package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.feed.FeedService
import zed.rainxch.githubstore.model.FeedResponse

private val VALID_FEED_PLATFORMS = setOf("android", "windows", "macos", "linux")
private const val MAX_PAGE = 25

// Anonymous discovery feed (feed brief v1). No auth, no X-GitHub-Token, no
// per-user state — the response is identical for every caller on the same
// (platform, page, day), which is what makes the long shared edge cache
// correct. Client-side personalization happens on-device against this
// shared candidate stream; the server never learns who is reading.
fun Route.feedRoutes(feedService: FeedService) {
    get("/feed") {
        val platform = call.request.queryParameters["platform"]
        if (platform != null && platform !in VALID_FEED_PLATFORMS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid platform. Must be one of: $VALID_FEED_PLATFORMS"),
            )
        }

        val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_PAGE)
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)

        val result = feedService.page(platform, page, limit)

        // max-age 900 serves browsers/proxies; the app ignores it (no
        // HttpCache plugin client-side) and keys its own CacheManager on the
        // rotation tag. s-maxage 3600 lets the CDN absorb the whole day's
        // traffic on ~25 pages × 5 platform variants.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=900, s-maxage=3600")
        call.respond(
            FeedResponse(
                items = result.items,
                page = result.page,
                hasMore = result.hasMore,
                generatedAt = result.generatedAt,
                rotation = result.rotation,
            ),
        )
    }
}
