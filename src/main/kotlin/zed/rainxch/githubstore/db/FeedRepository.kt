package zed.rainxch.githubstore.db

import kotlinx.coroutines.Dispatchers
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
        val platformColumn = when (platform) {
            "android" -> "has_installers_android"
            "windows" -> "has_installers_windows"
            "macos" -> "has_installers_macos"
            "linux" -> "has_installers_linux"
            else -> null
        }
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
                WHERE r.archived = FALSE
                  AND (r.stars > 0 OR r.download_count > 0)
                  AND r.latest_release_date IS NOT NULL
                  AND r.latest_release_date > NOW() - make_interval(days => ?)
                """.trimIndent()
            )
            if (platformColumn != null) append(" AND r.$platformColumn = true")
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
