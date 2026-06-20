package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.respondNotFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import java.time.OffsetDateTime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.FeedCoverageStats
import zed.rainxch.githubstore.db.FeedRepository
import zed.rainxch.githubstore.db.Repos
import zed.rainxch.githubstore.ingest.GitHubRepo
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

private const val BASIC_AUTH_REALM = "github-store-admin"
const val ADMIN_BASIC_AUTH = "admin-basic"

private val internalLog = LoggerFactory.getLogger("InternalRoutes")

// Single shared scope for admin-triggered background jobs (currently just
// the metadata-backfill endpoint). SupervisorJob so one job's failure does
// not cancel others. IO dispatcher because the work is HTTP + JDBC.
private val backfillScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

// One backfill at a time. Concurrent re-entry would double-count budget +
// race upserts. atomic CAS lets the first call claim, returning 409 to
// concurrent triggers.
private val backfillRunning = AtomicBoolean(false)

fun Route.internalRoutes(
    metrics: SearchMetricsRegistry,
    workerSupervisor: WorkerSupervisor,
    searchClient: GitHubSearchClient,
    feedRepository: FeedRepository,
) {
    val adminToken: String? = System.getenv("ADMIN_TOKEN")?.takeIf { it.isNotBlank() }
    val isProduction = System.getenv("APP_ENV") == "production"

    // Fail-closed under prod when ADMIN_TOKEN is missing (see the authenticate
    // setup in Plugins.kt — the provider returns null-credentials which Ktor
    // treats as unauthenticated, which `authenticate { }` rejects with 401).
    // The extra guard here is belt-and-suspenders: even if Plugins.kt gets
    // mis-edited, the internal routes never register without a token.
    if (isProduction && adminToken == null) {
        route("/internal") {
            get("{...}") {
                respondNotFound(call)
            }
        }
        return
    }

    route("/internal") {
        // JSON metrics — accepts either Basic Auth (so the dashboard's fetch()
        // carries the browser's cached credentials) OR an X-Admin-Token header
        // (for curl / machine callers). optional=true makes the authenticate
        // block parse credentials when present but not require them; the
        // authorized() helper below is the actual gate.
        authenticate(ADMIN_BASIC_AUTH, optional = true) {
            get("/metrics") {
                if (!authorized(call, adminToken)) {
                    return@get respondNotFound(call)
                }
                // An authenticated endpoint must never be edge-cached.
                call.response.header(HttpHeaders.CacheControl, "no-store, private")
                val snap = metrics.snapshot()
                val counters = SearchCounters(
                    uptimeSeconds = snap.uptimeSeconds,
                    meiliOnly = snap.meiliOnly,
                    passthrough = snap.passthrough,
                    postgresFallback = snap.postgresFallback,
                    explore = snap.explore,
                    zeroResult = snap.zeroResult,
                    avgLatencyMs = snap.avgLatencyMs,
                )
                val (db, top, feedCoverage) = coroutineScope {
                    val dbAsync = async { fetchDbMetrics() }
                    val topAsync = async { fetchTopRepos() }
                    val feedAsync = async { fetchFeedCoverage(feedRepository) }
                    Triple(dbAsync.await(), topAsync.await(), feedAsync.await())
                }
                val workers = workerSupervisor.lastTicks().mapValues { it.value.toString() }
                call.respond(MetricsResponse(
                    counters = counters,
                    training = db,
                    topRepos = top,
                    workers = workers,
                    feedCoverage = feedCoverage,
                ))
            }
        }

        // One-shot metadata backfill: refresh every curated row whose new
        // columns (open_issues, license_*) are still at their migration
        // defaults because no upsert has touched them since V14/V15
        // landed. Run by an operator after a column-add deploy; no-ops
        // afterwards since the SQL filter no longer matches.
        //
        // Pacing: 500ms per repo (REPO_REFRESH_PACE_MS env honoured for
        // consistency with RepoRefreshWorker). Quiet-window respected to
        // keep the rotation pool free for the daily fetcher. Single
        // concurrent run -- subsequent triggers get 409 until the
        // current job finishes.
        post("/backfill-stale") {
            if (!authorized(call, adminToken)) {
                return@post respondNotFound(call)
            }
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 10_000)
                ?: 5_000
            if (!backfillRunning.compareAndSet(false, true)) {
                call.response.header(HttpHeaders.RetryAfter, "60")
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    BackfillResponse(scheduled = 0, started = false, message = "backfill_already_running"),
                )
            }
            val candidates = transaction {
                Repos.selectAll()
                    .where { Repos.licenseSpdxId.isNull() }
                    .orderBy(Repos.id)
                    .limit(limit)
                    .map { it[Repos.id] to it[Repos.fullName] }
            }
            if (candidates.isEmpty()) {
                backfillRunning.set(false)
                return@post call.respond(
                    HttpStatusCode.OK,
                    BackfillResponse(scheduled = 0, started = false, message = "no stale rows"),
                )
            }
            backfillScope.launch {
                try {
                    runBackfill(searchClient, candidates)
                } finally {
                    backfillRunning.set(false)
                }
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.Accepted,
                BackfillResponse(scheduled = candidates.size, started = true),
            )
        }

        // One-shot backfill for pushed_at_gh (V18). Iterates every repo
        // where pushed_at_gh IS NULL, re-fetches from GitHub, and writes
        // the field. Terminates once all rows are filled:
        //   - Ok / NoUsableRelease: real pushed_at from GitHub
        //   - Archived / Gone: COALESCE(updated_at_gh, indexed_at) as proxy
        //     so these rows are never reconsidered on subsequent runs
        //   - TransientFailure: left NULL, re-tried on next invocation
        // Shares the same backfillRunning gate as /backfill-stale so the
        // two never run concurrently and don't race the rotation pool.
        post("/backfill-pushed-at") {
            if (!authorized(call, adminToken)) {
                return@post respondNotFound(call)
            }
            val limit = call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 10_000)
                ?: 10_000
            if (!backfillRunning.compareAndSet(false, true)) {
                call.response.header(HttpHeaders.RetryAfter, "60")
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    BackfillResponse(scheduled = 0, started = false, message = "backfill_already_running"),
                )
            }
            val candidates = transaction {
                Repos.selectAll()
                    .where { Repos.pushedAtGh.isNull() }
                    .orderBy(Repos.id)
                    .limit(limit)
                    .map { it[Repos.id] to it[Repos.fullName] }
            }
            if (candidates.isEmpty()) {
                backfillRunning.set(false)
                return@post call.respond(
                    HttpStatusCode.OK,
                    BackfillResponse(scheduled = 0, started = false, message = "no rows missing pushed_at"),
                )
            }
            backfillScope.launch {
                try {
                    runBackfill(searchClient, candidates)
                } finally {
                    backfillRunning.set(false)
                }
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                HttpStatusCode.Accepted,
                BackfillResponse(scheduled = candidates.size, started = true),
            )
        }

        // Browser dashboard. Basic Auth required in prod so the browser prompts
        // for credentials on first visit; optional in dev for local inspection.
        authenticate(ADMIN_BASIC_AUTH, optional = adminToken == null) {
            get("/dashboard") {
                val html = {}.javaClass.classLoader
                    .getResourceAsStream("admin/dashboard.html")
                    ?.bufferedReader()?.readText()
                    ?: return@get call.respond(HttpStatusCode.InternalServerError)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Robots-Tag", "noindex, nofollow")
                call.respondText(html, ContentType.Text.Html)
            }
        }
    }
}

