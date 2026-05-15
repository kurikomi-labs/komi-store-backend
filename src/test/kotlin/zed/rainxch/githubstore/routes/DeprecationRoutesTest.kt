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

class DeprecationRoutesTest {

    private fun ApplicationTestBuilder.installPlugins() {
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
    }

    @Test
    fun `GET legacy device path returns 410 Gone with hint`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { deprecationRoutes() } } }

        val response = client.get("/v1/repo/login/device")
        assertEquals(HttpStatusCode.Gone, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"error\":\"endpoint_deprecated\""), body)
        assertTrue(body.contains("/v1/auth/device/start"), body)
        assertTrue(body.contains("/v1/auth/device/poll"), body)
        assertEquals(
            "public, max-age=86400",
            response.headers[HttpHeaders.CacheControl],
        )
    }

    @Test
    fun `POST legacy oauth path also returns 410 Gone`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { deprecationRoutes() } } }

        val response = client.post("/v1/repo/login/oauth") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Gone, response.status)
    }

    @Test
    fun `GET legacy oauth path returns 410 Gone`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { deprecationRoutes() } } }

        val response = client.get("/v1/repo/login/oauth")
        assertEquals(HttpStatusCode.Gone, response.status)
    }
}
