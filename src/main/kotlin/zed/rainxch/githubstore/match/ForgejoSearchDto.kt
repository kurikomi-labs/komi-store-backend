package zed.rainxch.githubstore.match

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Minimal Forgejo / Gitea `/api/v1/repos/search` response shape — only the
// fields we actually use for external-match scoring. `ignoreUnknownKeys`
// drops everything else so Forgejo schema additions don't break us.
//
// Field naming follows the live Codeberg payload (forgejo 15.0.0-108):
//   - `stars_count`        — counterpart of GitHub `stargazers_count`
//   - `forks_count`        — same on both
//   - `open_issues_count`  — same on both
//   - `owner.login`        — same on both, though Forgejo populates extra
//                            fields we ignore
@Serializable
internal data class ForgejoSearchResults(
    val data: List<ForgejoRepo> = emptyList(),
)

@Serializable
internal data class ForgejoRepo(
    val owner: ForgejoUser,
    val name: String,
    @SerialName("full_name") val fullName: String? = null,
    val description: String? = null,
    @SerialName("stars_count") val starsCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    @SerialName("open_issues_count") val openIssuesCount: Int = 0,
    @SerialName("has_releases") val hasReleases: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
internal data class ForgejoUser(
    val login: String,
)
