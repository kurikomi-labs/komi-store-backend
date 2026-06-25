package zed.rainxch.githubstore.ingest

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.Repos
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.ranking.SearchScore
import zed.rainxch.githubstore.topics.TopicCodeMapper
import zed.rainxch.githubstore.util.FeatureFlags
import zed.rainxch.githubstore.util.formatRecency
import zed.rainxch.githubstore.util.queryHash
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

class GitHubSearchClient(
    private val meilisearchClient: zed.rainxch.githubstore.db.MeilisearchClient,
) {
    private val log = LoggerFactory.getLogger(GitHubSearchClient::class.java)

    // Single fallback token used in two cases:
    //   1. When the rotation pool is empty (no GH_TOKEN_* set)
    //   2. During the fetcher's quiet window (see below), so the full rotation
    //      pool is free for the daily fetch + meili_sync to consume.
    private val githubToken: String? = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }

    // Rotation pool reusing the same tokens the Python fetcher uses. Outside
    // the quiet window, the backend's fallback passthrough + workers
    // round-robin across these to spread load.
    private val tokenPool: List<String> = listOf(
        System.getenv("GH_TOKEN_TRENDING"),
        System.getenv("GH_TOKEN_NEW_RELEASES"),
        System.getenv("GH_TOKEN_MOST_POPULAR"),
        System.getenv("GH_TOKEN_TOPICS"),
    ).mapNotNull { it?.takeIf { s -> s.isNotBlank() } }

    private val rotationCursor = AtomicInteger(0)

    // Quiet window (UTC hours): the backend avoids the rotation pool during
    // this range so the Python fetcher's daily run gets the full 4-token
    // quota. Default 01:00–04:00 covers "1 hour before + ~2 hour run" for
    // the 02:00 UTC daily fetch. Override via env if the schedule changes.
    private val quietStartUtcHour: Int =
        System.getenv("TOKEN_QUIET_START_UTC")?.toIntOrNull() ?: 1
    private val quietEndUtcHour: Int =
        System.getenv("TOKEN_QUIET_END_UTC")?.toIntOrNull() ?: 4

    // Exposed (internal) so cross-class callers in the same module
    // (GitHubResourceClient via AppModule) can mirror the quiet-window
    // guarantee without duplicating the env-var parsing or the wrap-midnight
    // arithmetic.
    internal fun isQuietWindowNow(): Boolean {
        val h = OffsetDateTime.now(ZoneOffset.UTC).hour
        return if (quietStartUtcHour <= quietEndUtcHour) {
            h in quietStartUtcHour until quietEndUtcHour
        } else {
            // Window wraps midnight (e.g. start=23, end=4).
            h >= quietStartUtcHour || h < quietEndUtcHour
        }
    }

    // Central fallback token selector. Called whenever a request doesn't carry
    // X-GitHub-Token (i.e. passthrough, SignalAggregationWorker,
    // RepoRefreshWorker, ExternalMatchService — everything that shares the
    // backend's own quota). `internal` so cross-package callers in the same
    // module (notably ExternalMatchService) can route through the same
    // rotation pool and respect the quiet-window guarantee.
    internal fun pickFallbackToken(): String? {
        if (isQuietWindowNow()) return githubToken
        if (tokenPool.isEmpty()) return githubToken
        val idx = rotationCursor.getAndIncrement().rem(tokenPool.size).let {
            if (it < 0) it + tokenPool.size else it
        }
        return tokenPool[idx]
    }

    // Lazy so the CIO engine + non-daemon selector threads only spawn on
    // first request. Tests that construct this client transitively (e.g.
    // via ExternalMatchService's constructor) avoid the engine unless they
    // exercise a code path that calls into the HTTP layer.
    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            // Without these, a stalled GitHub call could hang a request handler
            // and its Hikari connection indefinitely. 15s is a generous ceiling —
            // GitHub's p99 is sub-second for both search and release-list calls.
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    // Single point of truth for outbound GitHub calls: applies the right
    // Authorization header, makes the call, and on a rate-limited response
    // retries once with a different pool token when conditions allow.
    //
    // Retry conditions: outside the fetcher quiet window, and only when a
    // different pool token is available than the one that just got limited.
    // During the quiet window the pool belongs to the daily fetcher — a rate
    // limit is surfaced verbatim instead.
    private suspend fun githubGet(
        url: String,
        userToken: String?,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): io.ktor.client.statement.HttpResponse {
        val firstToken = userToken ?: pickFallbackToken()
        val first = client.get(url) {
            header("Accept", "application/vnd.github+json")
            if (firstToken != null) header("Authorization", "token $firstToken")
            configure()
        }
        if (!isRateLimited(first.status.value, first.headers)) return first
        if (isQuietWindowNow()) return first
        val retryToken = pickFallbackToken()
        if (retryToken == null || retryToken == firstToken) return first
        log.info("Retrying rate-limited GitHub call with fallback pool: url={}", url)
        return client.get(url) {
            header("Accept", "application/vnd.github+json")
            header("Authorization", "token $retryToken")
            configure()
        }
    }

    private fun isRateLimited(status: Int, headers: io.ktor.http.Headers): Boolean {
        if (status == 429) return true
        if (status == 403) {
            if (headers["x-ratelimit-remaining"] == "0") return true
            if (headers["retry-after"] != null) return true
        }
        return false
    }

    // Persistence happens off the request path: respond to the user with the enriched
    // results first, then upsert to Postgres + Meili in this supervised scope so
    // failures never crash the parent and never block the response.
    private val persistenceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            log.warn("Async passthrough persistence failed", e)
        }
    )

    // Bounds how many passthrough/refresh upserts can be in flight at once.
    // Without this, a traffic burst queues unbounded coroutines each holding
    // a Hikari connection (pool size 20) — pool exhaustion starves request
    // handlers. 4 keeps plenty of pool capacity for live requests.
    private val persistenceGate = Semaphore(permits = 4)

    // Caps concurrent cold-search upstream work. searchAndIngest and explore
    // each fan out to GitHub (search + per-candidate release fetch); under a
    // burst of distinct cold queries this can saturate the rotation pool and
    // backlog Ktor request workers waiting on upstream. 8 in-flight searches
    // is comfortable for the 4-token rotation pool and leaves the remaining
    // pool capacity for warm Meili-served traffic.
    private val coldQueryGate = Semaphore(permits = 8)

    private val platformExtensions = mapOf(
        "android" to listOf(".apk", ".aab"),
        "windows" to listOf(".exe", ".msi", ".msix"),
        "macos"   to listOf(".dmg", ".pkg"),
        "linux"   to listOf(".appimage", ".deb", ".rpm", ".flatpak"),
    )

    // NSFW blocklist — mirrors the Python fetcher's BLOCKED_TOPICS.
    // Applied to query, topics, and description of GitHub search results.
    private val blockedTerms = setOf(
        "nsfw", "porn", "pornography", "hentai", "e-hentai", "ehentai",
        "adult", "adult-content", "xxx", "erotic", "erotica", "sex",
        "nude", "nudes", "nudity", "lewd", "r18", "r-18",
        "rule34", "rule-34", "booru", "gelbooru", "danbooru",
        "nhentai", "hanime", "ecchi", "yaoi", "yuri", "doujin", "doujinshi",
        "onlyfans", "fansly", "chaturbate", "xvideos", "pornhub",
        "xhamster", "xnxx", "redtube", "cam-girl", "camgirl",
        "fetish", "bdsm", "harem", "waifu", "18+",
    )

    private fun queryIsBlocked(query: String): Boolean {
        val lower = query.lowercase()
        return blockedTerms.any { term -> lower.contains(term) }
    }

    private fun repoIsBlocked(repo: GitHubRepo): Boolean {
        val topicsLower = repo.topics.map { it.lowercase() }.toSet()
        if (topicsLower.any { it in blockedTerms }) return true
        val desc = (repo.description ?: "").lowercase()
        return blockedTerms.any { term -> desc.contains(term) }
    }

    /**
     * Search GitHub for repos matching the query, check for installer releases,
     * ingest into Postgres + Meilisearch, and return the results.
     *
     * Transitional: this returns just the hits list for backward compatibility
     * with the existing SearchRoutes caller. New callers should use
     * `searchAndIngestOutcome` so they can distinguish "GitHub had no matches"
     * from "upstream failed". The route migration happens in a follow-up.
     */
    suspend fun searchAndIngest(
        query: String,
        platform: String?,
        limit: Int = 10,
        userToken: String? = null,
    ): List<RepoResponse> = when (val outcome = searchAndIngestOutcome(query, platform, limit, userToken)) {
        is SearchOutcome.Hits -> outcome.results
        SearchOutcome.NoMatches -> emptyList()
        is SearchOutcome.UpstreamFailed -> emptyList()
    }

    suspend fun searchAndIngestOutcome(
        query: String,
        platform: String?,
        limit: Int = 10,
        userToken: String? = null,
    ): SearchOutcome {
        if (queryIsBlocked(query)) return SearchOutcome.NoMatches
        if (FeatureFlags.disableLiveGitHubPassthrough) {
            log.info("Live GitHub passthrough disabled; skipping query={}", queryHash(query))
            return SearchOutcome.NoMatches
        }

        return try {
            coldQueryGate.withPermit {
                var withInstallers = runPass(query, platform, limit, userToken, nameMatch = false)
                // When the stars-sorted pass yields nothing installable, try an
                // in:name pass. Lets genuinely niche literal-name matches (e.g. a
                // 12-star app whose repo IS the query) surface past the library
                // projects that dominate general best-match rankings.
                if (withInstallers.isEmpty()) {
                    withInstallers = runPass(query, platform, limit, userToken, nameMatch = true)
                }
                if (withInstallers.isEmpty()) return@withPermit SearchOutcome.NoMatches

                persistenceScope.launch {
                    persistenceGate.withPermit {
                        val scoredByRepoId = ingestToPostgres(withInstallers)
                        syncToMeilisearch(withInstallers, scoredByRepoId)
                        log.info("On-demand ingest: {} repos for query={}", withInstallers.size, queryHash(query))
                    }
                }

                SearchOutcome.Hits(withInstallers.map { it.toRepoResponse() })
            }
        } catch (e: Exception) {
            log.warn("GitHub search passthrough failed for query={}: {}", queryHash(query), e.message)
            Sentry.captureException(e)
            SearchOutcome.UpstreamFailed(reason = e.message ?: e::class.java.simpleName, cause = e)
        }
    }

    /**
     * Explicit user-triggered "Fetch more from GitHub" — paginated deep search.
     * Returns (new repos added, hasMore flag).
     */
    suspend fun explore(
        query: String,
        platform: String?,
        page: Int,
        userToken: String? = null,
    ): ExploreResult {
        try {
            if (queryIsBlocked(query)) return ExploreResult(emptyList(), hasMore = false)
            if (FeatureFlags.disableLiveGitHubPassthrough) {
                log.info("Live GitHub passthrough disabled; skipping explore query={} page={}", queryHash(query), page)
                return ExploreResult(emptyList(), hasMore = false)
            }

            return coldQueryGate.withPermit {
                // Fetch 10 repos per page, require 5+ stars to filter abandoned junk
                val repos = searchGitHub(query, limit = 10, page = page, minStars = 5, userToken = userToken)
                if (repos.isEmpty()) return@withPermit ExploreResult(emptyList(), hasMore = false)

                val filtered = repos.filter { repo ->
                    !repo.archived && !repo.disabled && !repoIsBlocked(repo)
                }

                val withInstallers = coroutineScope {
                    filtered.map { repo ->
                        async {
                            val releases = fetchAllReleases(repo.fullName, userToken)
                            val latest = releases.firstOrNull { !it.draft && !it.prerelease }
                                ?: return@async null
                            val platformFlags = detectPlatforms(latest)
                            if (platformFlags.none { it.value }) return@async null
                            if (platform != null && platformFlags[platform] != true) return@async null
                            val downloadCount = releases.sumOf { r -> r.assets.sumOf { it.downloadCount } }
                            RepoWithRelease(repo, latest, platformFlags, downloadCount)
                        }
                    }.awaitAll()
                }.filterNotNull()

                if (withInstallers.isNotEmpty()) {
                    persistenceScope.launch {
                        persistenceGate.withPermit {
                            val scoredByRepoId = ingestToPostgres(withInstallers)
                            syncToMeilisearch(withInstallers, scoredByRepoId)
                            log.info("Explore ingest: {} repos for query={} page={}", withInstallers.size, queryHash(query), page)
                        }
                    }
                }

                // hasMore signals "worth asking for another page". A full raw page
                // that filtered down to zero installables almost always means
                // subsequent pages (same sort order, similar repos) will too — so
                // flip hasMore false when the installable yield is empty. Prevents
                // the client from looping forever on queries like "insta" where
                // everything matches text but nothing ships with installers.
                val hasMore = repos.size >= 10 && withInstallers.isNotEmpty()
                ExploreResult(withInstallers.map { it.toRepoResponse() }, hasMore = hasMore)
            }
        } catch (e: Exception) {
            log.warn("Explore failed for query={} page {}: {}", queryHash(query), page, e.message)
            return ExploreResult(emptyList(), hasMore = false)
        }
    }

    private suspend fun runPass(
        query: String,
        platform: String?,
        limit: Int,
        userToken: String?,
        nameMatch: Boolean,
    ): List<RepoWithRelease> {
        if (FeatureFlags.disableLiveGitHubPassthrough) return emptyList()
        // Fan-out cap: 10 candidates × up to 3 release pages = ~30 GitHub
        // calls per pass, vs ~1500 with the old 30 / 50 settings. Tighter
        // ceiling makes a flood of cold queries far less able to drain
        // the rotation pool.
        val repos = searchGitHub(
            query = query,
            limit = 10,
            page = 1,
            minStars = if (nameMatch) 5 else 10,
            userToken = userToken,
            nameMatch = nameMatch,
        )
        if (repos.isEmpty()) return emptyList()
        val candidates = repos.filterNot { repoIsBlocked(it) }
        return coroutineScope {
            candidates.map { repo ->
                async {
                    val releases = fetchAllReleases(repo.fullName, userToken)
                    val latest = releases.firstOrNull { !it.draft && !it.prerelease }
                        ?: return@async null
                    val platformFlags = detectPlatforms(latest)
                    if (platformFlags.none { it.value }) return@async null
                    if (platform != null && platformFlags[platform] != true) return@async null
                    val downloadCount = releases.sumOf { r -> r.assets.sumOf { it.downloadCount } }
                    RepoWithRelease(repo, latest, platformFlags, downloadCount)
                }
            }.awaitAll()
        }.filterNotNull().take(limit)
    }

    private suspend fun searchGitHub(
        query: String,
        limit: Int,
        page: Int = 1,
        minStars: Int = 10,
        userToken: String? = null,
        nameMatch: Boolean = false,
    ): List<GitHubRepo> {
        val qParts = buildList {
            add(query)
            if (nameMatch) add("in:name")
            add("stars:>=$minStars")
            add("fork:true")
        }
        val response = githubGet("https://api.github.com/search/repositories", userToken) {
            // fork:true includes both forks and non-forks.
            // Abandoned forks get filtered out later by installer release + star checks.
            parameter("q", qParts.joinToString(" "))
            // in:name passes rely on GitHub's default best-match so literal
            // name matches surface first. The generic pass sorts by stars to
            // favor popular apps.
            if (!nameMatch) parameter("sort", "stars")
            parameter("per_page", limit)
            parameter("page", page)
        }

        if (response.status != HttpStatusCode.OK) return emptyList()
        return response.body<GitHubSearchResponse>().items
    }

    // Fetches recent releases by following the Link: next header. Bounded at
    // 3 pages (300 releases) — the long tail of older download counts past
    // that is rounding noise for ranking, and the cap keeps a single cold
    // query from dispatching ~50 GitHub API calls per candidate.
    private suspend fun fetchAllReleases(fullName: String, userToken: String? = null): List<GitHubRelease> {
        val out = mutableListOf<GitHubRelease>()
        var url: String? = "https://api.github.com/repos/$fullName/releases?per_page=100"
        var pages = 0
        while (url != null && pages < 3) {
            try {
                val response = githubGet(url, userToken)
                if (response.status != HttpStatusCode.OK) break
                out += response.body<List<GitHubRelease>>()
                url = parseNextLink(response.headers["Link"])
                pages++
            } catch (_: Exception) {
                break
            }
        }
        return out
    }

    private val linkNextRegex = Regex("""<([^>]+)>;\s*rel="next"""")

    private fun parseNextLink(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        return linkNextRegex.find(linkHeader)?.groupValues?.getOrNull(1)
    }

    // Re-fetch an existing repo's metadata + releases for RepoRefreshWorker.
    // Returns null if the repo is gone (404), archived, or has no installable
    // release — the caller uses that to soft-delete / mark stale.
    internal suspend fun refreshRepo(fullName: String, userToken: String? = null): RefreshResult {
        if (FeatureFlags.disableLiveGitHubPassthrough) return RefreshResult.TransientFailure
        val metaResponse = try {
            githubGet("https://api.github.com/repos/$fullName", userToken)
        } catch (_: Exception) {
            return RefreshResult.TransientFailure
        }
        if (metaResponse.status == HttpStatusCode.NotFound) return RefreshResult.Gone
        if (metaResponse.status != HttpStatusCode.OK) return RefreshResult.TransientFailure
        val repo = metaResponse.body<GitHubRepo>()
        if (repo.archived || repo.disabled) return RefreshResult.Archived

        val releases = fetchAllReleases(fullName, userToken)
        val latest = releases.firstOrNull { !it.draft && !it.prerelease }
            ?: return RefreshResult.NoUsableRelease(repo)
        val platformFlags = detectPlatforms(latest)
        val downloadCount = releases.sumOf { r -> r.assets.sumOf { it.downloadCount } }
        return RefreshResult.Ok(RepoWithRelease(repo, latest, platformFlags, downloadCount))
    }

    // Writes a single RepoWithRelease to Postgres + schedules Meili sync.
    // Exposed for RepoRefreshWorker so it doesn't duplicate upsert plumbing.
    internal fun persist(refreshed: RepoWithRelease) {
        val scored = ingestToPostgres(listOf(refreshed))
        persistenceScope.launch {
            persistenceGate.withPermit {
                syncToMeilisearch(listOf(refreshed), scored)
            }
        }
    }

    internal data class DeleteResult(val id: Long, val meiliPurged: Boolean)

    // Admin-only hard delete by GitHub numeric id. Removes the Postgres row
    // (FK ON DELETE CASCADE clears repo_categories / repo_topic_buckets /
    // repo_signals / repo_stats_daily / feed_exposure / repo_daily_snapshot)
    // then purges the Meili doc keyed by the same id. Raw JDBC mirrors the
    // markPushedAtFallback pattern in InternalRoutes. Returns null when no row
    // matched. Meili failure is logged, not fatal — Postgres is the source of
    // truth and the caller gets meiliPurged=false to retry the purge.
    internal suspend fun deleteRepoById(id: Long): DeleteResult? {
        val deleted = transaction {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            conn.prepareStatement("DELETE FROM repos WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
        if (deleted == 0) return null
        val purged = purgeMeiliDoc(id)
        log.info("Admin delete repo id={} (meiliPurged={})", id, purged)
        return DeleteResult(id, purged)
    }

    // Admin-only hard delete by full_name (the unique key). full_name is unique
    // so this removes exactly one row regardless of its (possibly stale) id —
    // the heal path for a delete+recreate row whose id no longer resolves on
    // GitHub. Resolves the row's id first so the Meili doc can be purged by it.
    internal suspend fun deleteRepoByFullName(fullName: String): DeleteResult? {
        val id = transaction {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            val existing = conn.prepareStatement("SELECT id FROM repos WHERE full_name = ?").use { ps ->
                ps.setString(1, fullName)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            } ?: return@transaction null
            conn.prepareStatement("DELETE FROM repos WHERE id = ?").use { ps ->
                ps.setLong(1, existing)
                ps.executeUpdate()
            }
            existing
        } ?: return null
        val purged = purgeMeiliDoc(id)
        log.info("Admin delete repo full_name={} id={} (meiliPurged={})", fullName, id, purged)
        return DeleteResult(id, purged)
    }

    private suspend fun purgeMeiliDoc(id: Long): Boolean =
        runCatching { meilisearchClient.deleteDocument(id) }
            .onFailure { log.warn("Meili delete failed for id={}: {}", id, it.message) }
            .isSuccess

    internal sealed class RefreshResult {
        data class Ok(val repo: RepoWithRelease) : RefreshResult()
        object Gone : RefreshResult()
        object Archived : RefreshResult()
        data class NoUsableRelease(val repo: GitHubRepo) : RefreshResult()
        object TransientFailure : RefreshResult()
    }

    private fun detectPlatforms(release: GitHubRelease): Map<String, Boolean> {
        val assetNames = release.assets.map { it.name.lowercase() }
        return platformExtensions.mapValues { (_, exts) ->
            assetNames.any { name -> exts.any { name.endsWith(it) } }
        }
    }

    // Returns a map of repo_id → search_score for the repos just upserted,
    // so syncToMeilisearch can include the score on its POST payload
    // (without it, Meili's full-doc replace wipes the worker's score).
    private fun ingestToPostgres(repos: List<RepoWithRelease>): Map<Long, Double> {
        val scoredByRepoId = mutableMapOf<Long, Double>()
        transaction {
            for (r in repos) {
                val repo = r.repo
                val platforms = r.platformFlags
                val releaseDate = r.release.publishedAt?.let {
                    try { OffsetDateTime.parse(it) } catch (_: Exception) { null }
                }
                // Seed a cold-start search_score so passthrough-ingested repos
                // don't sit at NULL (sorting to the bottom) until the next
                // SignalAggregationWorker cycle. On re-ingest, preserve the
                // worker's refined score — otherwise upsert would wipe it
                // back to the cold-start value on every passthrough hit.
                val daysSinceRelease = SearchScore.daysSinceRelease(releaseDate?.toInstant())
                val existingScore: Float? = Repos
                    .select(Repos.searchScore)
                    .where { Repos.id eq repo.id }
                    .firstOrNull()
                    ?.get(Repos.searchScore)
                val scoreToWrite = existingScore ?: SearchScore.compute(
                    stars = repo.stargazersCount,
                    daysSinceRelease = daysSinceRelease,
                ).toFloat()
                Repos.upsert(Repos.id) {
                    it[id] = repo.id
                    it[fullName] = repo.fullName
                    it[owner] = repo.owner.login
                    it[name] = repo.name
                    it[ownerAvatarUrl] = repo.owner.avatarUrl
                    it[description] = repo.description
                    it[defaultBranch] = repo.defaultBranch
                    it[htmlUrl] = repo.htmlUrl
                    it[stars] = repo.stargazersCount
                    it[forks] = repo.forksCount
                    it[openIssues] = repo.openIssuesCount
                    it[licenseSpdxId] = repo.license?.spdxId
                    it[licenseName] = repo.license?.name
                    it[language] = repo.language
                    it[topics] = repo.topics
                    it[latestReleaseDate] = releaseDate
                    it[latestReleaseTag] = r.release.tagName
                    it[hasInstallersAndroid] = platforms["android"] ?: false
                    it[hasInstallersWindows] = platforms["windows"] ?: false
                    it[hasInstallersMacos] = platforms["macos"] ?: false
                    it[hasInstallersLinux] = platforms["linux"] ?: false
                    it[downloadCount] = r.downloadCount
                    it[searchScore] = scoreToWrite
                    it[pushedAtGh] = repo.pushedAt?.let {
                        try { OffsetDateTime.parse(it) } catch (_: Exception) { null }
                    }
                    it[indexedAt] = OffsetDateTime.now()
                }
                scoredByRepoId[repo.id] = scoreToWrite.toDouble()
            }
        }
        return scoredByRepoId
    }

    private suspend fun syncToMeilisearch(
        repos: List<RepoWithRelease>,
        scoredByRepoId: Map<Long, Double>,
    ) {
        try {
            val docs = repos.map { r ->
                zed.rainxch.githubstore.db.MeiliRepoHit(
                    id = r.repo.id,
                    full_name = r.repo.fullName,
                    owner = r.repo.owner.login,
                    name = r.repo.name,
                    owner_avatar_url = r.repo.owner.avatarUrl,
                    description = r.repo.description,
                    default_branch = r.repo.defaultBranch,
                    html_url = r.repo.htmlUrl,
                    stars = r.repo.stargazersCount,
                    forks = r.repo.forksCount,
                    open_issues = r.repo.openIssuesCount,
                    license_spdx_id = r.repo.license?.spdxId,
                    license_name = r.repo.license?.name,
                    language = r.repo.language,
                    topics = r.repo.topics,
                    latest_release_date = r.release.publishedAt,
                    latest_release_tag = r.release.tagName,
                    download_count = r.downloadCount,
                    has_installers_android = r.platformFlags["android"] ?: false,
                    has_installers_windows = r.platformFlags["windows"] ?: false,
                    has_installers_macos = r.platformFlags["macos"] ?: false,
                    has_installers_linux = r.platformFlags["linux"] ?: false,
                    pushed_at = r.repo.pushedAt,
                    // Meili's POST /documents replaces the whole doc. Omitting this
                    // would wipe the SignalAggregationWorker's most recent score
                    // on every passthrough/refresh until the next hourly cycle.
                    search_score = scoredByRepoId[r.repo.id],
                )
            }
            meilisearchClient.addDocuments(docs)
        } catch (e: Exception) {
            log.warn("Failed to sync on-demand repos to Meilisearch: {}", e.message)
        }
    }

    internal data class RepoWithRelease(
        val repo: GitHubRepo,
        val release: GitHubRelease,
        val platformFlags: Map<String, Boolean>,
        val downloadCount: Long = 0,
    ) {
        fun toRepoResponse(): RepoResponse {
            val releaseDateStr = release.publishedAt
            val recencyDays = releaseDateStr?.let {
                try {
                    val rd = OffsetDateTime.parse(it)
                    ChronoUnit.DAYS.between(rd.toInstant(), OffsetDateTime.now().toInstant()).toInt().coerceAtLeast(0)
                } catch (_: Exception) { null }
            }

            return RepoResponse(
                id = repo.id,
                name = repo.name,
                fullName = repo.fullName,
                owner = RepoOwner(login = repo.owner.login, avatarUrl = repo.owner.avatarUrl),
                description = repo.description,
                defaultBranch = repo.defaultBranch,
                htmlUrl = repo.htmlUrl,
                stargazersCount = repo.stargazersCount,
                forksCount = repo.forksCount,
                openIssuesCount = repo.openIssuesCount,
                licenseSpdxId = repo.license?.spdxId,
                licenseName = repo.license?.name,
                license = repo.license?.let { zed.rainxch.githubstore.model.RepoLicense(spdxId = it.spdxId, name = it.name) },
                language = repo.language,
                topics = repo.topics,
                topicCodes = TopicCodeMapper.resolve(repo.topics),
                releasesUrl = "${repo.htmlUrl}/releases",
                updatedAt = repo.updatedAt,
                createdAt = repo.createdAt,
                pushedAt = repo.pushedAt,
                latestReleaseDate = releaseDateStr,
                latestReleaseTag = release.tagName,
                releaseRecency = recencyDays,
                releaseRecencyText = recencyDays?.let { formatRecency(it) },
                downloadCount = downloadCount,
                hasInstallersAndroid = platformFlags["android"] ?: false,
                hasInstallersWindows = platformFlags["windows"] ?: false,
                hasInstallersMacos = platformFlags["macos"] ?: false,
                hasInstallersLinux = platformFlags["linux"] ?: false,
            )
        }
    }
}

// GitHub API DTOs

// Canonical result type for `searchAndIngestOutcome`. The legacy
// `searchAndIngest(): List<RepoResponse>` collapses NoMatches and
// UpstreamFailed into the same empty list — fine for the current
// SearchRoutes shape, but the route refactor will switch over so
// passthroughAttempted / fallback semantics can distinguish the cases.
sealed interface SearchOutcome {
    data class Hits(val results: List<RepoResponse>) : SearchOutcome
    data object NoMatches : SearchOutcome
    data class UpstreamFailed(val reason: String, val cause: Throwable?) : SearchOutcome
}

data class ExploreResult(
    val items: List<RepoResponse>,
    val hasMore: Boolean,
)

@Serializable
data class GitHubSearchResponse(
    val items: List<GitHubRepo> = emptyList(),
)

@Serializable
data class GitHubRepo(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val owner: GitHubOwner,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("stargazers_count") val stargazersCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    // Includes open PRs (GitHub treats PRs as issues). Same number GitHub
    // website's Issues tab shows.
    @SerialName("open_issues_count") val openIssuesCount: Int = 0,
    // GitHub-detected license. Null on unlicensed repos or when GitHub's
    // classifier didn't recognise the LICENSE file.
    val license: GitHubLicense? = null,
    val language: String? = null,
    val topics: List<String> = emptyList(),
    val archived: Boolean = false,
    val disabled: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    // R5/R13: last default-branch commit, distinct from updated_at (metadata change).
    @SerialName("pushed_at") val pushedAt: String? = null,
)

@Serializable
data class GitHubOwner(
    val login: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("download_count") val downloadCount: Long = 0,
)

// GitHub's license object on /repos/{o}/{n}. We persist `spdx_id` + `name`
// only; the upstream `key`, `url`, and `node_id` aren't surfaced.
@Serializable
data class GitHubLicense(
    @SerialName("spdx_id") val spdxId: String? = null,
    val name: String? = null,
)
