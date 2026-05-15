package zed.rainxch.githubstore

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import io.sentry.Sentry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import zed.rainxch.githubstore.routes.ADMIN_BASIC_AUTH
import zed.rainxch.githubstore.util.ApiError
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
}

internal val REQUEST_ID_KEY = AttributeKey<String>("RequestId")
private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9\\-]{1,64}$")

// Reject oversized or unknown-size bodies before reading them.
// Idiom at call sites: `if (!call.requireMaxBody(N)) return@post`.
// Only effective when the client sends Content-Length — chunked transfer-
// encoding bypasses this. Caddy's `request_body { max_size ... }` directive
// handles the chunked path at the edge.
internal suspend fun ApplicationCall.requireMaxBody(maxBytes: Long): Boolean {
    val len = request.contentLength()
    if (len == null || len > maxBytes) {
        respond(HttpStatusCode.PayloadTooLarge, ApiError("payload_too_large"))
        return false
    }
    return true
}

// Hoisted so all rate-limit buckets share one definition. A typo in any
// inlined copy would silently degrade that bucket's key to "unknown",
// collapsing every IP into one shared quota.
//
// Resolution order:
//   1. CF-Connecting-IP -- set by Cloudflare on the api.github-store.org
//      vhost. Cloudflare always overwrites whatever the client sent, so
//      this is the real origin client when the request actually traversed
//      Cloudflare. The api-direct.github-store.org vhost strips this
//      header at Caddy, so it can't be forged on the direct path.
//   2. X-Forwarded-For first IP -- on api-direct, Caddy overwrites XFF
//      with the real TCP source so forgery is defeated. On api (when CF
//      is bypassed somehow), Caddy passes through whatever Cloudflare put
//      there (Cloudflare appends real IP to existing XFF), so the first
//      IP could be a forgery; CF-Connecting-IP is preferred above.
//   3. Literal "unknown" -- shouldn't happen behind Caddy, but keeps the
//      bucket-key function total instead of nullable.
private fun forwardedFor(call: io.ktor.server.application.ApplicationCall): String {
    val cf = call.request.headers["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotEmpty() }
    if (cf != null) return cf
    val xff = call.request.headers["X-Forwarded-For"]
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
        .orEmpty()
    return xff.ifEmpty { "unknown" }
}

// Key function for the search bucket (covers /search, /search/explore,
// /releases, /readme, /user — all the upstream-passthrough routes). Behind a
// CDN POP, IP-only keying collapses every user behind one regional POP into
// a single 60/min slot. Keying by a hash of the user's X-GitHub-Token when
// present spreads the limit per-user instead. Anonymous callers still share
// by IP (correct — no token to distinguish them). The hash uses the same
// HmacSHA256(pepper, ...) that hashes device IDs; truncated to 16 hex chars
// to keep rate-limit bucket keys short. Token contents never appear in logs.
private fun searchBucketKey(call: io.ktor.server.application.ApplicationCall): String {
    val token = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }
    return if (token != null) {
        "tok:${zed.rainxch.githubstore.util.PrivacyHash.hash(token).take(16)}"
    } else {
        "ip:${forwardedFor(call)}"
    }
}

// Shared 404 responder. Logs the unmatched method + path (NOT the query
// string — query can carry user search terms), sets a short edge cache so
// scanners and broken clients can't pin origin, and returns the same JSON
// shape every other 4xx uses. Path is bracketed so `grep '\[404 ...]'` finds
// only 404 lines on a noisy log.
//
// Called by:
//   - The global `status(NotFound)` StatusPages handler (unmatched routes
//     and any route-level 404 — Ktor 3's StatusPages overrides route-level
//     bodies, see StatusPagesOverrideTest).
//   - Routes that want the same body shape + caching + log without going
//     through StatusPages (`InternalRoutes`).
internal suspend fun respondNotFound(call: io.ktor.server.application.ApplicationCall) {
    val rid = call.attributes.getOrNull(REQUEST_ID_KEY)
    val method = call.request.httpMethod.value
    val path = call.request.path()
    call.application.environment.log.info(
        "[404 rid={}] {} {}", rid ?: "-", method, path,
    )
    call.response.header(HttpHeaders.CacheControl, "public, max-age=300, s-maxage=300")
    call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
}

