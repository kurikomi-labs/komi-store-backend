package zed.rainxch.githubstore.routes

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventRoutesTest {

    private fun ApplicationTestBuilder.installPlugins() {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `POST events returns 410 Gone with deprecation notice`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { eventRoutes() } } }

        val response = client.post("/v1/events") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("[]")
        }

        assertEquals(HttpStatusCode.Gone, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"error\":\"endpoint_deprecated\""), "missing error code: $body")
        assertTrue(body.contains("\"deprecated_at\":\"2026-04-26\""), "missing deprecated_at: $body")
        assertTrue(body.contains("\"see\":\"https://github-store.org"), "missing see url: $body")
    }

    @Test
    fun `POST events sets long Cache-Control so old clients stop polling`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { eventRoutes() } } }

        val response = client.post("/v1/events") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("[]")
        }

        val cacheControl = response.headers[HttpHeaders.CacheControl]
        assertEquals("public, max-age=86400", cacheControl)
    }

    @Test
    fun `body content is ignored - even invalid payloads still get 410`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { eventRoutes() } } }

        val response = client.post("/v1/events") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not-json-at-all")
        }

        assertEquals(HttpStatusCode.Gone, response.status)
    }
}
