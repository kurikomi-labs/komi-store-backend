package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import zed.rainxch.githubstore.match.ExternalMatchEntry
import zed.rainxch.githubstore.match.ExternalMatchRequest
import zed.rainxch.githubstore.match.ExternalMatchResponse
import zed.rainxch.githubstore.match.ExternalMatchService
import zed.rainxch.githubstore.match.ForgejoSearchClient
import zed.rainxch.githubstore.requireMaxBody
import zed.rainxch.githubstore.util.ApiError

private const val EXTERNAL_MATCH_MAX_BODY = 256L * 1024
private const val MAX_CANDIDATES_PER_REQUEST = 25
private const val MAX_SOURCES_PER_REQUEST = 8
private val PACKAGE_NAME_RE = Regex("^[\\w.-]{1,255}$")
private val OWNER_RE = Regex("^[\\w.-]{1,39}$")
private val REPO_RE = Regex("^[\\w.-]{1,100}$")
private val FINGERPRINT_RE = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")
private const val MAX_APP_LABEL_LEN = 200
private val INSTALLER_KINDS = setOf(
    "obtainium", "fdroid", "play", "aurora", "galaxy",
    "oem_other", "browser", "sideload", "system",
    "github_store_self", "unknown",
)

// Hard allowlist of source short-names. Anything else is a 400. Keeps the
// fan-out target set under operator control — backend will NEVER call an
// arbitrary user-supplied host (SSRF / DoS risk).
private val VALID_SOURCES: Set<String> = ForgejoSearchClient.SOURCE_TO_HOST.keys

fun Route.externalMatchRoutes(service: ExternalMatchService) {
    post("/external-match") {
        if (!call.requireMaxBody(EXTERNAL_MATCH_MAX_BODY)) return@post

        val req = call.receive<ExternalMatchRequest>()

        if (req.platform != "android") {
            return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_platform", message = "platform must be 'android' for v1"))
        }
        if (req.candidates.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ApiError("empty_candidates"))
        }
        if (req.candidates.size > MAX_CANDIDATES_PER_REQUEST) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("batch_too_large", message = "max $MAX_CANDIDATES_PER_REQUEST candidates per request"),
            )
        }
        if (req.sources.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ApiError("empty_sources"))
        }
        if (req.sources.size > MAX_SOURCES_PER_REQUEST) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("too_many_sources", message = "max $MAX_SOURCES_PER_REQUEST sources per request"),
            )
        }
        val unknownSources = req.sources.filter { it !in VALID_SOURCES }
        if (unknownSources.isNotEmpty()) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    "invalid_source",
                    message = "unknown source(s): ${unknownSources.distinct()}; valid: ${VALID_SOURCES.sorted()}",
                ),
            )
        }

        // Per-candidate validation. Reject the whole batch on any malformed
        // entry rather than silently dropping it -- a buggy client that's
        // sending garbage should learn loudly.
        for (c in req.candidates) {
            if (!PACKAGE_NAME_RE.matches(c.packageName)) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_package_name"))
            }
            if (c.appLabel.isBlank() || c.appLabel.length > MAX_APP_LABEL_LEN) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_app_label"))
            }
            c.signingFingerprint?.let { fp ->
                if (!FINGERPRINT_RE.matches(fp)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_fingerprint"))
                }
            }
            c.installerKind?.let { kind ->
                if (kind !in INSTALLER_KINDS) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_installer_kind"))
                }
            }
            c.manifestHint?.owner?.let { owner ->
                if (!OWNER_RE.matches(owner)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_manifest_owner"))
                }
            }
            c.manifestHint?.repo?.let { repo ->
                if (!REPO_RE.matches(repo)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_manifest_repo"))
                }
            }
        }

        // Fan out across candidates in parallel -- they're independent and the
        // service's per-candidate work is dominated by HTTP latency to GitHub
        // + the optional forge fan-out. Bounded by MAX_CANDIDATES_PER_REQUEST
        // (25) × MAX_SOURCES_PER_REQUEST (8) = 200 concurrent upstreams worst
        // case; the per-client Ktor connection pool absorbs that.
        val matches = coroutineScope {
            req.candidates.map { c ->
                async {
                    ExternalMatchEntry(
                        packageName = c.packageName,
                        candidates = service.matchOne(c, req.sources),
                    )
                }
            }.awaitAll()
        }

        call.respond(ExternalMatchResponse(matches = matches))
    }
}
