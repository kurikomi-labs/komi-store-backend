package zed.rainxch.githubstore.routes

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
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.ingest.GitHubDeviceClient
import zed.rainxch.githubstore.ingest.GitHubDeviceResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthPollDiagnosticsTest {

    private class FakeDeviceClient(
        private val response: GitHubDeviceResponse,
    ) : GitHubDeviceClient(clientId = "test-client-id") {
        var lastDeviceCode: String? = null

        override suspend fun pollDeviceToken(deviceCode: String): GitHubDeviceResponse {
            lastDeviceCode = deviceCode
            return response
        }

        override suspend fun startDeviceFlow(): GitHubDeviceResponse =
            error("startDeviceFlow not used in these tests")
    }

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeTest
    fun attachAppender() {
        logger = LoggerFactory.getLogger("AuthRoutes") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun ApplicationTestBuilder.setupApp(client: GitHubDeviceClient) {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(RateLimit) {
                // Tests focus on the route's logging + status-mapping behaviour;
                // the production buckets (10/hr/IP start, 200/hr/IP poll) would
                // be flaky here and slow each test by ~minutes. Register the
                // same bucket names with an unbounded ceiling so authRoutes
                // wires up without changing under test.
                register(RateLimitName("auth-start")) {
                    rateLimiter(limit = Int.MAX_VALUE, refillPeriod = kotlin.time.Duration.parse("1m"))
                }
                register(RateLimitName("auth-poll")) {
                    rateLimiter(limit = Int.MAX_VALUE, refillPeriod = kotlin.time.Duration.parse("1m"))
                }
            }
            routing { route("/v1") { authRoutes(client) } }
        }
    }

    private fun formBody(deviceCode: String?): String =
        if (deviceCode == null) "" else "device_code=$deviceCode"

    @Test
    fun `pending poll forwards 200 verbatim and logs authorization_pending error code`() = testApplication {
        val fake = FakeDeviceClient(
            GitHubDeviceResponse(
                status = HttpStatusCode.OK,
                body = """{"error":"authorization_pending"}""",
            ),
        )
        setupApp(fake)

        val response = client.post("/v1/auth/device/poll") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(formBody("dc_secret_value"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("authorization_pending"))

        val line = pollLogLine()
        assertTrue(line.contains("gh_err=authorization_pending"), line)
        assertTrue(line.contains("ghs=200"), line)
        assertTrue(line.contains("lat_ms="), line)
        assertTrue(line.contains("dch="), line)
    }

    @Test
    fun `successful poll never logs the raw device_code or any token body field`() = testApplication {
        val accessToken = "gho_test_token_must_not_leak"
        val fake = FakeDeviceClient(
            GitHubDeviceResponse(
                status = HttpStatusCode.OK,
                body = """{"access_token":"$accessToken","token_type":"bearer","scope":""}""",
            ),
        )
        setupApp(fake)

        val rawDeviceCode = "dc_must_not_be_logged"
        val response = client.post("/v1/auth/device/poll") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(formBody(rawDeviceCode))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        // Body forwarded verbatim — that's the contract with the client.
        assertTrue(response.bodyAsText().contains(accessToken))

        val line = pollLogLine()
        assertFalse(line.contains(accessToken), "access_token leaked into log: $line")
        assertFalse(line.contains(rawDeviceCode), "raw device_code leaked into log: $line")
        assertTrue(line.contains("gh_err=-"), line)
        assertTrue(line.contains("ghs=200"), line)
    }

    @Test
    fun `non-2xx upstream flips client response to 502 and logs upstream status`() = testApplication {
        val fake = FakeDeviceClient(
            GitHubDeviceResponse(
                status = HttpStatusCode.BadRequest,
                body = """{"error":"invalid_request"}""",
            ),
        )
        setupApp(fake)

        val response = client.post("/v1/auth/device/poll") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(formBody("dc_x"))
        }
        assertEquals(HttpStatusCode.BadGateway, response.status)

        val line = pollLogLine()
        assertTrue(line.contains("ghs=400"), line)
        assertTrue(line.contains("gh_err=invalid_request"), line)
    }

    @Test
    fun `missing device_code returns 400 and emits no auth-poll log line`() = testApplication {
        val fake = FakeDeviceClient(
            GitHubDeviceResponse(HttpStatusCode.OK, """{"error":"authorization_pending"}"""),
        )
        setupApp(fake)

        val response = client.post("/v1/auth/device/poll") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        // No upstream call, no auth-poll log entry.
        assertEquals(0, appender.list.count { it.formattedMessage.contains("[auth-poll") })
    }

    @Test
    fun `same device_code always hashes to the same dch prefix`() = testApplication {
        val fake = FakeDeviceClient(
            GitHubDeviceResponse(HttpStatusCode.OK, """{"error":"authorization_pending"}"""),
        )
        setupApp(fake)

        client.post("/v1/auth/device/poll") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(formBody("repeatable_code"))
        }
        client.post("/v1/auth/device/poll") {
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody(formBody("repeatable_code"))
        }

        val dchValues = appender.list
            .map { it.formattedMessage }
            .filter { it.contains("[auth-poll") }
            .map { Regex("dch=([0-9a-f]+)").find(it)?.groupValues?.get(1) }
        assertEquals(2, dchValues.size)
        assertNotNull(dchValues[0])
        assertEquals(dchValues[0], dchValues[1])
    }

    private fun pollLogLine(): String {
        val msgs = appender.list.map { it.formattedMessage }.filter { it.contains("[auth-poll") }
        assertEquals(1, msgs.size, "expected exactly one auth-poll log line, got: $msgs")
        return msgs.single()
    }
}
