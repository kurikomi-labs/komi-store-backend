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

class RootRoutesTest {

    private fun ApplicationTestBuilder.installPlugins() {
        application {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }
    }

    @Test
    fun `GET root returns greeting JSON`() = testApplication {
        installPlugins()
        application { routing { rootRoutes() } }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"name\":\"github-store-backend\""), body)
        assertTrue(body.contains("\"docs\":\"https://github-store.org\""), body)
        assertTrue(body.contains("\"api\":\"https://api.github-store.org/v1/\""), body)
    }

    @Test
    fun `GET root sets a long Cache-Control so CF holds the greeting`() = testApplication {
        installPlugins()
        application { routing { rootRoutes() } }

        val response = client.get("/")
        assertEquals(
            "public, max-age=3600, s-maxage=86400",
            response.headers[HttpHeaders.CacheControl],
        )
    }
}
