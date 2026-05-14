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
    fun `POST events returns 204 No Content so pre-1_8_3 clients see success and stop retrying`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { eventRoutes() } } }

        val response = client.post("/v1/events") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("[]")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(response.bodyAsText().isEmpty(), "204 must have an empty body")
    }

    @Test
    fun `body content is ignored - even invalid payloads still get 204`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { eventRoutes() } } }

        val response = client.post("/v1/events") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not-json-at-all")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }
}
