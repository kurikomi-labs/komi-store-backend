package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.ingest.GitHubRepo
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.match.ForgejoResourceClient
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.util.GitHubIdentifiers

private val log = LoggerFactory.getLogger("RepoRoutes")

// Lazy-cache: keep a curated-DB-hit fast path, and on miss fetch metadata
// from GitHub (via the cache-aware resource client so ETag revalidation +
// mutex dedup + negative caching all apply). Non-curated repos get stored
// in resource_cache, not the `repos` table — search results stay clean.
//
// Forge support (brief 3.3): optional ?host=<forge> query routes the same
// owner/name lookup at a trusted Forgejo / Gitea host. The forge path
// skips the curated DB (which is GitHub-only) and goes straight to the
// ForgejoResourceClient cache + fetch. License is sniffed server-side
// once per repo so individual users don't redo the base64-decode +
// regex match.
fun Route.repoRoutes(
    repoRepository: RepoRepository,
    resourceClient: GitHubResourceClient,
    forgejoResourceClient: ForgejoResourceClient,
) {
    val lenientJson = Json { ignoreUnknownKeys = true }

    get("/repo/{owner}/{name}") {
        val owner = GitHubIdentifiers.validOwner(call.parameters["owner"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_owner"))
        val name = GitHubIdentifiers.validName(call.parameters["name"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_name"))

        // Forge-keyed lookup. `host` is hard-allowlisted on the route layer
        // (defence in depth — ForgejoResourceClient enforces the same set
        // again, so SSRF is structurally blocked even if validation drifts).
        val forgeHost = call.request.queryParameters["host"]?.trim()?.lowercase()
        if (!forgeHost.isNullOrBlank()) {
            if (!forgejoResourceClient.isTrusted(forgeHost)) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "invalid_host", "message" to "host not in trusted forge allowlist"),
                )
            }
            val resp = forgejoResourceClient.fetchRepo(forgeHost, owner, name)
            if (resp == null) {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
                return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Repo not found"))
            }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=30, s-maxage=300")
            return@get call.respond(resp)
        }

        // Fast path: curated DB hit — full RepoResponse with installer flags,
        // search_score, etc. GitHub-only (curated DB never indexed forges).
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
// /v1/releases/{owner}/{name} separately. `internal` so the refresh route
// can build the same shape for repos that were fetched but not persisted
// (no usable release).
internal fun GitHubRepo.toMetadataOnlyResponse(): RepoResponse = RepoResponse(
    id = id,
    name = name,
    fullName = fullName,
    owner = RepoOwner(login = owner.login, avatarUrl = owner.avatarUrl),
    description = description,
    defaultBranch = defaultBranch,
    htmlUrl = htmlUrl,
    stargazersCount = stargazersCount,
    forksCount = forksCount,
    openIssuesCount = openIssuesCount,
    licenseSpdxId = license?.spdxId,
    licenseName = license?.name,
    license = license?.let { zed.rainxch.githubstore.model.RepoLicense(spdxId = it.spdxId, name = it.name) },
    language = language,
    topics = topics,
    releasesUrl = "$htmlUrl/releases",
    updatedAt = updatedAt,
    createdAt = createdAt,
    pushedAt = pushedAt,
)
