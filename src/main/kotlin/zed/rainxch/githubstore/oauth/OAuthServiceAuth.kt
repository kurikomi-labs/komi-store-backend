package zed.rainxch.githubstore.oauth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.util.ApiError
import java.security.MessageDigest

// Gate the two S2S OAuth endpoints (/v1/oauth/state and /v1/oauth/exchange).
//
// Two checks, both must pass:
//   1. Shared secret  — `X-Oauth-Service-Token` header must equal the env
//      OAUTH_SERVICE_TOKEN (constant-time compared so neither length nor
//      content can leak via response timing).
//   2. Host allowlist — `Host:` header must be in OAUTH_SERVICE_ALLOWED_HOSTS
//      (comma-separated env). Belt-and-suspenders on top of (1): even if the
//      secret leaks, the request still has to land on the canonical vhost,
//      not the api-direct fallback or some accidental Cloudflare Workers
//      route. If the env is unset or blank the host check is skipped — that
//      mode is for local dev (`APP_ENV != production`) only; production
//      MUST configure both env vars (validateProductionEnv enforces both at
//      startup) or both routes refuse every request.
//
// All misconfiguration responses look identical to "wrong secret" responses
// (401 service_auth_required) so an anonymous prober can't fingerprint
// missing env vars vs. wrong-secret. Server-side logs carry the real reason.
class OAuthServiceAuth(
    private val expectedToken: String?,
    allowedHostsCsv: String?,
) {
    private val log = LoggerFactory.getLogger(OAuthServiceAuth::class.java)
    private val isProd = System.getenv("APP_ENV") == "production"

    private val allowedHosts: Set<String> =
        allowedHostsCsv?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    suspend fun authorize(call: ApplicationCall): Boolean {
        if (expectedToken.isNullOrBlank()) {
            log.error("[oauth-service-auth] OAUTH_SERVICE_TOKEN unset — every S2S OAuth request will be rejected")
            return rejectUnauthorized(call)
        }

        val provided = call.request.headers["X-Oauth-Service-Token"].orEmpty()
        if (!constantTimeEquals(provided, expectedToken)) {
            return rejectUnauthorized(call)
        }

        if (allowedHosts.isNotEmpty()) {
            val host = call.request.headers[HttpHeaders.Host]?.lowercase().orEmpty()
            // Strip optional :port suffix — operators sometimes set "api.example.org"
            // in the env but the Host header arrives as "api.example.org:443".
            val hostOnly = host.substringBefore(':')
            if (hostOnly !in allowedHosts) {
                log.info("[oauth-service-auth] rejected host={}", host)
                return rejectUnauthorized(call)
            }
        } else if (isProd) {
            log.error("[oauth-service-auth] OAUTH_SERVICE_ALLOWED_HOSTS unset in production — every S2S OAuth request will be rejected")
            return rejectUnauthorized(call)
        }

        return true
    }

    private suspend fun rejectUnauthorized(call: ApplicationCall): Boolean {
        call.respond(HttpStatusCode.Unauthorized, ApiError("service_auth_required"))
        return false
    }

    // MessageDigest.isEqual is constant-time on length AND content since
    // Java 6u17 — it processes both buffers fully rather than short-circuiting
    // on length mismatch. Avoids the length-side-channel a hand-rolled
    // length-prefixed XOR-or-accumulate would have.
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
}
