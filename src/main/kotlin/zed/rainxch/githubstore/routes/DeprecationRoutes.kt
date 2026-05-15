package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Old client builds (pre-1.6) wired the device-flow URLs under `/repo/` by
// mistake. Cloudflare analytics show ~110 hits/week to `/v1/repo/login/device`
// and `/v1/repo/login/oauth` returning 404 — they fall through the generic
// `/repo/{owner}/{name}` route into a GitHub lookup that legitimately 404s.
// Replace with a 410 Gone tombstone so old clients get a real signal and a
// pointer to the correct path, and so CF can cache the response and stop
// hammering origin.
//
// Note: declared BEFORE `repoRoutes` in the routing block so the static
// segments win over the parameterized `/repo/{owner}/{name}` route.
@Serializable
private data class DeprecatedAuthNotice(
    val error: String = "endpoint_deprecated",
    val message: String = "This path was used by pre-1.6 builds. Use POST /v1/auth/device/start and POST /v1/auth/device/poll for the device-flow.",
    val deprecated_at: String = "2024-09-01",
    val use_instead: List<String> = listOf(
        "/v1/auth/device/start",
        "/v1/auth/device/poll",
    ),
)

private val NOTICE = DeprecatedAuthNotice()
private const val GONE_CACHE_CONTROL = "public, max-age=86400"

fun Route.deprecationRoutes() {
    listOf("/repo/login/device", "/repo/login/oauth").forEach { path ->
        get(path) {
            call.response.header(HttpHeaders.CacheControl, GONE_CACHE_CONTROL)
            call.respond(HttpStatusCode.Gone, NOTICE)
        }
        post(path) {
            call.response.header(HttpHeaders.CacheControl, GONE_CACHE_CONTROL)
            call.respond(HttpStatusCode.Gone, NOTICE)
        }
    }
}