// Returns true if the caller is allowed to see JSON metrics. Three paths:
//   - Dev (adminToken == null): always allowed.
//   - Header X-Admin-Token matches: allowed.
//   - Authenticated via Basic Auth (browser reloading the /metrics fetch with
//     cached credentials): allowed.
private fun authorized(call: io.ktor.server.application.ApplicationCall, adminToken: String?): Boolean {
    if (adminToken == null) return true
    val header = call.request.headers["X-Admin-Token"]
    // Constant-time compare to defang token-length / per-byte timing oracles.
    // MessageDigest.isEqual NPEs on null inputs, so guard first.
    if (header != null && MessageDigest.isEqual(
            header.toByteArray(StandardCharsets.UTF_8),
            adminToken.toByteArray(StandardCharsets.UTF_8),
        )) return true
    val principal = call.principal<UserIdPrincipal>()
    return principal != null
}

// One-shot backfill loop. Re-uses GitHubSearchClient.refreshRepo + persist
// (the same path RepoRefreshWorker runs nightly), but drops the curated-row
// exclusion so we hit the catalog rows the worker leaves alone. Pacing
// mirrors REPO_REFRESH_PACE_MS so an operator who tuned the worker also
// tunes this. Quiet window respected -- the rotation pool belongs to the
// daily fetcher between 1-4 UTC.
private suspend fun runBackfill(
    searchClient: GitHubSearchClient,
    candidates: List<Pair<Long, String>>,
) {
    val pacePerRepoMs: Long = (System.getenv("REPO_REFRESH_PACE_MS")?.toLongOrNull() ?: 500L)
        .coerceAtLeast(0L)
    var ok = 0
    var metadataOnly = 0
    var gone = 0
    var archived = 0
    var failed = 0
    for ((_, fullName) in candidates) {
        // Quiet-window guard: pause the loop, don't burn the candidate.
        // The daily fetcher's pool stays free; we resume after the window.
        while (searchClient.isQuietWindowNow()) {
            delay(60_000)
        }
        when (val result = searchClient.refreshRepo(fullName)) {
            is GitHubSearchClient.RefreshResult.Ok -> {
                searchClient.persist(result.repo)
                ok++
            }
            is GitHubSearchClient.RefreshResult.NoUsableRelease -> {
                // Repo metadata fetched fine but has no installable release.
                // The full persist path requires release info, so update the
                // drift-prone metadata columns directly. Otherwise these rows
                // would stay at default open_issues=0 / NULL license forever
                // and re-appear in every subsequent backfill query -- the
                // exact failure mode that prompted this fix.
                upsertMetadataOnly(result.repo)
                metadataOnly++
            }
            GitHubSearchClient.RefreshResult.Gone -> {
                // Repo deleted on GitHub — stamp with existing data so it
                // doesn't reappear in pushed_at_gh IS NULL queries forever.
                markPushedAtFallback(fullName)
                gone++
            }
            GitHubSearchClient.RefreshResult.Archived -> {
                // Repo archived — same stamping rationale as Gone.
                markPushedAtFallback(fullName)
                archived++
            }
            GitHubSearchClient.RefreshResult.TransientFailure -> failed++
        }
        delay(pacePerRepoMs)
    }
    internalLog.info(
        "Backfill done: ok={} metadata-only={} gone={} archived={} transient-fail={} (of {})",
        ok, metadataOnly, gone, archived, failed, candidates.size,
    )
}

