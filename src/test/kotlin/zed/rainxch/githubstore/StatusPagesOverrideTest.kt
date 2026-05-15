package zed.rainxch.githubstore

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

// Regression pin for Ktor 3's StatusPages behaviour:
//
// `status(HttpStatusCode.NotFound)` in StatusPages OVERRIDES route-level
// `call.respond(HttpStatusCode.NotFound, body)` bodies. Both unmatched-route
// 404s AND explicit route 404s flow through the global handler — there is
// no built-in way to scope the handler to "unmatched routes only".
//
// Consequence: every route that wants a 404 with a custom body must either
// (a) route through the shared `Plugins.respondNotFound` helper so the body
// is consistent, or (b) use a different status code (e.g. 400 BadRequest
// for input-validation cases). The global handler emits ApiError("not_found")
// with a 300s edge-cache header and a privacy-safe `[404 …]` log line.
//
// If a future Ktor upgrade changes this behaviour, this test fails and we
// learn before InternalRoutes/BadgeRoutes/RepoRefreshRoutes start serving
// inconsistent 404 bodies.
class StatusPagesOverrideTest {

    @Test
    fun `status NotFound handler overrides explicit route-level 404 bodies`() = testApplication {
        application {
            install(StatusPages) {
                status(HttpStatusCode.NotFound) { call, _ ->
                    call.respondText("global-handler", status = HttpStatusCode.NotFound)
                }
            }
            routing {
                get("/route-with-body") {
                    call.respondText("route-level-body", status = HttpStatusCode.NotFound)
                }
            }
        }

        val response = client.get("/route-with-body")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("global-handler", response.bodyAsText())
    }
}
