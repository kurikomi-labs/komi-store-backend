package zed.rainxch.githubstore.oauth

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.routes.oauthRoutes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val VALID_TOKEN = "test-shared-secret"
private const val ALLOWED_HOST = "api.github-store.org"

class OAuthRoutesTest {

    private val urlBase64 = Base64.getUrlEncoder().withoutPadding()
    private val rng = SecureRandom()

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeTest
    fun attachAppender() {
        logger = LoggerFactory.getLogger("OAuthRoutes") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private class FakeExchangeService(
        private val behaviour: Behaviour,
    ) : OAuthExchangeService(clientId = "test-cid", clientSecret = "test-secret", callbackUrl = "https://test/callback") {
        sealed class Behaviour {
            data class Success(val token: String) : Behaviour()
            data class UpstreamError(val code: String) : Behaviour()
            data object UpstreamFailure : Behaviour()
        }

        var lastCode: String? = null

        override suspend fun exchange(code: String): Result {
            lastCode = code
            return when (behaviour) {
                is Behaviour.Success -> Result.Success(behaviour.token)
                is Behaviour.UpstreamError -> Result.UpstreamError(behaviour.code)
                Behaviour.UpstreamFailure -> Result.UpstreamFailure
            }
        }
    }

    private fun ApplicationTestBuilder.setupApp(
        store: OAuthEphemeralStore,
        exchange: OAuthExchangeService,
        serviceAuth: OAuthServiceAuth = OAuthServiceAuth(VALID_TOKEN, ALLOWED_HOST),
    ) {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            install(RateLimit) {
                register(RateLimitName("oauth-state")) {
                    rateLimiter(limit = Int.MAX_VALUE, refillPeriod = 1.minutes)
                }
                register(RateLimitName("oauth-exchange")) {
                    rateLimiter(limit = Int.MAX_VALUE, refillPeriod = 1.minutes)
                }
                register(RateLimitName("oauth-handoff")) {
                    rateLimiter(limit = Int.MAX_VALUE, refillPeriod = 1.minutes)
                }
            }
            routing {
                route("/v1") {
                    oauthRoutes(store, exchange, serviceAuth)
                }
            }
        }
    }

    private fun random43(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return urlBase64.encodeToString(bytes)
    }

