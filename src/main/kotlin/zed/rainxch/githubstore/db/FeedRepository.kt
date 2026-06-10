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
                WHERE
                """.trimIndent()
            )
            append(where)
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
