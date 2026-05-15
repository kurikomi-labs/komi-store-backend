package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import zed.rainxch.githubstore.match.SigningFingerprintRepository
import zed.rainxch.githubstore.match.SigningSeedsResponse
import zed.rainxch.githubstore.util.ApiError
import java.security.MessageDigest

private const val DEFAULT_PAGE_SIZE = 1000
private const val MAX_PAGE_SIZE = 5000

// Cache budget: signing seeds change only on the daily F-Droid sync cron, and
// the operator purges the Cloudflare cache when that lands. Until then the
// content is byte-stable for the same (since, cursor, limit) inputs, so push
// a long edge TTL plus a strong ETag for conditional revalidation.
//
//   max-age=86400        — clients hold for 1 day
//   s-maxage=604800      — Cloudflare holds for 7 days (purged on seed update)
//   stale-while-revalidate=86400
//                        — serve stale up to 1 day while the edge re-fetches
private const val SIGNING_SEEDS_CACHE_CONTROL =
    "public, max-age=86400, s-maxage=604800, stale-while-revalidate=86400"

private val EtagJson = Json { encodeDefaults = true }

// Per E1_BACKEND_HANDOFF.md:
//   GET /v1/signing-seeds?since=<epoch_ms>&platform=android&cursor=<opaque>
//
// Anonymous, paginated. observedAt is epoch milliseconds -- never seconds.
// `platform` is required and currently only `android` is meaningful for E1.
fun Route.signingSeedsRoutes(repository: SigningFingerprintRepository) {
    get("/signing-seeds") {
        val platform = call.request.queryParameters["platform"]
        if (platform != "android") {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("invalid_platform", message = "platform query parameter required; android is the only supported value for now"),
            )
            return@get
        }

        val since = call.request.queryParameters["since"]?.let { raw ->
            raw.toLongOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_since", message = "since must be epoch milliseconds"))
                return@get
            }
        }
        if (since != null && since < 0) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_since", message = "since must be non-negative"))
            return@get
        }

        val cursor = call.request.queryParameters["cursor"]?.let { token ->
            SigningFingerprintRepository.PageCursor.decode(token) ?: run {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_cursor"))
                return@get
            }
        }

        val pageSize = call.request.queryParameters["limit"]?.toIntOrNull()
            ?.coerceIn(1, MAX_PAGE_SIZE)
            ?: DEFAULT_PAGE_SIZE

        val page = repository.page(sinceMs = since, cursor = cursor, limit = pageSize)
        val response = SigningSeedsResponse(
            rows = page.rows,
            nextCursor = page.nextCursor?.encode(),
        )

        val etag = etagOf(response)
        call.response.header(HttpHeaders.CacheControl, SIGNING_SEEDS_CACHE_CONTROL)
        call.response.header(HttpHeaders.ETag, etag)

        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]?.trim()
        if (ifNoneMatch != null && etagsMatch(ifNoneMatch, etag)) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }

        call.respond(response)
    }
}

// Strong ETag over the canonical JSON of the response. Same (since, cursor,
// limit) inputs produce the same rows + nextCursor, so identical bytes ->
// identical tag. New rows arriving for the same `since` produce a fresh tag,
// which is correct: the response body really did change.
private fun etagOf(response: SigningSeedsResponse): String {
    val canonical = EtagJson.encodeToString(SigningSeedsResponse.serializer(), response)
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(canonical.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "\"${hex.take(16)}\""
}

// Lenient match: clients (and CDNs) sometimes send the weak prefix `W/` or a
// list of comma-separated tags. Accept either an exact match against ours or
// any token in a comma-separated list. We never emit weak ETags ourselves, so
// strip the `W/` prefix on the incoming side before comparing.
private fun etagsMatch(header: String, ours: String): Boolean {
    if (header == "*") return true
    return header.split(",")
        .map { it.trim().removePrefix("W/") }
        .any { it == ours }
}