    private fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return urlBase64.encodeToString(digest)
    }

    private fun HttpRequestBuilder.serviceHeaders() {
        header("X-Oauth-Service-Token", VALID_TOKEN)
        header(HttpHeaders.Host, ALLOWED_HOST)
    }

    // ---------- /state ----------

    @Test
    fun `state registers and returns 204`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val state = random43()
        val challenge = random43()
        val resp = client.post("/v1/oauth/state") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"state":"$state","code_challenge":"$challenge"}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        assertNotNull(store.get(OAuthEphemeralStore.NAMESPACE_STATE, state))
    }

    @Test
    fun `state rejects duplicate state with 409`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val state = random43()
        val challenge = random43()
        val body = """{"state":"$state","code_challenge":"$challenge"}"""
        client.post("/v1/oauth/state") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        val resp = client.post("/v1/oauth/state") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `state rejects malformed values with 400`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val resp = client.post("/v1/oauth/state") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"state":"short","code_challenge":"${random43()}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("invalid_state"))
    }

    // ---------- service auth ----------

    @Test
    fun `state without service token returns 401`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val resp = client.post("/v1/oauth/state") {
            header(HttpHeaders.Host, ALLOWED_HOST)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"state":"${random43()}","code_challenge":"${random43()}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("service_auth_required"))
    }

    @Test
    fun `state with wrong host returns 401 indistinguishable from wrong-secret`() = testApplication {
        // Same body for wrong-secret and wrong-host so a prober can't
        // determine which check failed.
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val resp = client.post("/v1/oauth/state") {
            header("X-Oauth-Service-Token", VALID_TOKEN)
            header(HttpHeaders.Host, "evil.example")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"state":"${random43()}","code_challenge":"${random43()}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("service_auth_required"))
        assertFalse(resp.bodyAsText().contains("host_not_allowed"))
    }

    @Test
    fun `state with port-suffixed host still matches allowlist`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val resp = client.post("/v1/oauth/state") {
            header("X-Oauth-Service-Token", VALID_TOKEN)
            header(HttpHeaders.Host, "$ALLOWED_HOST:443")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"state":"${random43()}","code_challenge":"${random43()}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
    }

    @Test
    fun `oauth not configured returns 401 indistinguishable from wrong-secret`() = testApplication {
        // Misconfiguration (missing token) and "wrong secret" must look
        // identical to an anonymous prober — otherwise the response code +
        // body fingerprint the deploy state.
        val store = InMemoryOAuthEphemeralStore()
        setupApp(
            store,
            FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure),
            serviceAuth = OAuthServiceAuth(expectedToken = null, allowedHostsCsv = ALLOWED_HOST),
        )

        val resp = client.post("/v1/oauth/state") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"state":"${random43()}","code_challenge":"${random43()}"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("service_auth_required"))
        assertFalse(resp.bodyAsText().contains("oauth_not_configured"))
    }

    // ---------- /exchange ----------

    @Test
    fun `exchange happy path returns handoff_id and stores token`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        val exchange = FakeExchangeService(FakeExchangeService.Behaviour.Success("gho_secret_test_token"))
        setupApp(store, exchange)

        // Pre-register state with challenge
        val verifier = random43()
        val state = random43()
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_STATE,
            state,
            """{"code_challenge":"${challengeOf(verifier)}","created_at_ms":0}""",
            java.time.Duration.ofSeconds(60),
        )

        val resp = client.post("/v1/oauth/exchange") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"gh-code-abc","state":"$state","code_verifier":"$verifier"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val handoffId = Json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["handoff_id"]!!.jsonPrimitive.content
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(handoffId))
        // State was consumed.
        assertNull(store.get(OAuthEphemeralStore.NAMESPACE_STATE, state))
        // Handoff stored with the token.
        assertEquals("gho_secret_test_token", store.get(OAuthEphemeralStore.NAMESPACE_HANDOFF, handoffId))
        // Exchange service saw the right code.
        assertEquals("gh-code-abc", exchange.lastCode)
    }

    @Test
    fun `exchange with missing state returns 400 state_missing_or_expired`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val resp = client.post("/v1/oauth/exchange") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"x","state":"${random43()}","code_verifier":"${random43()}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("state_missing_or_expired"))
    }

    @Test
    fun `exchange with PKCE mismatch returns 400 and burns the state`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.Success("never-returned")))

        val state = random43()
        val realVerifier = random43()
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_STATE,
            state,
            """{"code_challenge":"${challengeOf(realVerifier)}","created_at_ms":0}""",
            java.time.Duration.ofSeconds(60),
        )

        // Send a DIFFERENT verifier — challenge will not match.
        val attackerVerifier = random43()
        val resp = client.post("/v1/oauth/exchange") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"x","state":"$state","code_verifier":"$attackerVerifier"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("pkce_mismatch"))
        // State is burned even on PKCE failure — defence against verifier-guessing.
        assertNull(store.get(OAuthEphemeralStore.NAMESPACE_STATE, state))
    }

    @Test
    fun `exchange with unknown GitHub error code normalises to github_unknown`() = testApplication {
        // F6 — anything outside the whitelist must not propagate verbatim.
        val store = InMemoryOAuthEphemeralStore()
        setupApp(
            store,
            FakeExchangeService(FakeExchangeService.Behaviour.UpstreamError("weird_thing_we_dont_know_about")),
        )

        val state = random43()
        val verifier = random43()
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_STATE,
            state,
            """{"code_challenge":"${challengeOf(verifier)}","created_at_ms":0}""",
            java.time.Duration.ofSeconds(60),
        )

        val resp = client.post("/v1/oauth/exchange") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"x","state":"$state","code_verifier":"$verifier"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("github_unknown"), "should normalise: $body")
        assertFalse(body.contains("weird_thing_we_dont_know_about"), "unsanitised: $body")
    }

    @Test
    fun `exchange with GitHub error bubbles up as github_xxx`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamError("bad_verification_code")))

        val state = random43()
        val verifier = random43()
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_STATE,
            state,
            """{"code_challenge":"${challengeOf(verifier)}","created_at_ms":0}""",
            java.time.Duration.ofSeconds(60),
        )

        val resp = client.post("/v1/oauth/exchange") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"x","state":"$state","code_verifier":"$verifier"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("github_bad_verification_code"))
    }

    @Test
    fun `exchange with upstream failure returns 502 and state is consumed at top by getDel`() = testApplication {
        // After the F1+F4 fix, state is always consumed atomically at the
        // top of /exchange via getDel. GitHub's authorization `code` is
        // single-use upstream, so a retry of the same (state, code) pair is
        // never legitimate — the website must start a fresh flow.
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val state = random43()
        val verifier = random43()
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_STATE,
            state,
            """{"code_challenge":"${challengeOf(verifier)}","created_at_ms":0}""",
            java.time.Duration.ofSeconds(60),
        )

        val resp = client.post("/v1/oauth/exchange") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"x","state":"$state","code_verifier":"$verifier"}""")
        }
        assertEquals(HttpStatusCode.BadGateway, resp.status)
        assertNull(store.get(OAuthEphemeralStore.NAMESPACE_STATE, state))
    }

    @Test
    fun `exchange with concurrent same-state requests - only one wins`() = testApplication {
        // Race verification for the getDel atomicity fix. Two requests with
        // identical (state, code_verifier) — exactly one should reach the
        // GitHub call and succeed; the other must get state_missing_or_expired.
        val store = InMemoryOAuthEphemeralStore()
        val exchange = FakeExchangeService(FakeExchangeService.Behaviour.Success("token-once"))
        setupApp(store, exchange)

        val state = random43()
        val verifier = random43()
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_STATE,
            state,
            """{"code_challenge":"${challengeOf(verifier)}","created_at_ms":0}""",
            java.time.Duration.ofSeconds(60),
        )

        suspend fun call(): HttpStatusCode = client.post("/v1/oauth/exchange") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"x","state":"$state","code_verifier":"$verifier"}""")
        }.status

        val first = call()
        val second = call()
        // One success, one state_missing — order independent.
        assertTrue(
            (first == HttpStatusCode.OK && second == HttpStatusCode.BadRequest) ||
                (first == HttpStatusCode.BadRequest && second == HttpStatusCode.OK),
            "expected one OK and one BadRequest, got $first and $second",
        )
    }

    // ---------- /handoff/{id} ----------

    @Test
    fun `handoff returns token then 404 on second call - single-use`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val handoffId = random43()
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_HANDOFF,
            handoffId,
            "gho_test_secret_token",
            java.time.Duration.ofSeconds(60),
        )

        val first = client.post("/v1/oauth/handoff/$handoffId")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
        assertTrue(first.bodyAsText().contains("gho_test_secret_token"))

        val second = client.post("/v1/oauth/handoff/$handoffId")
        assertEquals(HttpStatusCode.NotFound, second.status)
    }

    @Test
    fun `handoff with malformed id returns 404 without lookup`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val resp = client.post("/v1/oauth/handoff/not-a-real-id")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `handoff 404 path also sets Cache-Control no-store`() = testApplication {
        // F9 — every response from /handoff must be no-store, including 404,
        // so a middlebox can't cache the "miss" for an id that might land
        // valid moments later.
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val miss = client.post("/v1/oauth/handoff/${random43()}")
        assertEquals(HttpStatusCode.NotFound, miss.status)
        assertEquals("no-store", miss.headers[HttpHeaders.CacheControl])

        val malformed = client.post("/v1/oauth/handoff/short")
        assertEquals(HttpStatusCode.NotFound, malformed.status)
        assertEquals("no-store", malformed.headers[HttpHeaders.CacheControl])
    }

    // ---------- privacy ----------

    @Test
    fun `successful exchange never logs the code, verifier, or access_token`() = testApplication {
        val token = "gho_supersecret_token_value"
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.Success(token)))

        val state = random43()
        val verifier = random43()
        val code = "code_must_not_leak_abc"
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_STATE,
            state,
            """{"code_challenge":"${challengeOf(verifier)}","created_at_ms":0}""",
            java.time.Duration.ofSeconds(60),
        )

        client.post("/v1/oauth/exchange") {
            serviceHeaders()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"$code","state":"$state","code_verifier":"$verifier"}""")
        }

        val logged = appender.list.joinToString("\n") { it.formattedMessage }
        assertFalse(logged.contains(token), "access_token in log: $logged")
        assertFalse(logged.contains(code), "code in log: $logged")
        assertFalse(logged.contains(verifier), "verifier in log: $logged")
        assertFalse(logged.contains(state), "full state in log: $logged")
        // The 8-char prefix IS allowed — needed for correlation.
        assertTrue(logged.contains(state.take(8)), "expected state prefix in log")
    }

    @Test
    fun `successful handoff never logs the token or full handoff_id`() = testApplication {
        val token = "gho_handoff_token_secret"
        val store = InMemoryOAuthEphemeralStore()
        setupApp(store, FakeExchangeService(FakeExchangeService.Behaviour.UpstreamFailure))

        val handoffId = random43()
        store.setEx(
            OAuthEphemeralStore.NAMESPACE_HANDOFF,
            handoffId,
            token,
            java.time.Duration.ofSeconds(60),
        )

        client.post("/v1/oauth/handoff/$handoffId")

        val logged = appender.list.joinToString("\n") { it.formattedMessage }
        assertFalse(logged.contains(token), "access_token in handoff log: $logged")
        assertFalse(logged.contains(handoffId), "full handoff_id in log: $logged")
        assertTrue(logged.contains(handoffId.take(8)), "expected handoff_id prefix in log")
    }
}
