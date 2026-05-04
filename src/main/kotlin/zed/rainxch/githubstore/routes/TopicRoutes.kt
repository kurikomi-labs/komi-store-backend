package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.db.RepoRepository

private val VALID_BUCKETS = setOf("privacy", "media", "productivity", "networking", "dev-tools")
private val VALID_PLATFORMS = setOf("android", "windows", "macos", "linux")

fun Route.topicRoutes(repoRepository: RepoRepository) {
    get("/topics/{bucket}/{platform}") {
        val bucket = call.parameters["bucket"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing bucket")
        )
        val platform = call.parameters["platform"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing platform")
        )

        if (bucket !in VALID_BUCKETS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid bucket. Must be one of: $VALID_BUCKETS")
            )
        }
        if (platform !in VALID_PLATFORMS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid platform. Must be one of: $VALID_PLATFORMS")
            )
        }

        val repos = repoRepository.findByTopicBucket(bucket, platform)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=600")
        call.respond(repos)
    }
}
