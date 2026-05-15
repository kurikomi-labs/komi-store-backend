package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.badge.BadgeService
import zed.rainxch.githubstore.badge.BadgeVariant
import zed.rainxch.githubstore.util.GitHubIdentifiers

private val SVG_CONTENT_TYPE = ContentType.parse("image/svg+xml; charset=utf-8")
private const val FRESH_CACHE = "public, max-age=3600, s-maxage=3600, stale-while-revalidate=86400"
private const val DEGRADED_CACHE = "public, max-age=300, s-maxage=300"

// URL convention:
//   Per-repo:  /v1/badge/{owner}/{name}/{kind}/{style}/{variant}
//              kind ∈ {release, stars, downloads}
//   Global:    /v1/badge/{kind}/{style}/{variant}
//              kind ∈ {users, fdroid}
//   Static:    /v1/badge/static/{style}/{variant}?label=...&icon=...
//
// {style} 1..12 selects a hue (red..neutral). {variant} 1..3 selects a shade
// (dark / medium / light). Mirrors ziadOUA/m3-Markdown-Badges's color model.
fun Route.badgeRoutes(badgeService: BadgeService) {
    route("/badge") {

        // Static (custom label) — declared first so it doesn't get shadowed
        // by the global-kind route.
        get("/static/{style}/{variant}") {
            val (styleIndex, variant) = parseStyleAndVariant(
                call.parameters["style"], call.parameters["variant"],
            ) ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "style must be 1-12, variant must be 1-3"))

            val label = call.request.queryParameters["label"]?.takeIf { it.isNotBlank() }
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "label query parameter required"))
            if (label.length > 100) return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "label_too_long"))
            val icon = call.request.queryParameters["icon"]
            val height = parseHeight(call.request.queryParameters["height"])

            val svg = badgeService.renderStatic(label, icon, styleIndex, variant, height)
            respondSvg(svg, degraded = false)
        }

        // Per-repo: /v1/badge/{owner}/{name}/{kind}/{style}/{variant}
        get("/{owner}/{name}/{kind}/{style}/{variant}") {
            val owner = GitHubIdentifiers.validOwner(call.parameters["owner"])
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_owner"))
            val name = GitHubIdentifiers.validName(call.parameters["name"])
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_name"))
            val kind = call.parameters["kind"]!!

            val (styleIndex, variant) = parseStyleAndVariant(
                call.parameters["style"], call.parameters["variant"],
            ) ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "style must be 1-12, variant must be 1-3"))

            val labelOverride = call.request.queryParameters["label"]
            val height = parseHeight(call.request.queryParameters["height"])

            val rendered = badgeService.renderRepoBadge(owner, name, kind, styleIndex, variant, labelOverride, height)
                // `kind` is the URL path segment; an unknown value is an
                // input-validation error, not a missing resource. 400 also
                // sidesteps the global StatusPages NotFound handler that
                // would otherwise overwrite this diagnostic body with the
                // generic `not_found` envelope (see StatusPagesOverrideTest).
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown kind: $kind"))

            respondSvg(rendered.svg, degraded = rendered.degraded)
        }

        // Global: /v1/badge/{kind}/{style}/{variant} for non-repo-specific badges (users, fdroid).
        get("/{kind}/{style}/{variant}") {
            val kind = call.parameters["kind"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (kind == "static") {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "use /badge/static/{style}/{variant}?label=..."))
            }

            val (styleIndex, variant) = parseStyleAndVariant(
                call.parameters["style"], call.parameters["variant"],
            ) ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "style must be 1-12, variant must be 1-3"))

            val labelOverride = call.request.queryParameters["label"]
            val height = parseHeight(call.request.queryParameters["height"])

            val rendered = badgeService.renderGlobalBadge(kind, styleIndex, variant, labelOverride, height)
                // Same rationale as the per-repo route above: `kind` is
                // input, not a missing resource; 400 keeps the diagnostic
                // body intact against the global NotFound handler.
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown kind: $kind (global kinds are users, fdroid; use /v1/badge/{owner}/{name}/{kind}/... for per-repo)"))

            respondSvg(rendered.svg, degraded = rendered.degraded)
        }
    }
}

private fun parseStyleAndVariant(style: String?, variant: String?): Pair<Int, BadgeVariant>? {
    val s = style?.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val v = variant?.toIntOrNull()?.let { BadgeVariant.fromIndex(it) } ?: return null
    return s to v
}

private fun parseHeight(raw: String?): Int =
    raw?.toIntOrNull()?.coerceIn(24, 60) ?: 30

private suspend fun io.ktor.server.routing.RoutingContext.respondSvg(svg: String, degraded: Boolean) {
    call.response.header(HttpHeaders.CacheControl, if (degraded) DEGRADED_CACHE else FRESH_CACHE)
    val etag = "\"" + Integer.toHexString(svg.hashCode()).padStart(8, '0') + "\""
    call.response.header(HttpHeaders.ETag, etag)
    call.response.header("Vary", "Accept-Encoding")

    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
    if (ifNoneMatch != null && ifNoneMatch == etag) {
        call.respond(HttpStatusCode.NotModified)
        return
    }

    val status = if (degraded) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
    call.respondText(svg, SVG_CONTENT_TYPE, status)
}
