package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.SearchMissRepository
import zed.rainxch.githubstore.db.SearchRepository
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.match.ForgejoSearchClient
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry
import zed.rainxch.githubstore.model.ExploreResponse
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.model.SearchResponse
import zed.rainxch.githubstore.topics.TopicCodeMapper

private val VALID_PLATFORMS = setOf("android", "windows", "macos", "linux")
// `recent` kept for back-compat; `releases` is the public-facing alias.
// `updated` mirrors GitHub's repo-level updated_at sort.
private val VALID_SORTS = setOf("relevance", "stars", "recent", "releases", "updated")
// `order` is independent of `sort` so callers can ask for "oldest releases"
// or "fewest stars" without spawning a sort-name explosion. desc matches
// GitHub's default. Ignored when sort=relevance (text-rank is a score, not
// a sortable column).
private val VALID_ORDERS = setOf("asc", "desc")
private const val ON_DEMAND_THRESHOLD = 5

// Multi-source `source` parameter (forges brief 3.5). "github" stays the
// default for byte-identical pre-1.9.0 wire shape. Forge short-names map
// via ForgejoSearchClient.SOURCE_TO_HOST.
//
// `source=all` (cross-source merge of the existing GitHub-Meili path + the
// forge fan-out) is INTENTIONALLY NOT in this set yet. It needs the GitHub
// search branch refactored into a helper that returns SearchResponse so the
// two paths can race in coroutineScope; that's a non-trivial separate PR
// from the forge surface itself. Until then, callers asking for all-source
// get a clear 400 pointing at the per-source variants.
private val VALID_SOURCES = ForgejoSearchClient.SOURCE_TO_HOST.keys

fun Route.searchRoutes(
    meilisearch: MeilisearchClient,
    searchRepository: SearchRepository,
    githubSearch: GitHubSearchClient,
    searchMissRepository: SearchMissRepository,
    metrics: SearchMetricsRegistry,
    forgejoSearchClient: ForgejoSearchClient,
) {
    get("/search") {
        // Empty `q` is allowed when `sort` is anything other than relevance --
        // browse mode for "Recently Updated" / "Recent Releases" home tabs.
        // sort=relevance still requires a query because text-rank needs one.
        val rawQuery = call.request.queryParameters["q"]
        val sort = call.request.queryParameters["sort"] ?: "relevance"
        if (sort !in VALID_SORTS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid sort. Must be one of: $VALID_SORTS")
            )
        }
        val order = (call.request.queryParameters["order"] ?: "desc").lowercase()
        if (order !in VALID_ORDERS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid order. Must be one of: $VALID_ORDERS")
            )
        }
        if ((rawQuery.isNullOrBlank()) && sort == "relevance") {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing query parameter 'q' (required when sort=relevance)")
            )
        }
        val query = rawQuery.orEmpty()

        val platform = call.request.queryParameters["platform"]
        if (platform != null && platform !in VALID_PLATFORMS) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid platform. Must be one of: $VALID_PLATFORMS")
            )
        }

        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        // `source` selects which catalogs to search. "github" (default) runs
        // the existing Meilisearch + GitHub passthrough path unchanged.
        // Forge short-names route to ForgejoSearchClient and return a flat
        // RepoResponse list with source_host populated. "all" fans out to
        // GitHub + every trusted forge in parallel; results merge ranked by
        // stars desc.
        val source = call.request.queryParameters["source"]?.lowercase() ?: "github"
        if (source !in VALID_SOURCES) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid source. Must be one of: ${VALID_SOURCES.sorted()}"),
            )
        }
        // Forge-only short-circuit. Requires a non-empty query (forge search
        // APIs reject blank). Browse-mode sorts (sort=updated etc.) are
        // GitHub-only for now — no equivalent unified index across forges.
        if (source != "github") {
            if (query.isBlank()) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Missing query parameter 'q' (required for forge sources)"),
                )
            }
            val host = ForgejoSearchClient.SOURCE_TO_HOST[source]
                ?: error("VALID_SOURCES drift — $source missing from SOURCE_TO_HOST")
            val startTime = System.currentTimeMillis()
            val hits = forgejoSearchClient.search(host, query, limit = limit)
            val items = hits.map { hit ->
                RepoResponse(
                    id = 0L,
                    name = hit.repo,
                    fullName = "${hit.owner}/${hit.repo}",
                    owner = RepoOwner(login = hit.owner, avatarUrl = null),
                    description = hit.description,
                    defaultBranch = null,
                    htmlUrl = "https://$host/${hit.owner}/${hit.repo}",
                    stargazersCount = hit.stars,
                    forksCount = 0,
                    openIssuesCount = 0,
                    language = null,
                    topics = emptyList(),
                    releasesUrl = "https://$host/${hit.owner}/${hit.repo}/releases",
                    updatedAt = null,
                    createdAt = null,
                    sourceHost = host,
                )
            }
            val elapsed = (System.currentTimeMillis() - startTime).toInt()
            call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
            return@get call.respond(
                SearchResponse(
                    items = items,
                    totalHits = items.size,
                    processingTimeMs = elapsed,
                    source = "forgejo_search",
                ),
            )
        }

        // Every non-relevance sort is served by Postgres FTS (the column for
        // `stars`, `latest_release_date`, `updated_at_gh` lives there and the
        // ORDER BY is unambiguous). Meili would otherwise apply its own
        // relevance-first ranking before the `sort` parameter, producing the
        // exact bug from GitHub-Store#711 where `sort=stars` silently fell
        // back to relevance order because matched-words-and-typo-correction
        // ranking rules outrank `sort` in Meili's default rankingRules chain
        // and the relevance ties dominate the small result sets the issue
        // reproduced. Postgres FTS keeps the text filter (`tsv_search @@
        // plainto_tsquery`) and applies the requested sort as the primary key.
        if (sort != "relevance") {
            val startTime = System.currentTimeMillis()
            val items = searchRepository.search(
                query = query,
                platform = platform,
                sort = sort,
                order = order,
                limit = limit,
                offset = offset,
            )
            val elapsed = (System.currentTimeMillis() - startTime).toInt()
            metrics.recordPostgresFallback(items.size, elapsed)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=15, s-maxage=30")
            return@get call.respond(SearchResponse(
                items = items,
                totalHits = items.size,
                processingTimeMs = elapsed,
                source = "postgres",
            ))
        }

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

            // On-demand passthrough only makes sense for actual text queries.
            // Browse mode (empty q with a non-relevance sort) is a catalog
            // listing -- no GitHub call is appropriate.
            if (query.isNotBlank() && items.size < ON_DEMAND_THRESHOLD && offset == 0) {
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
            // Browse mode has no query to log.
            if (query.isNotBlank() && items.size < ON_DEMAND_THRESHOLD) {
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
                order = order,
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
    licenseSpdxId = license_spdx_id,
    licenseName = license_name,
    license = zed.rainxch.githubstore.db.nestedLicense(license_spdx_id, license_name),
    language = language,
    topics = topics,
    topicCodes = TopicCodeMapper.resolve(topics),
    releasesUrl = "$html_url/releases",
    updatedAt = null,
    createdAt = null,
    pushedAt = pushed_at,
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
