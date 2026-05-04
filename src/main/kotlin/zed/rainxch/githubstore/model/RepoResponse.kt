package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class RepoOwner(
    val login: String,
    val avatarUrl: String?,
)

@Serializable
data class RepoResponse(
    val id: Long,
    val name: String,
    val fullName: String,
    val owner: RepoOwner,
    val description: String?,
    val defaultBranch: String?,
    val htmlUrl: String,
    val stargazersCount: Int,
    val forksCount: Int,
    // Mirrors GitHub's open_issues_count -- includes both open issues AND
    // open PRs (GitHub treats PRs as a kind of issue). Same value as the
    // GitHub website's Issues tab badge.
    val openIssuesCount: Int = 0,
    val language: String?,
    val topics: List<String>,
    val releasesUrl: String?,
    val updatedAt: String?,
    val createdAt: String?,
    val latestReleaseDate: String? = null,
    val latestReleaseTag: String? = null,
    val releaseRecency: Int? = null,
    val releaseRecencyText: String? = null,
    val downloadCount: Long = 0,
    val trendingScore: Double? = null,
    val popularityScore: Double? = null,
    val hasInstallersAndroid: Boolean = false,
    val hasInstallersWindows: Boolean = false,
    val hasInstallersMacos: Boolean = false,
    val hasInstallersLinux: Boolean = false,
)
