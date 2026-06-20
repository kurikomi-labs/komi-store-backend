package zed.rainxch.githubstore.db

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.topics.TopicCodeMapper
import zed.rainxch.githubstore.util.formatRecency
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// Candidate-pool queries for the /v1/feed assembler. Each pool is a bounded
// top-N slice of the curated `repos` table along one quality axis; the
// FeedAssembler interleaves them with diversity windows. Queries run once
// per (platform, epochDay) — FeedService caches the assembled result — so
// none of this is on the per-request hot path.
class FeedRepository {

    suspend fun trendingPool(platform: String?, limit: Int = 100): List<RepoResponse> =
        poolQuery(
            where = "trending_score IS NOT NULL",
            orderBy = "trending_score DESC",
            platform = platform,
            limit = limit,
        )

    suspend fun releasesPool(platform: String?, limit: Int = 100): List<RepoResponse> =
        poolQuery(
            where = "latest_release_date IS NOT NULL AND latest_release_date > NOW() - INTERVAL '14 days'",
            orderBy = "latest_release_date DESC",
            platform = platform,
            limit = limit,
        )

    // "Hidden gems": modest star count but recent release activity and a
    // non-null quality score. The band excludes both megaprojects (already
    // covered by trending/popular) and abandoned/empty repos.
    suspend fun gemsPool(platform: String?, limit: Int = 100): List<RepoResponse> =
        poolQuery(
            where = "stars BETWEEN 50 AND 800 " +
                "AND latest_release_date IS NOT NULL " +
                "AND latest_release_date > NOW() - INTERVAL '30 days' " +
                "AND search_score IS NOT NULL",
            orderBy = "search_score DESC",
            platform = platform,
            limit = limit,
        )

    suspend fun popularPool(platform: String?, limit: Int = 100): List<RepoResponse> =
        poolQuery(
            where = "popularity_score IS NOT NULL",
            orderBy = "popularity_score DESC",
            platform = platform,
            limit = limit,
        )

    // Broad quality-ranked source for the TOPICS pool. Canonical topic codes
    // are computed Kotlin-side (TopicCodeMapper) from raw topics at mapping
    // time, so the bucketing itself can't happen in SQL — fetch a wide
    // search_score slice and let FeedAssembler.bucketByPrimaryTopic split it.
    suspend fun topicSourcePool(platform: String?, limit: Int = 600): List<RepoResponse> =
        poolQuery(
            where = "search_score IS NOT NULL AND topics IS NOT NULL AND array_length(topics, 1) > 0",
            orderBy = "search_score DESC",
            platform = platform,
            limit = limit,
        )

    // ── feed-v2: single eligible-set selection (replaces the four capped pools) ──

