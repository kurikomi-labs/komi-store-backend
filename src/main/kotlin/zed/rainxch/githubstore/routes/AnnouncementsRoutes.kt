package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.announcements.AnnouncementsRegistry

// GET /v1/announcements -- public, no auth, no rate-limit bucket beyond the
// global 120/min/IP. Returns the same envelope to every caller (no per-user
// logic, no per-IP variation). max-age=600 keeps each browser fresh on its
// 10-minute cold-start cycle; s-maxage=3600 lets the CDN hold one hour so an
// edit doesn't slam origin from every POP at once. New items still surface
// within the hour (announcements are not real-time anyway).
//
// ETag handling: hashes the active item set (post expiresAt filter), so
// `If-None-Match` revalidations are 304s when nothing has expired since the
// last fetch. fetchedAt is intentionally NOT folded into the hash -- otherwise
// every minute would mint a new tag for identical content.
fun Route.announcementsRoutes(registry: AnnouncementsRegistry) {
    get("/announcements") {
        val served = registry.serve()
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]?.trim()

        call.response.header(
            HttpHeaders.CacheControl,
            "public, max-age=600, s-maxage=3600",
        )
        call.response.header(HttpHeaders.ETag, served.etag)

        if (ifNoneMatch != null && etagsMatch(ifNoneMatch, served.etag)) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }

        call.respond(served.response)
    }
}

// Lenient match: clients (and CDNs) sometimes send the weak prefix `W/` or a
// list of comma-separated tags. Accept either an exact match against ours or
// any token in a comma-separated list. We never emit weak ETags ourselves, so
// we strip the `W/` prefix on the incoming side before comparing.
private fun etagsMatch(header: String, ours: String): Boolean {
    if (header == "*") return true
    return header.split(",")
        .map { it.trim().removePrefix("W/") }
        .any { it == ours }
}
