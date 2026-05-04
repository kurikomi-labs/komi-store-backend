package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.SearchMissRepository
import zed.rainxch.githubstore.db.SearchRepository
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry
import zed.rainxch.githubstore.model.ExploreResponse
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.model.SearchResponse

private val VALID_PLATFORMS = setOf("android", "windows", "macos", "linux")
private val VALID_SORTS = setOf("relevance", "stars", "recent")
private const val ON_DEMAND_THRESHOLD = 5

fun Route.searchRoutes(
    meilisearch: MeilisearchClient,
    searchRepository: SearchRepository,
    githubSearch: GitHubSearchClient,
    searchMissRepository: SearchMissRepository,
    metrics: SearchMetricsRegistry,
) {
    get("/search") {
        val query = call.request.queryParameters["q"]
        if (query.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing query parameter 'q'")
            )
        }

        val platform = call.request.queryParameters["platform"]
        if (platform != null && platform !in VALID_PLATFORMS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid platform. Must be one of: $VALID_PLATFORMS")
            )
        }

        val sort = call.request.queryParameters["sort"] ?: "relevance"
        if (sort !in VALID_SORTS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid sort. Must be one of: $VALID_SORTS")
            )
        }

        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        // Try Meilisearch first, fall back to Postgres FTS
        try {
            val result = meilisearch.search(
                query = query,
                platform = platform,
                sort = sort,
                limit = limit,
                offset = offset,
            )

            var items = result.hits.map { it.toRepoResponse() }
            var totalHits = result.estimatedTotalHits
            var source = "meilisearch"
            var passthroughAttempted = false

            // On-demand: if few results, also search GitHub and ingest
            if (items.size < ON_DEMAND_THRESHOLD && offset == 0) {
                passthroughAttempted = true
                val githubResults = githubSearch.searchAndIngest(query, platform, limit = 10, userToken = userToken)
                if (githubResults.isNotEmpty()) {
                    // Merge and re-sort by stars descending. Without this, a
                    // freshly-ingested megaproject lands below a 50-star indexed
                    // repo just because passthrough hits are appended after Meili's.
                    // Stars gives a consistent popularity tier across both sources
                    // (passthrough items haven't earned a search_score yet).
                    val existingIds = items.map { it.id }.toSet()
                    val newItems = githubResults.filter { it.id !in existingIds }
                    items = (items + newItems).sortedByDescending { it.stargazersCount }
                    totalHits = items.size
                    source = if (items.size > result.hits.size) "meilisearch+github" else "meilisearch"
                }
            }

            // Log near-misses too — queries with 1-4 results are tractable training
            // candidates; the worker prioritizes zero-result rows via result_count.
            if (items.size < ON_DEMAND_THRESHOLD) {
                searchMissRepository.logMiss(query, resultCount = items.size)
            }

            if (source == "meilisearch+github") {
                metrics.recordPassthrough(items.size, result.processingTimeMs)
            } else {
                metrics.recordMeiliOnly(items.size, result.processingTimeMs)
            }

            call.response.header(HttpHeaders.CacheControl, "public, max-age=15, s-maxage=30")
            call.respond(SearchResponse(
                items = items,
                totalHits = totalHits,
                processingTimeMs = result.processingTimeMs,
                source = source,
                passthroughAttempted = passthroughAttempted,
            ))
        } catch (e: Exception) {
            // Meilisearch down — fall back to Postgres FTS
            call.application.environment.log.warn("Meilisearch unavailable, falling back to Postgres FTS", e)

            val startTime = System.currentTimeMillis()
            val items = searchRepository.search(
                query = query,
                platform = platform,
                sort = sort,
                limit = limit,
                offset = offset,
            )
            val elapsed = (System.currentTimeMillis() - startTime).toInt()

            metrics.recordPostgresFallback(items.size, elapsed)
            call.respond(SearchResponse(
                items = items,
                totalHits = items.size,
                processingTimeMs = elapsed,
                source = "postgres",
            ))
        }
    }

    get("/search/explore") {
        val query = call.request.queryParameters["q"]
        if (query.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing query parameter 'q'")
            )
        }

        val platform = call.request.queryParameters["platform"]
        if (platform != null && platform !in VALID_PLATFORMS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid platform. Must be one of: $VALID_PLATFORMS")
            )
        }

        val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, 10)
        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        val startTime = System.currentTimeMillis()
        val result = githubSearch.explore(query, platform, page, userToken = userToken)
        val elapsed = (System.currentTimeMillis() - startTime).toInt()
        metrics.recordExplore(result.items.size, elapsed)

        // Explore is idempotent for (q, platform, page) so edge caching 60s saves
        // real GitHub API work on hot queries. Shorter browser cache (30s) since
        // the index is updating underneath us.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=30, s-maxage=60")
        call.respond(ExploreResponse(
            items = result.items,
            page = page,
            hasMore = result.hasMore,
        ))
    }
}

private fun zed.rainxch.githubstore.db.MeiliRepoHit.toRepoResponse() = RepoResponse(
    id = id,
    name = name,
    fullName = full_name,
    owner = RepoOwner(login = owner, avatarUrl = owner_avatar_url),
    description = description,
    defaultBranch = default_branch,
    htmlUrl = html_url,
    stargazersCount = stars,
    forksCount = forks,
    openIssuesCount = open_issues,
    language = language,
    topics = topics,
    releasesUrl = "$html_url/releases",
    updatedAt = null,
    createdAt = null,
    latestReleaseDate = latest_release_date,
    latestReleaseTag = latest_release_tag,
    downloadCount = download_count,
    hasInstallersAndroid = has_installers_android,
    hasInstallersWindows = has_installers_windows,
    hasInstallersMacos = has_installers_macos,
    hasInstallersLinux = has_installers_linux,
    trendingScore = trending_score,
    popularityScore = popularity_score,
)