// Metadata-only UPDATE for repos without an installable release. Touches
// just the drift-prone columns (the new ones added by V14/V15 plus
// stars/forks/description/archived which are also volatile). Leaves
// release-related columns alone -- they were already correct from the
// last successful Ok-path refresh, OR they're at schema defaults because
// no release ever existed (correct outcome either way).
private fun upsertMetadataOnly(repo: GitHubRepo) {
    transaction {
        Repos.update({ Repos.fullName eq repo.fullName }) {
            it[stars] = repo.stargazersCount
            it[forks] = repo.forksCount
            it[openIssues] = repo.openIssuesCount
            it[licenseSpdxId] = repo.license?.spdxId
            it[licenseName] = repo.license?.name
            it[description] = repo.description
            it[pushedAtGh] = repo.pushedAt?.let { raw ->
                try { OffsetDateTime.parse(raw) } catch (_: Exception) { null }
            }
            it[indexedAt] = OffsetDateTime.now()
        }
    }
}

// For Gone/Archived repos we have no live pushed_at from GitHub.
// Use the best available proxy from existing data so the row stops
// appearing in the pushed_at_gh IS NULL filter on future backfill runs.
private fun markPushedAtFallback(fullName: String) {
    transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement(
            """
            UPDATE repos
            SET pushed_at_gh = COALESCE(updated_at_gh, indexed_at)
            WHERE full_name = ? AND pushed_at_gh IS NULL
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, fullName)
            ps.executeUpdate()
        }
    }
}

// Feed-v2 rotation health, one row per feed variant. null = the all-platform
// feed; the four named platforms rotate independently (per-platform cooldown),
// so a platform whose coverage has stalled must be visible on its own line.
private suspend fun fetchFeedCoverage(feedRepository: FeedRepository): List<FeedCoverageStats> = coroutineScope {
    val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay().toInt()
    listOf(null, "android", "windows", "macos", "linux")
        .map { p -> async { feedRepository.coverageStats(p, today) } }
        .map { it.await() }
}

private suspend fun fetchDbMetrics(): TrainingMetrics = coroutineScope {
    val unprocessed = async { countUnprocessedMisses() }
    val reposWithSignals = async { countReposWithSignals() }
    val reposWithSearchScore = async { countReposWithSearchScore() }
    val topMisses = async { fetchTopMisses() }
    TrainingMetrics(
        unprocessedMisses = unprocessed.await(),
        reposWithSignals = reposWithSignals.await(),
        reposWithSearchScore = reposWithSearchScore.await(),
        topMissesLast7d = topMisses.await(),
    )
}

private suspend fun countUnprocessedMisses(): Long = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    conn.prepareStatement(
        "SELECT COUNT(*) FROM search_misses WHERE last_processed_at IS NULL"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }
}

private suspend fun countReposWithSignals(): Long = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    conn.prepareStatement(
        "SELECT COUNT(*) FROM repo_signals"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }
}

private suspend fun countReposWithSearchScore(): Long = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    conn.prepareStatement(
        "SELECT COUNT(*) FROM repos WHERE search_score IS NOT NULL"
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }
}

// Top misses: report only the hash-prefix and counts. Raw query text is
// no longer stored (privacy hardening, V6) so the dashboard surfaces an
// 8-char identifier per query — useful for spotting hotspots without
// exposing what users typed.
private suspend fun fetchTopMisses(): List<TopMiss> = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    val out = mutableListOf<TopMiss>()
    conn.prepareStatement(
        """
        SELECT query_hash, miss_count, result_count, last_seen_at
        FROM search_misses
        WHERE last_seen_at > NOW() - INTERVAL '7 days'
        ORDER BY miss_count DESC
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                out.add(
                    TopMiss(
                        query = rs.getString("query_hash")?.take(8) ?: "—",
                        missCount = rs.getInt("miss_count"),
                        resultCount = rs.getObject("result_count") as? Int,
                        lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant()?.toString(),
                    )
                )
            }
        }
    }
    out
}

