package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Telemetry was killed in the 2026-04 audit. Endpoint kept as a 410 Gone
// stub so older clients see a clear deprecation signal instead of dribbling
// 400s into the origin logs. Cache-Control lets Cloudflare/clients hold the
// response so retries don't reach origin once it's seen.
private const val DEPRECATED_AT = "2026-04-26"
private const val SEE_URL = "https://github-store.org/blog/why-we-killed-telemetry"

@Serializable
private data class DeprecationNotice(
    val error: String,
    val message: String,
    val deprecated_at: String,
    val see: String,
)

private val EVENTS_GONE_NOTICE = DeprecationNotice(
    error = "endpoint_deprecated",
    message = "Telemetry endpoint removed. Update GitHub Store to 1.8.0 or newer.",
    deprecated_at = DEPRECATED_AT,
    see = SEE_URL,
)

fun Route.eventRoutes() {
    post("/events") {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respond(HttpStatusCode.Gone, EVENTS_GONE_NOTICE)
    }
}
