package zed.rainxch.githubstore.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.util.formatRecency
import java.sql.Array as SqlArray
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class SearchRepository {

    suspend fun search(
        query: String,
        platform: String? = null,
        sort: String = "relevance",
        limit: Int = 20,
        offset: Int = 0,
    ): List<RepoResponse> = newSuspendedTransaction(Dispatchers.IO) {
        val platformColumn = when (platform) {
            "android" -> "has_installers_android"
            "windows" -> "has_installers_windows"
            "macos" -> "has_installers_macos"
            "linux" -> "has_installers_linux"
            else -> null
        }

        // search_score tie-breaker on every path so behavioral signal still
        // disambiguates within the primary sort's equivalence class.
        // `recent` and `releases` are aliases for "by release date" -- the
        // newer name aligns with the GET /v1/search?sort=releases option
        // exposed to clients (matches user intent: stable releases first).
        // `updated` mirrors GitHub's repo-level `updated_at` (any push,
        // not necessarily a release).
        val orderClause = when (sort) {
            "stars" -> "ORDER BY stars DESC, search_score DESC NULLS LAST"
            "recent", "releases" -> "ORDER BY latest_release_date DESC NULLS LAST, search_score DESC NULLS LAST"
            "updated" -> "ORDER BY updated_at_gh DESC NULLS LAST, search_score DESC NULLS LAST"
            else -> "ORDER BY ts_rank(tsv_search, plainto_tsquery('english', ?)) DESC, search_score DESC NULLS LAST"
        }
        // Browse mode: empty query + non-relevance sort skips the ts_match
        // filter entirely. Clients use this for "no search box, just sort
        // the catalog" UX (Recently-Updated / Recent-Releases home tabs).
        val browseMode = query.isBlank() && sort != "relevance"

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
                       updated_at_gh, created_at_gh
                FROM repos
                """.trimIndent()
            )
            // WHERE clause skipped in browse mode -- caller wants the whole
            // catalog sorted by `sort`, not a text-matched subset.
            if (!browseMode) append(" WHERE tsv_search @@ plainto_tsquery('english', ?)")
            if (platformColumn != null) {
                append(if (browseMode) " WHERE " else " AND ").append("$platformColumn = true")
            }
            append(" ").append(orderClause).append(" LIMIT ? OFFSET ?")
        }

        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val results = mutableListOf<RepoResponse>()

        conn.prepareStatement(sql).use { stmt ->
            var i = 1
            if (!browseMode) stmt.setString(i++, query)
            if (sort == "relevance") stmt.setString(i++, query) // ts_rank in ORDER BY
            stmt.setInt(i++, limit)
            stmt.setInt(i, offset)

            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val releaseDateStr = rs.getString("latest_release_date")
                    val recencyDays = releaseDateStr?.let {
                        try {
                            val releaseDate = OffsetDateTime.parse(it)
                            ChronoUnit.DAYS.between(releaseDate.toInstant(), OffsetDateTime.now().toInstant())
                                .toInt().coerceAtLeast(0)
                        } catch (_: Exception) { null }
                    }

                    val topicsArray = rs.getArray("topics")
                    val topics: List<String> = topicsArray?.extractStringList() ?: emptyList()

                    results.add(
                        RepoResponse(
                            id = rs.getLong("id"),
                            name = rs.getString("name"),
                            fullName = rs.getString("full_name"),
                            owner = RepoOwner(
                                login = rs.getString("owner"),
                                avatarUrl = rs.getString("owner_avatar_url"),
                            ),
                            description = rs.getString("description"),
                            defaultBranch = rs.getString("default_branch"),
                            htmlUrl = rs.getString("html_url"),
                            stargazersCount = rs.getInt("stars"),
                            forksCount = rs.getInt("forks"),
                            openIssuesCount = rs.getInt("open_issues"),
                            licenseSpdxId = rs.getString("license_spdx_id"),
                            licenseName = rs.getString("license_name"),
                            license = nestedLicense(rs.getString("license_spdx_id"), rs.getString("license_name")),
                            language = rs.getString("language"),
                            topics = topics,
                            releasesUrl = "${rs.getString("html_url")}/releases",
                            updatedAt = rs.getString("updated_at_gh"),
                            createdAt = rs.getString("created_at_gh"),
                            latestReleaseDate = releaseDateStr,
                            latestReleaseTag = rs.getString("latest_release_tag"),
                            releaseRecency = recencyDays,
                            releaseRecencyText = recencyDays?.let { formatRecency(it) },
                            downloadCount = rs.getLong("download_count"),
                            hasInstallersAndroid = rs.getBoolean("has_installers_android"),
                            hasInstallersWindows = rs.getBoolean("has_installers_windows"),
                            hasInstallersMacos = rs.getBoolean("has_installers_macos"),
                            hasInstallersLinux = rs.getBoolean("has_installers_linux"),
                            trendingScore = rs.getObject("trending_score") as? Double,
                            popularityScore = rs.getObject("popularity_score") as? Double,
                        )
                    )
                }
            }
        }

        results
    }

    @Suppress("UNCHECKED_CAST")
    private fun SqlArray.extractStringList(): List<String> {
        val arr = this.array as? Array<Any?> ?: return emptyList()
        return arr.filterIsInstance<String>()
    }

}