    /**
     * The whole eligible quality pool for feed-v2, in one query. Unlike the four
     * capped pools above (each a top-N along one axis, so coverage is bounded by
     * union(top-N) ≪ the eligible set), this applies the binary quality gate and
     * returns every passing repo up to [limit] ≥ E, so cooldown can actually
     * reach demoted tail repos (design brief §4d).
     *
     * Per repo it also pulls the momentum + rotation inputs the read-path scorer
     * needs, all frozen off the request path:
     *  - latest computed star/dl velocity EWMA from repo_daily_snapshot (LATERAL,
     *    most-recent non-null row — null until ≥2 snapshots accrue, which the
     *    scorer treats as zero momentum → ranks on the static base);
     *  - feed_exposure cooldown state for (repo, platform).
     *
     * The cooldown CASE masks `last_shown_epochday == today`: today's own
     * exposure write must NOT feed today's ranking, or the intraday 3h rebuild
     * would re-rank against its own writes and tear pagination. With the mask,
     * every build within a UTC day reads identical cooldown inputs (brief §7).
     */
    suspend fun eligiblePool(
        platform: String?,
        todayEpochDay: Int,
        staleDays: Int = FEED_GATE_STALE_DAYS,
        limit: Int = FEED_ELIGIBLE_LIMIT,
    ): List<EligibleRepo> = newSuspendedTransaction(Dispatchers.IO) {
        val platformColumn = platformColumn(platform)
        val exposurePlatform = platform ?: "all"

        val sql = buildString {
            append(
                """
                SELECT r.id, r.full_name, r.owner, r.name, r.owner_avatar_url, r.description, r.default_branch,
                       r.html_url, r.stars, r.forks, r.open_issues, r.license_spdx_id, r.license_name,
                       r.language, r.topics,
                       r.latest_release_date, r.latest_release_tag, r.download_count,
                       r.has_installers_android, r.has_installers_windows,
                       r.has_installers_macos, r.has_installers_linux,
                       r.trending_score, r.popularity_score,
                       r.updated_at_gh, r.created_at_gh, r.pushed_at_gh,
                       s.star_velocity_ewma, s.dl_velocity_ewma,
                       CASE WHEN fe.last_shown_epochday < ? THEN fe.last_shown_epochday ELSE NULL END AS last_shown,
                       COALESCE(fe.shown_count, 0) AS shown_count
                FROM repos r
                LEFT JOIN LATERAL (
                    SELECT star_velocity_ewma, dl_velocity_ewma
                    FROM repo_daily_snapshot
                    WHERE repo_id = r.id AND star_velocity_ewma IS NOT NULL
                    ORDER BY snapshot_date DESC
                    LIMIT 1
                ) s ON true
                LEFT JOIN feed_exposure fe ON fe.repo_id = r.id AND fe.platform = ?
                """.trimIndent()
            )
            // Same gate as coverageStats — single source so the metric's
            // denominator can't drift from what actually ships. Carries the
            // make_interval(days => ?) placeholder for staleDays.
            append(" WHERE ").append(eligibleGateWhere(platformColumn))
            // Deterministic tiebreak: FeedRankScorer.rank sorts stably, so equal
            // keys keep this SQL order → byte-identical ranking per (platform, day).
            append(" ORDER BY r.id LIMIT ?")
        }

        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val results = mutableListOf<EligibleRepo>()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, todayEpochDay)
            stmt.setString(2, exposurePlatform)
            stmt.setInt(3, staleDays)
            stmt.setInt(4, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val lastShown = (rs.getObject("last_shown") as? Number)?.toInt()
                    results.add(
                        EligibleRepo(
                            repo = rs.toRepoResponse(),
                            starVelocityEwma = (rs.getObject("star_velocity_ewma") as? Number)?.toDouble(),
                            dlVelocityEwma = (rs.getObject("dl_velocity_ewma") as? Number)?.toDouble(),
                            daysSinceShown = lastShown?.let { todayEpochDay - it },
                            shownCount = rs.getInt("shown_count"),
                        )
                    )
                }
            }
        }
        results
    }

    /**
     * Records the repos actually PLACED in today's assembled feed for [platform]
     * (not the whole eligible set — diversity windows drop some, and recording a
     * never-shown candidate would mis-age its cooldown). Keyed on (repo_id,
     * platform) only — global, never per-user, so the CDN-shared anonymity
     * invariant holds.
     *
     * The `WHERE last_shown_epochday < EXCLUDED…` guard makes the write idempotent
     * across the 3h rebuild and a mid-day process restart: a repo already marked
     * shown today is skipped, so shown_count never inflates ~8×/day (brief §7).
     */
    suspend fun recordExposure(
        platform: String?,
        placedIds: List<Long>,
        todayEpochDay: Int,
    ): Int = newSuspendedTransaction(Dispatchers.IO) {
        if (placedIds.isEmpty()) return@newSuspendedTransaction 0
        val exposurePlatform = platform ?: "all"
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        var written = 0
        conn.prepareStatement(
            """
            INSERT INTO feed_exposure (repo_id, platform, last_shown_epochday, shown_count, last_position, updated_at)
            VALUES (?, ?, ?, 1, ?, NOW())
            ON CONFLICT (repo_id, platform) DO UPDATE
            SET last_shown_epochday = EXCLUDED.last_shown_epochday,
                shown_count         = feed_exposure.shown_count + 1,
                last_position       = EXCLUDED.last_position,
                updated_at          = NOW()
            WHERE feed_exposure.last_shown_epochday IS NULL
               OR feed_exposure.last_shown_epochday < EXCLUDED.last_shown_epochday
            """.trimIndent()
        ).use { ps ->
            placedIds.forEachIndexed { pos, id ->
                ps.setLong(1, id)
                ps.setString(2, exposurePlatform)
                ps.setInt(3, todayEpochDay)
                ps.setInt(4, pos)
                ps.addBatch()
                if ((pos + 1) % EXPOSURE_WRITE_CHUNK == 0) written += ps.executeBatch().sum()
            }
            written += ps.executeBatch().sum()
        }
        written
    }

    /**
     * Operator observability for feed-v2 rotation (brief §5, §10). Per platform,
     * every count is restricted to CURRENTLY-eligible repos (the same JOIN +
     * gate as the feed build) so the numerators stay consistent with
     * eligibleCount — a repo surfaced earlier but since archived/stale must not
     * inflate coverageRatio past 1.0:
     *  - coverageRatio   = currently-eligible repos surfaced in the last 14d / eligible set
     *  - surfacedOnce30d = eligible repos shown exactly once and not since (the
     *    "one-shot then vanished" failure the brief warns about)
     *  - eliteShareTop10 = concentration of total exposure among the 10 most-shown
     *    eligible repos active in the window. NOTE: shown_count is LIFETIME
     *    (feed_exposure tracks a cumulative counter, not per-day), so this is
     *    "share of lifetime shows among window-active repos" — a chronic-dominance
     *    signal, not a 14d-only one. High → a few repos hog the feed.
     */
    suspend fun coverageStats(platform: String?, todayEpochDay: Int): FeedCoverageStats =
        newSuspendedTransaction(Dispatchers.IO) {
            val exposurePlatform = platform ?: "all"
            val col = platformColumn(platform)
            val gate = eligibleGateWhere(col)
            val conn = TransactionManager.current().connection.connection as java.sql.Connection

            val eligibleCount = conn.prepareStatement(
                "SELECT COUNT(*) FROM repos r WHERE $gate"
            ).use { ps ->
                ps.setInt(1, FEED_GATE_STALE_DAYS)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }

            // All exposure reads JOIN repos + apply the gate so they count only
            // currently-eligible repos. The gate's single `?` (stale-days) binds
            // last, after each query's own feed_exposure predicates.
            val windowCutoff = todayEpochDay - COVERAGE_WINDOW_DAYS
            val distinctSurfaced14d = conn.prepareStatement(
                "SELECT COUNT(*) FROM feed_exposure fe JOIN repos r ON r.id = fe.repo_id " +
                    "WHERE fe.platform = ? AND fe.last_shown_epochday >= ? AND $gate"
            ).use { ps ->
                ps.setString(1, exposurePlatform); ps.setInt(2, windowCutoff); ps.setInt(3, FEED_GATE_STALE_DAYS)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }

            val surfacedOnce30d = conn.prepareStatement(
                // Shown exactly once ever, last shown inside the 30d window but
                // not today — the one-shot-then-vanish signal, eligible repos only.
                "SELECT COUNT(*) FROM feed_exposure fe JOIN repos r ON r.id = fe.repo_id " +
                    "WHERE fe.platform = ? AND fe.shown_count = 1 " +
                    "AND fe.last_shown_epochday BETWEEN ? AND ? AND $gate"
            ).use { ps ->
                ps.setString(1, exposurePlatform)
                ps.setInt(2, todayEpochDay - ONE_SHOT_WINDOW_DAYS)
                ps.setInt(3, todayEpochDay - 1)
                ps.setInt(4, FEED_GATE_STALE_DAYS)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }

            val totalShows = conn.prepareStatement(
                "SELECT COALESCE(SUM(fe.shown_count), 0) FROM feed_exposure fe JOIN repos r ON r.id = fe.repo_id " +
                    "WHERE fe.platform = ? AND fe.last_shown_epochday >= ? AND $gate"
            ).use { ps ->
                ps.setString(1, exposurePlatform); ps.setInt(2, windowCutoff); ps.setInt(3, FEED_GATE_STALE_DAYS)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
            val top10Shows = conn.prepareStatement(
                "SELECT COALESCE(SUM(shown_count), 0) FROM " +
                    "(SELECT fe.shown_count FROM feed_exposure fe JOIN repos r ON r.id = fe.repo_id " +
                    "WHERE fe.platform = ? AND fe.last_shown_epochday >= ? AND $gate " +
                    "ORDER BY fe.shown_count DESC LIMIT 10) t"
            ).use { ps ->
                ps.setString(1, exposurePlatform); ps.setInt(2, windowCutoff); ps.setInt(3, FEED_GATE_STALE_DAYS)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }

            FeedCoverageStats(
                platform = exposurePlatform,
                eligibleCount = eligibleCount,
                distinctSurfaced14d = distinctSurfaced14d,
                coverageRatio = if (eligibleCount > 0) distinctSurfaced14d.toDouble() / eligibleCount else 0.0,
                surfacedOnce30d = surfacedOnce30d,
                eliteShareTop10 = if (totalShows > 0) top10Shows.toDouble() / totalShows else 0.0,
            )
        }

    private fun platformColumn(platform: String?): String? = when (platform) {
        "android" -> "has_installers_android"
        "windows" -> "has_installers_windows"
        "macos" -> "has_installers_macos"
        "linux" -> "has_installers_linux"
        else -> null
    }

    // Shared eligibility gate (brief §4a). One `?` for the stale-window days.
    // Both eligiblePool (feed build) and coverageStats (the metric denominator)
    // build their WHERE from this so the two can never disagree.
    private fun eligibleGateWhere(platformColumn: String?): String = buildString {
        append("r.archived = FALSE")
        append(" AND (r.stars > 0 OR r.download_count > 0)")
        append(" AND r.latest_release_date IS NOT NULL")
        append(" AND r.latest_release_date > NOW() - make_interval(days => ?)")
        if (platformColumn != null) append(" AND r.$platformColumn = true")
    }

    private suspend fun poolQuery(
        where: String,
        orderBy: String,
        platform: String?,
        limit: Int,
    ): List<RepoResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val platformColumn = when (platform) {
            "android" -> "has_installers_android"
            "windows" -> "has_installers_windows"
            "macos" -> "has_installers_macos"
            "linux" -> "has_installers_linux"
            else -> null
        }

        val sql = buildString {
            append(
                """
                SELECT id, full_name, owner, name, owner_avatar_url, description, default_branch,
                       html_url, stars, forks, open_issues, license_spdx_id, license_name,
                       language, topics,
                       latest_release_date, latest_release_tag, download_count,
                       has_installers_android, has_installers_windows,
                       has_installers_macos, has_installers_linux,
                       trending_score, popularity_score, search_score,
                       updated_at_gh, created_at_gh, pushed_at_gh
                FROM repos
                """.trimIndent()
            )
            // trimIndent strips the trailing newline, so every fragment below
            // must carry its own leading space — gluing WHERE onto the next
            // token produced `WHEREtrending_score` and a Postgres syntax error
            // in the first deployed build.
            append(" WHERE ").append(where)
            if (platformColumn != null) append(" AND $platformColumn = true")
            append(" ORDER BY ").append(orderBy).append(" LIMIT ?")
        }

        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val results = mutableListOf<RepoResponse>()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) results.add(rs.toRepoResponse())
            }
        }
        results
    }

    private fun ResultSet.toRepoResponse(): RepoResponse {
        val releaseDateStr = getString("latest_release_date")
        val recencyDays = releaseDateStr?.let {
            try {
                val releaseDate = OffsetDateTime.parse(it)
                ChronoUnit.DAYS.between(releaseDate.toInstant(), OffsetDateTime.now().toInstant())
                    .toInt().coerceAtLeast(0)
            } catch (_: Exception) { null }
        }
        @Suppress("UNCHECKED_CAST")
        val topics: List<String> = (getArray("topics")?.array as? Array<Any?>)
            ?.filterIsInstance<String>() ?: emptyList()

        return RepoResponse(
            id = getLong("id"),
            name = getString("name"),
            fullName = getString("full_name"),
            owner = RepoOwner(login = getString("owner"), avatarUrl = getString("owner_avatar_url")),
            description = getString("description"),
            defaultBranch = getString("default_branch"),
            htmlUrl = getString("html_url"),
            stargazersCount = getInt("stars"),
            forksCount = getInt("forks"),
            openIssuesCount = getInt("open_issues"),
            licenseSpdxId = getString("license_spdx_id"),
            licenseName = getString("license_name"),
            license = nestedLicense(getString("license_spdx_id"), getString("license_name")),
            language = getString("language"),
            topics = topics,
            topicCodes = TopicCodeMapper.resolve(topics),
            releasesUrl = "${getString("html_url")}/releases",
            updatedAt = getString("updated_at_gh"),
            createdAt = getString("created_at_gh"),
            pushedAt = getString("pushed_at_gh"),
            latestReleaseDate = releaseDateStr,
            latestReleaseTag = getString("latest_release_tag"),
            releaseRecency = recencyDays,
            releaseRecencyText = recencyDays?.let { formatRecency(it) },
            downloadCount = getLong("download_count"),
            hasInstallersAndroid = getBoolean("has_installers_android"),
            hasInstallersWindows = getBoolean("has_installers_windows"),
            hasInstallersMacos = getBoolean("has_installers_macos"),
            hasInstallersLinux = getBoolean("has_installers_linux"),
            // Columns are REAL (FLOAT4) — JDBC materialises java.lang.Float,
            // so a safe-cast to Double silently nulls every value. Go through
            // Number to survive both Float and any future DOUBLE PRECISION
            // migration.
            trendingScore = (getObject("trending_score") as? Number)?.toDouble(),
            popularityScore = (getObject("popularity_score") as? Number)?.toDouble(),
        )
    }
}

