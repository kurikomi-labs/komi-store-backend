package zed.rainxch.githubstore.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.util.formatRecency
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class RepoRepository {

    suspend fun findByOwnerAndName(owner: String, name: String): RepoResponse? = newSuspendedTransaction(Dispatchers.IO) {
        Repos.selectAll()
            .where { (Repos.owner eq owner) and (Repos.name eq name) }
            .firstOrNull()
            ?.toRepoResponse()
    }

    suspend fun findByCategory(category: String, platform: String, limit: Int = 50): List<RepoResponse> = newSuspendedTransaction(Dispatchers.IO) {
        // Primary: dynamic behavioral search_score (updated hourly by
        // SignalAggregationWorker from clicks / installs / stars / freshness).
        // Tie-breaker: the static rank the Python fetcher writes once a day,
        // which preserves the category's semantic flavor (trending stays
        // velocity-flavored, new-releases stays recency-flavored, etc.) when
        // two repos have similar behavioral scores.
        Repos.innerJoin(RepoCategories, { id }, { repoId })
            .selectAll()
            .where {
                (RepoCategories.category eq category) and (RepoCategories.platform eq platform)
            }
            .orderBy(
                Repos.searchScore to SortOrder.DESC_NULLS_LAST,
                RepoCategories.rank to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toRepoResponse(category = category) }
    }

    suspend fun findByTopicBucket(bucket: String, platform: String, limit: Int = 50): List<RepoResponse> = newSuspendedTransaction(Dispatchers.IO) {
        Repos.innerJoin(RepoTopicBuckets, { id }, { repoId })
            .selectAll()
            .where {
                (RepoTopicBuckets.bucket eq bucket) and (RepoTopicBuckets.platform eq platform)
            }
            .orderBy(
                Repos.searchScore to SortOrder.DESC_NULLS_LAST,
                RepoTopicBuckets.rank to SortOrder.ASC,
            )
            .limit(limit)
            .map { it.toRepoResponse() }
    }

    private fun ResultRow.toRepoResponse(category: String? = null): RepoResponse {
        val releaseDateStr = this[Repos.latestReleaseDate]?.toString()
        val releaseDate = this[Repos.latestReleaseDate]
        val recencyDays = releaseDate?.let {
            ChronoUnit.DAYS.between(it.toInstant(), OffsetDateTime.now().toInstant()).toInt().coerceAtLeast(0)
        }

        return RepoResponse(
            id = this[Repos.id],
            name = this[Repos.name],
            fullName = this[Repos.fullName],
            owner = RepoOwner(
                login = this[Repos.owner],
                avatarUrl = this[Repos.ownerAvatarUrl],
            ),
            description = this[Repos.description],
            defaultBranch = this[Repos.defaultBranch],
            htmlUrl = this[Repos.htmlUrl],
            stargazersCount = this[Repos.stars],
            forksCount = this[Repos.forks],
            openIssuesCount = this[Repos.openIssues],
            language = this[Repos.language],
            topics = this[Repos.topics],
            releasesUrl = "${this[Repos.htmlUrl]}/releases",
            updatedAt = this[Repos.updatedAtGh]?.toString(),
            createdAt = this[Repos.createdAtGh]?.toString(),
            latestReleaseDate = releaseDateStr,
            latestReleaseTag = this[Repos.latestReleaseTag],
            releaseRecency = recencyDays,
            releaseRecencyText = recencyDays?.let { formatRecency(it) },
            downloadCount = this[Repos.downloadCount],
            trendingScore = if (category == "trending") this[Repos.trendingScore]?.toDouble() else null,
            popularityScore = if (category == "most-popular") this[Repos.popularityScore]?.toDouble() else null,
            hasInstallersAndroid = this[Repos.hasInstallersAndroid],
            hasInstallersWindows = this[Repos.hasInstallersWindows],
            hasInstallersMacos = this[Repos.hasInstallersMacos],
            hasInstallersLinux = this[Repos.hasInstallersLinux],
        )
    }

}
