package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.REQUEST_ID_KEY
import zed.rainxch.githubstore.ingest.GitHubDeviceClient
import zed.rainxch.githubstore.ingest.GitHubDeviceResponse
import zed.rainxch.githubstore.requireMaxBody
import zed.rainxch.githubstore.util.ApiError
import zed.rainxch.githubstore.util.PrivacyHash

private val log = LoggerFactory.getLogger("AuthRoutes")

private const val START_MAX_BODY = 1L * 1024
private const val POLL_MAX_BODY = 4L * 1024

// Used only to extract the `error` field from device-flow error-shaped 200
// responses (`{"error":"authorization_pending"}` etc). The `access_token` and
// `refresh_token` fields on success responses are intentionally absent from
// this DTO so they can NEVER end up in the deserialized object — even if we
// accidentally log it, there is nothing sensitive to leak.
@Serializable
private data class DeviceErrorProbe(val error: String? = null)

private val errorProbeJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun Route.authRoutes(deviceClient: GitHubDeviceClient) {
    route("/auth/device") {
        rateLimit(RateLimitName("auth-start")) {
            post("/start") {
                if (!call.requireMaxBody(START_MAX_BODY)) return@post
                try {
                    val result = deviceClient.startDeviceFlow()
                    val outStatus = if (result.status.isSuccess()) {
                        HttpStatusCode.OK
                    } else {
                        HttpStatusCode.BadGateway
                    }
                    call.respondText(
                        text = result.body,
                        contentType = ContentType.Application.Json,
                        status = outStatus,
                    )
                } catch (e: Exception) {
                    log.warn("auth/device/start upstream error: {}", e.message)
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ApiError("github_unreachable"),
                    )
                }
            }
        }

        rateLimit(RateLimitName("auth-poll")) {
            post("/poll") {
                if (!call.requireMaxBody(POLL_MAX_BODY)) return@post
                val form = try {
                    call.receiveParameters()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_body"),
                    )
                }
                val deviceCode = form["device_code"]?.takeIf { it.isNotBlank() }
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("missing_device_code"),
                    )

                // Diagnostics for the auth-stuck reports (GitHub-Store#433, #395
                // and the Sprint 3 Task #8 user-survey reports). We log exactly
                // the metadata needed to correlate a user-reported failed flow
                // with the backend's view: a stable hash of device_code (so the
                // user can paste the 16-char prefix and we can grep logs), the
                // upstream HTTP status, GitHub's `error` code if the body was
                // an error-shaped 200, latency, and the client UA. Never the
                // raw device_code, never the upstream body, never any token
                // field. See CLAUDE.md: "The backend must never log the access
                // token returned by a successful poll".
                val deviceCodeHash = PrivacyHash.hash(deviceCode).take(16)
                val userAgent = call.request.headers[HttpHeaders.UserAgent]
                    ?.replace('\n', ' ')
                    ?.replace('\r', ' ')
                    ?.take(120)
                    ?: "-"
                val rid = call.attributes.getOrNull(REQUEST_ID_KEY) ?: "-"
                val start = System.currentTimeMillis()

                val result: GitHubDeviceResponse = try {
                    deviceClient.pollDeviceToken(deviceCode)
                } catch (e: Exception) {
                    val latency = System.currentTimeMillis() - start
                    log.info(
                        "[auth-poll rid={}] dch={} ghs=- gh_err=upstream_exception lat_ms={} ua={}",
                        rid, deviceCodeHash, latency, userAgent,
                    )
                    log.warn("auth/device/poll upstream error: {}", e.message)
                    return@post call.respond(
                        HttpStatusCode.BadGateway,
                        ApiError("github_unreachable"),
                    )
                }
                val latency = System.currentTimeMillis() - start

                val githubErrorCode = parseErrorCode(result.body)
                log.info(
                    "[auth-poll rid={}] dch={} ghs={} gh_err={} lat_ms={} ua={}",
                    rid,
                    deviceCodeHash,
                    result.status.value,
                    githubErrorCode ?: "-",
                    latency,
                    userAgent,
                )

                // Device-flow pending/error states (authorization_pending,
                // slow_down, access_denied, expired_token, ...) arrive from
                // GitHub as HTTP 200 with an error-shaped body. Forward
                // 200→200 verbatim; the client already string-matches these.
                // Only non-2xx flips to 502 so the client's infrastructure
                // fallback predicate fires cleanly.
                val outStatus = if (result.status.isSuccess()) {
                    HttpStatusCode.OK
                } else {
                    HttpStatusCode.BadGateway
                }
                call.respondText(
                    text = result.body,
                    contentType = ContentType.Application.Json,
                    status = outStatus,
                )
            }
        }
    }
}

// Parses the `error` field out of a device-flow response. Returns null when
// the body isn't JSON, doesn't contain an `error` field, or any other parse
// failure. Crucially, only DeviceErrorProbe is deserialized — token fields
// from a successful response are never materialised in JVM memory beyond the
// raw `result.body` string we forward verbatim to the client.
private fun parseErrorCode(body: String): String? {
    if (body.isBlank()) return null
    return try {
        errorProbeJson.decodeFromString(DeviceErrorProbe.serializer(), body).error
    } catch (_: Exception) {
        null
    }
}