// One eligible repo plus the off-request-path scoring inputs FeedRankScorer needs.
// stars/downloads/daysSinceRelease are read from [repo] (already mapped); velocity
// and cooldown state ride alongside.
data class EligibleRepo(
    val repo: RepoResponse,
    val starVelocityEwma: Double?,
    val dlVelocityEwma: Double?,
    val daysSinceShown: Int?,
    val shownCount: Int,
)

// Quality-gate stale window: a repo with no release in this many days is dropped
// from the feed regardless of stars. Brief §10 proposes 365d; env-overridable.
// `get()` (no backing field) so an env change takes effect on the next daily
// build without a redeploy — same instant-override semantics as
// FeatureFlags.feedV2Ranking.
val FEED_GATE_STALE_DAYS: Int
    get() = System.getenv("FEED_GATE_STALE_DAYS")?.toIntOrNull()?.takeIf { it >= 1 } ?: 365

// Upper bound on the eligible set fetched per build. Must stay ≥ E (the ~1–3k
// genuinely-good pool) or cooldown can't reach the tail (brief §4d). 5000 covers
// the estimate with headroom while bounding a pathological full-catalog scan.
val FEED_ELIGIBLE_LIMIT: Int
    get() = System.getenv("FEED_ELIGIBLE_LIMIT")?.toIntOrNull()?.takeIf { it >= 1 } ?: 5_000

private const val EXPOSURE_WRITE_CHUNK = 1_000

// Per-platform feed-v2 rotation health, surfaced on /internal/metrics.
@Serializable
data class FeedCoverageStats(
    val platform: String,
    val eligibleCount: Long,
    val distinctSurfaced14d: Long,
    val coverageRatio: Double,
    val surfacedOnce30d: Long,
    val eliteShareTop10: Double,
)

// Trailing window for the coverage ratio (distinct surfaced / eligible).
private const val COVERAGE_WINDOW_DAYS = 14
// Trailing window for the one-shot-then-vanish count.
private const val ONE_SHOT_WINDOW_DAYS = 30
