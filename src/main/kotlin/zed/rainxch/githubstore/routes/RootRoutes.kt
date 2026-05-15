package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Visiting the bare hostname used to 404. Scanners, monitoring tooling, and the
// occasional curious browser hit `/`, so a tiny greeting is cheaper at the edge
// than a 404 — and a clear pointer to the docs avoids confusion about whether
// the service is live. Long edge TTL: this body is byte-stable.
@Serializable
private data class RootGreeting(
    val name: String = "github-store-backend",
    val docs: String = "https://github-store.org",
    val api: String = "https://api.github-store.org/v1/",
)

private val ROOT_GREETING = RootGreeting()

fun Route.rootRoutes() {
    get("/") {
        call.response.header(
            HttpHeaders.CacheControl,
            "public, max-age=3600, s-maxage=86400",
        )
        call.respond(ROOT_GREETING)
    }
}
