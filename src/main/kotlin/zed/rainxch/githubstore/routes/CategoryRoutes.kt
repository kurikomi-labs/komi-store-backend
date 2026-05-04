package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.db.RepoRepository

private val VALID_CATEGORIES = setOf("trending", "new-releases", "most-popular")
private val VALID_PLATFORMS = setOf("android", "windows", "macos", "linux")

fun Route.categoryRoutes(repoRepository: RepoRepository) {
    get("/categories/{category}/{platform}") {
        val category = call.parameters["category"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing category")
        )
        val platform = call.parameters["platform"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing platform")
        )

        if (category !in VALID_CATEGORIES) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid category. Must be one of: $VALID_CATEGORIES")
            )
        }
        if (platform !in VALID_PLATFORMS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid platform. Must be one of: $VALID_PLATFORMS")
            )
        }

        val repos = repoRepository.findByCategory(category, platform)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=600")
        call.respond(repos)
    }
}
