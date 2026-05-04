package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.ingest.GitHubRepo
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.util.GitHubIdentifiers

private val log = LoggerFactory.getLogger("RepoRoutes")

// Lazy-cache: keep a curated-DB-hit fast path, and on miss fetch metadata
// from GitHub (via the cache-aware resource client so ETag revalidation +
// mutex dedup + negative caching all apply). Non-curated repos get stored
// in resource_cache, not the `repos` table — search results stay clean.
fun Route.repoRoutes(
    repoRepository: RepoRepository,
    resourceClient: GitHubResourceClient,
) {
    val lenientJson = Json { ignoreUnknownKeys = true }

    get("/repo/{owner}/{name}") {
        val owner = GitHubIdentifiers.validOwner(call.parameters["owner"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_owner"))
        val name = GitHubIdentifiers.validName(call.parameters["name"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_name"))

        // Fast path: curated DB hit — full RepoResponse with installer flags,
        // search_score, etc.
        val fromDb = repoRepository.findByOwnerAndName(owner, name)
        if (fromDb != null) {
            call.response.header(HttpHeaders.CacheControl, "public, max-age=30, s-maxage=300")
            return@get call.respond(fromDb)
        }

        // Miss: lazy-fetch metadata from GitHub, cache, return a RepoResponse
        // with null release-related fields. Client will separately hit
        // /v1/releases/{owner}/{name} to get release + install info.
        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        val result = resourceClient.fetchCached(
            cacheKey = "repo:$owner/$name",
            upstreamUrl = "https://api.github.com/repos/$owner/$name",
            userToken = userToken,
            ttlSeconds = 86_400L,       // 24h — repo metadata barely moves
            negativeTtlSeconds = 900L,  // 15 min — dampen fake-URL bombardment
        )

        when (result) {
            is GitHubResourceClient.Result.Hit,
            is GitHubResourceClient.Result.StaleFallback -> {
                val body = when (result) {
                    is GitHubResourceClient.Result.Hit -> result.body
                    is GitHubResourceClient.Result.StaleFallback -> result.body
                    else -> error("unreachable")
                }
                val gh = try {
                    lenientJson.decodeFromString(GitHubRepo.serializer(), body)
                } catch (e: Exception) {
                    log.warn("Failed to parse GitHub repo body for $owner/$name: {}", e.message)
                    return@get call.respond(
                        HttpStatusCode.BadGateway,
                        mapOf("error" to "upstream_parse_failed"),
                    )
                }
                if (result is GitHubResourceClient.Result.StaleFallback) {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.response.header("X-Cache-State", "stale-fallback")
                } else {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=30, s-maxage=300")
                }
                call.respond(gh.toMetadataOnlyResponse())
            }
            is GitHubResourceClient.Result.NegativeHit -> {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
                call.respond(
                    HttpStatusCode.fromValue(result.status),
                    mapOf("error" to "Repo not found"),
                )
            }
            is GitHubResourceClient.Result.UpstreamError -> {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "github_unreachable"))
            }
        }
    }
}

// Metadata-only response for lazy-cached repos. Release/installer/download
// fields take their schema defaults — the client will fill these in from
// /v1/releases/{owner}/{name} separately.
private fun GitHubRepo.toMetadataOnlyResponse(): RepoResponse = RepoResponse(
    id = id,
    name = name,
    fullName = fullName,
    owner = RepoOwner(login = owner.login, avatarUrl = owner.avatarUrl),
    description = description,
    defaultBranch = defaultBranch,
    htmlUrl = htmlUrl,
    stargazersCount = stargazersCount,
    forksCount = forksCount,
    language = language,
    topics = topics,
    releasesUrl = "$htmlUrl/releases",
    updatedAt = updatedAt,
    createdAt = createdAt,
)