private suspend fun fetchTopRepos(): TopRepos = coroutineScope {
    val byScore = async { fetchTopReposByScore() }
    val byClicks = async { fetchTopReposByClicks() }
    val byInstalls = async { fetchTopReposByInstalls() }
    TopRepos(
        byScore = byScore.await(),
        byClicks = byClicks.await(),
        byInstalls = byInstalls.await(),
    )
}

private suspend fun fetchTopReposByScore(): List<TopRepo> = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    val out = mutableListOf<TopRepo>()
    conn.prepareStatement(
        """
        SELECT id, full_name, stars, search_score
        FROM repos
        WHERE search_score IS NOT NULL
        ORDER BY search_score DESC NULLS LAST
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                out.add(
                    TopRepo(
                        id = rs.getLong("id"),
                        fullName = rs.getString("full_name"),
                        value = (rs.getObject("search_score") as? Number)?.toDouble() ?: 0.0,
                        stars = rs.getInt("stars"),
                    )
                )
            }
        }
    }
    out
}

private suspend fun fetchTopReposByClicks(): List<TopRepo> = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    val out = mutableListOf<TopRepo>()
    conn.prepareStatement(
        """
        SELECT r.id, r.full_name, r.stars, s.click_count_30d AS v
        FROM repo_signals s
        INNER JOIN repos r ON r.id = s.repo_id
        WHERE s.click_count_30d > 0
        ORDER BY s.click_count_30d DESC
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                out.add(
                    TopRepo(
                        id = rs.getLong("id"),
                        fullName = rs.getString("full_name"),
                        value = rs.getInt("v").toDouble(),
                        stars = rs.getInt("stars"),
                    )
                )
            }
        }
    }
    out
}

private suspend fun fetchTopReposByInstalls(): List<TopRepo> = newSuspendedTransaction(Dispatchers.IO) {
    val conn = TransactionManager.current().connection.connection as java.sql.Connection
    val out = mutableListOf<TopRepo>()
    conn.prepareStatement(
        """
        SELECT r.id, r.full_name, r.stars, s.install_success_30d AS v
        FROM repo_signals s
        INNER JOIN repos r ON r.id = s.repo_id
        WHERE s.install_success_30d > 0
        ORDER BY s.install_success_30d DESC
        LIMIT 20
        """.trimIndent()
    ).use { ps ->
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                out.add(
                    TopRepo(
                        id = rs.getLong("id"),
                        fullName = rs.getString("full_name"),
                        value = rs.getInt("v").toDouble(),
                        stars = rs.getInt("stars"),
                    )
                )
            }
        }
    }
    out
}

@Serializable
data class BackfillResponse(
    val scheduled: Int,
    val started: Boolean,
    val message: String? = null,
)

@Serializable
data class MetricsResponse(
    val counters: SearchCounters,
    val training: TrainingMetrics,
    val topRepos: TopRepos = TopRepos(),
    val workers: Map<String, String> = emptyMap(),
    val feedCoverage: List<FeedCoverageStats> = emptyList(),
)

@Serializable
data class SearchCounters(
    val uptimeSeconds: Long,
    val meiliOnly: Long,
    val passthrough: Long,
    val postgresFallback: Long,
    val explore: Long,
    val zeroResult: Long,
    val avgLatencyMs: Long,
)

@Serializable
data class TrainingMetrics(
    val unprocessedMisses: Long,
    val reposWithSignals: Long,
    val reposWithSearchScore: Long,
    val topMissesLast7d: List<TopMiss>,
)

@Serializable
data class TopMiss(
    val query: String,
    val missCount: Int,
    val resultCount: Int?,
    val lastSeenAt: String?,
)

@Serializable
data class TopRepos(
    val byScore: List<TopRepo> = emptyList(),
    val byClicks: List<TopRepo> = emptyList(),
    val byInstalls: List<TopRepo> = emptyList(),
)

@Serializable
data class TopRepo(
    val id: Long,
    val fullName: String,
    val value: Double,
    val stars: Int,
)
