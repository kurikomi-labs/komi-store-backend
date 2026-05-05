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
        // Primary sort is category-specific: trending velocity for the
        // trending list, absolute popularity for the popular list, release
        // recency for new-releases. Without category-specific primary, both
        // trending and most-popular collapse onto the same global
        // search_score and return ~99% identical top-N results -- the bug
        // this query previously had.
        //
        // Each category falls back to the global behavioral search_score
        // when its category-specific column is NULL, then to the static
        // rank the Python fetcher writes once a day. The fetcher populates
        // the category-specific scores for repos in that category, so the
        // fallback is mostly a no-op except for newly-ingested rows that
        // haven't been reranked yet.
        val primary: org.jetbrains.exposed.sql.Expression<*> = when (category) {
            "trending" -> Repos.trendingScore
            "most-popular" -> Repos.popularityScore
            "new-releases" -> Repos.latestReleaseDate
            else -> Repos.searchScore
        }
        Repos.innerJoin(RepoCategories, { id }, { repoId })
            .selectAll()
            .where {
                (RepoCategories.category eq category) and (RepoCategories.platform eq platform)
            }
            .orderBy(
                primary to SortOrder.DESC_NULLS_LAST,
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
            licenseSpdxId = this[Repos.licenseSpdxId],
            licenseName = this[Repos.licenseName],
            license = nestedLicense(this[Repos.licenseSpdxId], this[Repos.licenseName]),
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

// Builds the nested RepoLicense from the flat columns. Returns null when
// both inputs are null so the JSON field is `"license": null` rather than
// `"license": {"spdxId": null, "name": null}` for licenseless repos.
internal fun nestedLicense(spdxId: String?, name: String?): zed.rainxch.githubstore.model.RepoLicense? =
    if (spdxId == null && name == null) null
    else zed.rainxch.githubstore.model.RepoLicense(spdxId = spdxId, name = name)