fun Application.configureHTTP() {
    install(DefaultHeaders) {
        header("X-Engine", "github-store-backend")
    }

    // Mint a request ID early in the pipeline so every log line and error
    // report can correlate. Respects a client-supplied X-Request-ID if
    // present (useful when debugging a chain of services), otherwise
    // generates a short random hex.
    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
        // Whitelist alphanumerics and hyphen — anything else (control chars,
        // newlines, ANSI escapes, HTML) could be echoed back into log lines
        // and tail-aggregator output. Drop on any mismatch and fall through
        // to a server-generated UUID.
        val incoming = call.request.headers["X-Request-ID"]?.takeIf(REQUEST_ID_PATTERN::matches)
        val id = incoming ?: UUID.randomUUID().toString().substring(0, 12)
        call.attributes.put(REQUEST_ID_KEY, id)
        call.response.header("X-Request-ID", id)
    }

    // CORS is only useful for browser-based callers. The KMP client never sends
    // Origin (native HttpClient), so this only affects the admin dashboard (same
    // origin as the API — doesn't need CORS) and any future web surface. Pinning
    // to our own domains removes a CSRF foothold on state-changing POSTs (e.g.
    // /v1/repo/{owner}/{name}/refresh) from malicious third-party pages without
    // breaking anything we actually serve.
    install(CORS) {
        allowHost("github-store.org", subDomains = listOf("api", "api-direct", "www"))
        // localhost dev origins are only useful when developing the admin
        // dashboard or a future web client locally. Gating them on APP_ENV
        // keeps them out of the production allowlist so a malicious page
        // can't pretend to be localhost via header forgery against prod.
        if (System.getenv("APP_ENV") != "production") {
            allowHost("localhost:8080")
            allowHost("localhost:5173") // vite dev default, harmless if unused
        }
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-GitHub-Token")
        // X-Admin-Token is intentionally NOT in the CORS allowlist. Admin
        // endpoints are reached via curl/SSH only — never from a browser —
        // so allowing the header would only enable a CSRF-adjacent abuse
        // path (e.g. tricking an admin's browser into firing requests).
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
    }

    install(Compression) {
        gzip {
            priority = 1.0
        }
    }

    // Route HEAD requests to the matching GET handler. Without this, HEAD
    // returns 404 even when GET works — confusing for `curl -I`, monitoring,
    // and CDN origin probes.
    install(AutoHeadResponse)

    install(RateLimit) {
        // General API: 360 requests per minute per IP.
        //
        // Note on the key: forwardedFor() reads CF-Connecting-IP first
        // (set by Cloudflare on api.github-store.org) and X-Forwarded-For
        // first IP otherwise (real TCP source on api-direct, since Caddy
        // overwrites XFF there). Cloudflare always overwrites the
        // CF-Connecting-IP it sets, so it's forge-resistant on the
        // CDN-fronted vhost; the api-direct vhost strips the header at
        // Caddy as defence-in-depth.
        //
        // CDN vs direct-path note: every Cloudflare POP carries thousands
        // of users; without per-user IP keying the bucket would fold them
        // all into one slot and rate-limit anonymous traffic globally
        // whenever a single user got busy. CF-Connecting-IP gives each
        // real client its own bucket on the CDN path. On api-direct the
        // TCP source is the actual user IP and the same per-user keying
        // applies via XFF.
        //
        // Bumped from 120 -> 360 after observing real client burst patterns:
        // a single details-page open already fans out to /repo + /releases +
        // /readme + /user (4 slots), and the cold-start preload pulls
        // /announcements + /categories + /topics in parallel. Global is
        // evaluated alongside per-route buckets, so any route in the search
        // bucket (240/min) was previously gated by global=120 -- the search
        // bump had no effect for the same-IP case. 360 keeps the per-route
        // ceilings as the binding limit on real traffic. VPS handles the new
        // ceiling without breaking a sweat (120 req/sec on 4 vCPU is fine).
        global {
            rateLimiter(limit = 360, refillPeriod = 1.minutes)
            requestKey(::forwardedFor)
        }
        // Search bucket: 240/min/key. Covers /search, /search/explore,
        // /releases, /readme, /user, /users/{u}/repos, /users/{u}/starred --
        // every route that fans out to the GitHub API. Keyed by token-hash
        // when present, IP otherwise (see searchBucketKey for rationale).
        //
        // Bumped from 60 -> 240 after observing real client burst patterns:
        // a single details-page open can fan out to /repo + /releases +
        // /readme + /user, and the developer-profile screen further pulls
        // /users/{u}/repos + /users/{u}/starred. The aggregate 4-token pool
        // (~20k/hr to GitHub) and per-route Cloudflare s-maxage absorb the
        // real upstream load -- backend's own bucket was the constraint, not
        // GitHub's quota.
        register(RateLimitName("search")) {
            rateLimiter(limit = 240, refillPeriod = 1.minutes)
            requestKey(::searchBucketKey)
        }
        // Badges: 60/min/IP. Embedded in READMEs so a single popular repo can
        // generate steady traffic; the limit is per-viewer-IP, not per-repo.
        register(RateLimitName("badges")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey(::forwardedFor)
        }
        // Auth device-flow start: low volume (one per login attempt). 1/hr/IP
        // (tightened 10× for direct-path abuse). One legitimate login per hour
        // per device is plenty; a NAT'd household burns this quickly but the
        // direct path is the fallback, not the primary, so the tradeoff favors
        // the abuse-floor over the rare retry.
        register(RateLimitName("auth-start")) {
            rateLimiter(limit = 1, refillPeriod = 1.hours)
            requestKey(::forwardedFor)
        }
        // Auth device-flow poll: a real flow is ~180 polls over 15min at 5s
        // intervals, so the floor cannot drop below ~200 without breaking
        // every login. Kept at 200/hr/IP — `auth-start` is the chokepoint for
        // abuse, since you can't poll without a device_code from start.
        register(RateLimitName("auth-poll")) {
            rateLimiter(limit = 200, refillPeriod = 1.hours)
            requestKey(::forwardedFor)
        }
        // External match: client batches up to 25 candidates per call, each
        // does 1-2 GitHub API calls. 30/min/IP gives ~750 candidates/min/IP,
        // which fits any legitimate "import existing apps" flow comfortably.
        register(RateLimitName("external-match")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey(::forwardedFor)
        }
        // Signing seeds: paginated dump, clients fetch incrementally with a
        // `since` cursor so steady-state traffic is one short call per sync.
        // 60/min/IP covers a worst-case "client lost its local seed table
        // and is re-paging the whole 5k-row corpus" scenario.
        register(RateLimitName("signing-seeds")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey(::forwardedFor)
        }
        // Mirrors list: clients fetch ~once per 24h normally; 30/min/IP is
        // generous against any reasonable refresh pattern but caps abuse.
        // The endpoint also has aggressive Cloudflare edge caching so most
        // requests don't reach origin at all.
        register(RateLimitName("mirrors-list")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey(::forwardedFor)
        }
    }

    // Basic Auth for the /v1/internal/dashboard HTML page. Username is
    // ignored (anything goes); password must match ADMIN_TOKEN. Matches the
    // token-gated JSON /v1/internal/metrics path so the browser gets a single
    // credential to carry across both fetches.
    val adminToken = System.getenv("ADMIN_TOKEN")?.takeIf { it.isNotBlank() }
    install(Authentication) {
        basic(ADMIN_BASIC_AUTH) {
            realm = "github-store-admin"
            validate { creds ->
                // Constant-time compare so an attacker can't binary-search the
                // token from the response-time delta of a `==` short-circuit.
                if (adminToken != null && MessageDigest.isEqual(
                        creds.password.toByteArray(StandardCharsets.UTF_8),
                        adminToken.toByteArray(StandardCharsets.UTF_8),
                    )
                ) {
                    UserIdPrincipal(creds.name)
                } else null
            }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        disableDefaultColors()
        // Populate SLF4J MDC with the request ID so any structured log
        // consumer (Sentry breadcrumbs, JSON log aggregators) picks it up
        // automatically via the MDC key "rid". Ktor's CallLogging plugin
        // manages MDC lifecycle around the call.
        mdc("rid") { call -> call.attributes.getOrNull(REQUEST_ID_KEY) }
        // Explicit bracket-prefixed access-log line so `grep 'rid=7404b7e8'`
        // on raw log files works without a structured-log pipeline. The
        // default Ktor format is sparse and doesn't carry the request ID
        // into the message body.
        format { call ->
            val rid = call.attributes.getOrNull(REQUEST_ID_KEY) ?: "-"
            val status = call.response.status()?.value ?: "-"
            val method = call.request.httpMethod.value
            val path = call.request.path()
            "[rid=$rid] $status $method $path"
        }
    }

    install(StatusPages) {
        // Client-error exceptions thrown by Ktor's content-negotiation, request-
        // validation, and parameter-conversion plugins. These are 4xx by nature
        // — malformed JSON, wrong shape, bad path/query types — and surfacing
        // them as 500 + Sentry events is misleading and quota-burning.
        exception<BadRequestException> { call, cause ->
            val rid = call.attributes.getOrNull(REQUEST_ID_KEY)
            call.application.environment.log.info(
                "Bad request (rid={}): {}", rid ?: "-", cause.javaClass.simpleName,
            )
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
        }
        exception<NotFoundException> { call, _ ->
            respondNotFound(call)
        }
        // Catch every unmatched-route 404 (Ktor's default response has no body
        // and no Cache-Control). One handler gives us:
        //   - consistent JSON shape ({error, message}) across the API
        //   - structured access log entry with method + path (no query) so we
        //     can classify scanner traffic vs old-client paths from Cloudflare
        //     analytics + the application log
        //   - short edge cache so repeat scanner hits don't slam origin
        status(HttpStatusCode.NotFound) { call, _ ->
            respondNotFound(call)
        }
        // 429s come out of the RateLimit plugin with Retry-After but an empty
        // body. Replace that with a JSON body the client can parse + display
        // ("rate_limited" + retry_after seconds). The Retry-After header set
        // by the RateLimit plugin is preserved on the response.
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            val retryAfter = call.response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiError(
                    error = "rate_limited",
                    message = if (retryAfter != null) "Retry after ${retryAfter}s" else "Rate limit exceeded",
                ),
            )
        }
        exception<Throwable> { call, cause ->
            val rid = call.attributes.getOrNull(REQUEST_ID_KEY)
            call.application.environment.log.error(
                "Unhandled exception (rid=${rid ?: "-"})",
                cause,
            )
            // Tag Sentry events with the same request_id users paste into bug
            // reports — makes the Sentry UI filter one-click.
            Sentry.withScope { scope ->
                if (rid != null) scope.setTag("request_id", rid)
                Sentry.captureException(cause)
            }
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("internal_error", message = "Internal server error"),
            )
        }
    }
}
