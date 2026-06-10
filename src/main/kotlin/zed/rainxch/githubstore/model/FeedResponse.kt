package zed.rainxch.githubstore.model

import kotlinx.serialization.Serializable

@Serializable
data class FeedResponse(
    val items: List<RepoResponse>,
    val page: Int,
    val hasMore: Boolean,
    // ISO instant of when this day's feed was assembled on the server.
    val generatedAt: String,
    // Daily rotation tag (UTC date). Clients include it in their cache key
    // so a cached page never crosses a rotation boundary; the value itself
    // is opaque to the client.
    val rotation: String,
)
