package zed.rainxch.githubstore.match

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.ingest.GitHubRepo
import zed.rainxch.githubstore.ingest.GitHubRelease
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.util.FeatureFlags

open class ExternalMatchService(
    private val signingFingerprintRepository: SigningFingerprintRepository,
    private val cache: ResourceCacheRepository,
    // Shares the rotation pool with GitHubSearchClient so external-match
    // upstream calls don't independently exhaust GitHub's anonymous limit
    // (60/hr per source IP — our VPS IP) and respect the quiet-window
    // guarantee for the daily Python fetcher.
    private val searchClient: GitHubSearchClient,
    // Forge fan-out client. Stays anonymous to Codeberg / Gitea / etc.
    // Centralizes the previously per-user Forgejo fanout the client did
    // before the multi-source extension shipped.
    private val forgejoSearchClient: ForgejoSearchClient = ForgejoSearchClient(),
) {
    private val log = LoggerFactory.getLogger(ExternalMatchService::class.java)

    // Lazy so the CIO engine doesn't spawn non-daemon selector threads at
    // class init — tests that override matchOne / drive the service through
    // fakes never touch this property, so the JVM exits cleanly at test
    // end instead of hanging until the test-task timeout fires.
    private val http: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GitHubSearchResults(val items: List<GitHubRepo> = emptyList())

    /**
     * Match a single candidate. Returns up to 5 ranked candidates per spec.
     *
     * Strategy priority (first hit wins for non-search paths):
     *   1. Manifest hint present  → GET /repos/{owner}/{repo} → confidence 1.0
     *   2. Signing fingerprint    → DB lookup                  → confidence 0.92
     *   3. Search                 → score top hits             → confidence ≤ 0.85
     *
     * Strategies 1 and 2 are exclusive: if either returns a match, search is
     * NOT performed. Strategy 3 only runs when neither produced a hit.
     *
     * `sources` selects which catalogs the search step fans out to. The
     * sentinel `"github"` runs the existing GitHub `/search/repositories`
     * path; other short names map to forge hosts via
     * `ForgejoSearchClient.SOURCE_TO_HOST`. Strategies 1 and 2 stay
     * GitHub-only for now — manifest hints don't carry a host, and the
     * forge fingerprint DB is the Tier-1 follow-up task (3.2 in the
     * forges-integration brief).
     */
    open suspend fun matchOne(
        req: ExternalMatchCandidateRequest,
        sources: List<String> = DEFAULT_SOURCES,
    ): List<ExternalMatchCandidate> {
        if (FeatureFlags.disableLiveGitHubPassthrough) {
            // Cached results may still serve via the search path's cache;
            // for live HTTP we drop to fingerprint-only.
            return matchByFingerprint(req)
        }

        // 1. Manifest hint (GitHub-only — hints don't carry a forge host
        //    today; revisit when the client starts emitting per-host hints).
        val manifestMatch = req.manifestHint?.let { hint ->
            val owner = hint.owner?.takeIf { it.isNotBlank() }
            val repo = hint.repo?.takeIf { it.isNotBlank() }
            if (owner != null && repo != null) validateManifestHint(owner, repo) else null
        }
        if (manifestMatch != null) return listOf(manifestMatch)

        // 2. Fingerprint (GitHub-only DB today; Forgejo extension = task 3.2).
        val fingerprintMatches = matchByFingerprint(req)
        if (fingerprintMatches.isNotEmpty()) return fingerprintMatches

        // 3. Search (cache by (packageName, appLabel, sources) per spec —
        // fingerprint is intentionally excluded from the key so a returning
        // user with a different fingerprint gets a fresh look-up; sources IS
        // part of the key because different fan-out sets legitimately
        // produce different result sets).
        val cacheKey = cacheKey(req.packageName, req.appLabel, sources)
        val existing = cache.get(cacheKey)
        if (existing != null && existing.isFresh() && existing.status == 200) {
            runCatching {
                return json.decodeFromString<List<ExternalMatchCandidate>>(existing.body)
            }.onFailure { log.warn("Cached external-match payload failed to decode; refetching") }
        }

        val searchMatches = searchAndScoreAcrossSources(req, sources)
        runCatching {
            cache.put(
                key = cacheKey,
                body = json.encodeToString(searchMatches),
                etag = null,
                status = 200,
                contentType = "application/json",
                ttlSeconds = CACHE_TTL_SECONDS,
            )
        }.onFailure { log.warn("Failed to cache external-match result: {}", it.message) }
        return searchMatches
    }

    private suspend fun matchByFingerprint(req: ExternalMatchCandidateRequest): List<ExternalMatchCandidate> {
        val fp = req.signingFingerprint?.takeIf { it.isNotBlank() } ?: return emptyList()
        val rows = signingFingerprintRepository.lookup(fp)
        if (rows.isEmpty()) return emptyList()

        // Cross-forge dedup (brief 3.6) via the fingerprint signal. When the
        // SAME signing cert is observed against the same (owner, repo) on
        // multiple hosts, those rows describe a mirrored release of one
        // logical app — collapse them into a single candidate so the client
        // doesn't render the same row twice. `available_on` lists every host
        // that carried the cert; the canonical sourceHost is the first hit
        // (github.com surfaces as null per the pre-V17 wire convention).
        return rows
            .groupBy { it.owner.lowercase() to it.repo.lowercase() }
            .map { (_, group) ->
                val hosts = group.map { it.host }.distinct()
                // Deterministic canonical: prefer github.com when present so
                // pre-V17 clients keep seeing source_host=null (wire-compat),
                // then fall back to the alphabetically-lowest host on
                // forge-only mirrors. `group.first()` was non-deterministic
                // — DB row order flap on table compaction would surface a
                // different `source_host` for the same fingerprint between
                // deploys.
                val canonical = group.firstOrNull { it.host == "github.com" }
                    ?: group.minBy { it.host }
                ExternalMatchCandidate(
                    owner = canonical.owner,
                    repo = canonical.repo,
                    confidence = FINGERPRINT_CONFIDENCE,
                    source = "fingerprint",
                    stars = null,
                    description = null,
                    sourceHost = canonical.host.takeIf { it != "github.com" },
                    availableOn = if (hosts.size > 1) hosts.sorted() else emptyList(),
                )
            }
    }

    private suspend fun validateManifestHint(owner: String, repo: String): ExternalMatchCandidate? {
        val token = searchClient.pickFallbackToken()
        val resp = try {
            http.get("https://api.github.com/repos/$owner/$repo") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ExternalMatch)")
                if (token != null) header(HttpHeaders.Authorization, "token $token")
            }
        } catch (e: Exception) {
            log.warn("Manifest hint validation failed for {}/{}: {}", owner, repo, e.message)
            return null
        }
        if (!resp.status.isSuccess()) return null
        val body: GitHubRepo = runCatching { resp.body<GitHubRepo>() }.getOrNull() ?: return null
        return ExternalMatchCandidate(
            owner = owner,
            repo = repo,
            confidence = MANIFEST_CONFIDENCE,
            source = "manifest",
            stars = body.stargazersCount,
            description = body.description,
        )
    }

    // Fan out across `sources` in parallel, merge into a single flat list
    // ranked by confidence. Each source is independent — failure of one
    // (timeout, 429, parse error) does not affect the others. Forge results
    // carry `source = "forgejo_search"` + `sourceHost = "<host>"`; GitHub
    // results stay `source = "search"` + `sourceHost = null` for wire
    // compatibility with pre-1.9.0 clients.
    private suspend fun searchAndScoreAcrossSources(
        req: ExternalMatchCandidateRequest,
        sources: List<String>,
    ): List<ExternalMatchCandidate> = coroutineScope {
        val tasks = sources.distinct().mapNotNull { source ->
            when (val host = ForgejoSearchClient.SOURCE_TO_HOST[source]) {
                null -> if (source == "github") {
                    async { searchAndScore(req) }
                } else {
                    // Unknown source — route layer already rejects these,
                    // so reaching this branch means a misconfigured caller.
                    // Skip rather than fail-the-batch.
                    null
                }
                else -> async { searchAndScoreForgejo(req, host) }
            }
        }
        val merged = tasks.awaitAll().flatten()
        // Cross-source dedup (brief 3.6). When the same (owner, repo) hits
        // both GitHub and a forge, collapse to one row with `available_on`
        // listing both hosts. The kept row is the higher-confidence one;
        // the loser's host is folded into `available_on`. This is the
        // weakest of the three signals the brief lists (name + owner
        // match across hosts) — fingerprint match (handled in
        // matchByFingerprint above) is the strong one, commit-SHA match
        // is the medium one (deferred — needs an extra GET per hit).
        val deduped = merged
            .groupBy { it.owner.lowercase() to it.repo.lowercase() }
            .map { (_, group) ->
                if (group.size == 1) return@map group.first()
                val canonical = group.maxBy { it.confidence }
                val extras = (group - canonical).mapNotNull { it.sourceHost }
                val canonicalHost = canonical.sourceHost ?: "github.com"
                val hosts = (listOf(canonicalHost) + extras).distinct()
                canonical.copy(availableOn = hosts)
            }

        // Re-rank cross-source by confidence descending; cap at 5 total so
        // the response stays bounded regardless of how many forges we fan
        // out to.
        deduped.sortedByDescending { it.confidence }.take(MAX_SUGGESTIONS)
    }

    private suspend fun searchAndScoreForgejo(
        req: ExternalMatchCandidateRequest,
        host: String,
    ): List<ExternalMatchCandidate> {
        // Forgejo / Gitea doesn't parse GitHub-style operators in `q` — embed
        // a `mode=source` parameter instead to filter forks out at the API
        // layer. Without this, "fork:false" used to leak into the free-text
        // search and skewed both the recall and the scorer.
        val hits = forgejoSearchClient.search(host, req.appLabel, mode = "source")
        if (hits.isEmpty()) return emptyList()
        return ExternalMatchScorer
            .rank(req.packageName, req.appLabel, hits, limit = 5)
            .map { (hit, confidence) ->
                ExternalMatchCandidate(
                    owner = hit.owner,
                    repo = hit.repo,
                    confidence = confidence,
                    source = "forgejo_search",
                    stars = hit.stars,
                    description = hit.description,
                    sourceHost = host,
                )
            }
    }

    private suspend fun searchAndScore(req: ExternalMatchCandidateRequest): List<ExternalMatchCandidate> {
        val token = searchClient.pickFallbackToken()
        val items: List<GitHubRepo> = try {
            val response = http.get("https://api.github.com/search/repositories") {
                parameter("q", "${req.appLabel} fork:false")
                // Spec §3.2 says "score top 5 search results" — fetch exactly 5
                // so the per-result asset-probe fan-out matches what we score.
                parameter("per_page", 5)
                parameter("sort", "stars")
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ExternalMatch)")
                if (token != null) header(HttpHeaders.Authorization, "token $token")
            }
            if (!response.status.isSuccess()) {
                emptyList()
            } else {
                val parsed: GitHubSearchResults = response.body()
                parsed.items
            }
        } catch (e: Exception) {
            log.warn("Search query failed for {}: {}", req.packageName, e.message)
            return emptyList()
        }
        if (items.isEmpty()) return emptyList()

        // For each repo, also need to know whether recent releases ship Android
        // assets. Probe in parallel, capped at the same set size.
        val withAssetSignal = coroutineScope {
            items.map { repo ->
                async { repo to hasRecentAndroidAsset(repo.owner.login, repo.name) }
            }.awaitAll()
        }

        val hits = withAssetSignal.map { (repo, hasAsset) ->
            ExternalMatchScorer.SearchHit(
                owner = repo.owner.login,
                repo = repo.name,
                stars = repo.stargazersCount,
                description = repo.description,
                hasAndroidAssetInRecentReleases = hasAsset,
            )
        }

        return ExternalMatchScorer
            .rank(req.packageName, req.appLabel, hits, limit = 5)
            .map { (hit, confidence) ->
                ExternalMatchCandidate(
                    owner = hit.owner,
                    repo = hit.repo,
                    confidence = confidence,
                    source = "search",
                    stars = hit.stars,
                    description = hit.description,
                )
            }
    }

    private suspend fun hasRecentAndroidAsset(owner: String, repo: String): Boolean {
        val token = searchClient.pickFallbackToken()
        val resp = try {
            http.get("https://api.github.com/repos/$owner/$repo/releases?per_page=5") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ExternalMatch)")
                if (token != null) header(HttpHeaders.Authorization, "token $token")
            }
        } catch (_: Exception) {
            return false
        }
        if (!resp.status.isSuccess()) return false
        val releases: List<GitHubRelease> = runCatching {
            resp.body<List<GitHubRelease>>()
        }.getOrNull() ?: return false
        return releases.any { release ->
            release.assets.any { asset ->
                asset.name.endsWith(".apk", ignoreCase = true) ||
                    asset.name.endsWith(".aab", ignoreCase = true)
            }
        }
    }

    private fun cacheKey(packageName: String, appLabel: String, sources: List<String>): String {
        // SHA-256 hash of the canonical (packageName, appLabel, sources)
        // triple. Earlier impl joined with a U+0001 separator, but
        // appLabel is only length-validated at the route layer, not
        // char-class validated, so a hostile client could embed U+0001
        // in appLabel and forge boundaries to collide onto another
        // tuple’s cache slot. Hashing the canonical concatenation
        // makes collisions cryptographically infeasible regardless of
        // the field contents. Prefix kept so ops can grep the namespace.
        val canonical = "$packageName\u0001$appLabel\u0001" +
            sources.distinct().sorted().joinToString(",")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "external-match:$hex"
    }

    companion object {
        // Pre-1.9.0 clients omit the `sources` field; the request DTO
        // defaults to this list so the public default behaviour stays
        // GitHub-only and the wire shape stays byte-identical.
        val DEFAULT_SOURCES: List<String> = listOf("github")
        const val MAX_SUGGESTIONS = 5

        // Internal scoring + TTL constants. Kept inside the same companion
        // object because Kotlin only permits one per class.
        internal const val MANIFEST_CONFIDENCE = 1.0
        internal const val FINGERPRINT_CONFIDENCE = 0.92
        internal const val CACHE_TTL_SECONDS = 24L * 60 * 60
    }
}
