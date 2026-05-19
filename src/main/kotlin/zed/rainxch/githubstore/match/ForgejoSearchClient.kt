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
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.match.ExternalMatchScorer.SearchHit

// Anonymous Forgejo / Gitea search client. Hits each trusted forge host's
// `/api/v1/repos/search` once per query and translates the response into
// `ExternalMatchScorer.SearchHit` so the existing ranker scores GitHub +
// forge hits with the same model.
//
// Threat surface:
//   - Host allowlist is enforced by the route layer + the constructor's
//     `trustedHosts` set; this class never accepts a user-supplied host.
//   - No PAT forwarding — backend talks to forges anonymously. Codeberg
//     allows anonymous reads on all the endpoints we touch.
//   - Rate-limit awareness: Codeberg's HAProxy returns 429 without
//     `Retry-After` or `X-RateLimit-*` headers. We collapse 429 (and any
//     other non-2xx) to "empty list", log once, and rely on the daily
//     soft ceiling of our own quota since fan-out is centralized here.
//
// 4-second per-call timeout mirrors the client's deleted per-host fanout
// budget so the end-to-end /v1/external-match request stays under the
// existing 8s soft ceiling even when every forge times out.
open class ForgejoSearchClient(
    private val trustedHosts: Set<String> = DEFAULT_TRUSTED_HOSTS,
) {
    private val log = LoggerFactory.getLogger(ForgejoSearchClient::class.java)

    // Lazy so the CIO engine + non-daemon selector threads only spawn when
    // search() is actually called. Test fakes that override `search` never
    // touch this property, so the JVM exits cleanly at test end instead of
    // hanging on the engine's selector pool.
    private val http: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 4_000
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 4_000
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
        }
    }

    fun isTrusted(host: String): Boolean = host in trustedHosts

    /**
     * Search `host`'s repository index for `query`. Returns up to `limit`
     * hits as `SearchHit`s shaped for `ExternalMatchScorer`. Empty list on
     * any failure (timeout, 4xx, 5xx, parse error) — the caller treats an
     * empty list as "no forge match" and the GitHub path runs unaffected.
     */
    open suspend fun search(host: String, query: String, limit: Int = 5): List<SearchHit> {
        if (host !in trustedHosts) {
            // Defence in depth — route layer should have rejected already.
            log.warn("ForgejoSearchClient.search called with untrusted host={}; ignoring", host)
            return emptyList()
        }
        if (query.isBlank()) return emptyList()

        val url = "https://$host/api/v1/repos/search"
        return try {
            val response: HttpResponse = http.get(url) {
                parameter("q", query)
                parameter("limit", limit)
                parameter("sort", "stars")
                parameter("order", "desc")
                parameter("archived", false)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ForgejoSearch)")
            }
            if (!response.status.isSuccess()) {
                log.info("Forgejo search non-2xx host={} status={}", host, response.status.value)
                return emptyList()
            }
            val parsed: ForgejoSearchResults = response.body()
            parsed.data.map { repo ->
                SearchHit(
                    owner = repo.owner.login,
                    repo = repo.name,
                    stars = repo.starsCount,
                    description = repo.description,
                    // Forgejo's `has_releases` is true when ANY release exists,
                    // not specifically Android. Without a per-asset probe we
                    // can't tell APK from tarball — treat `has_releases` as a
                    // weak positive signal so scoring keeps working. False
                    // positives downgrade naturally via the package-name +
                    // app-label similarity components of the scorer.
                    hasAndroidAssetInRecentReleases = repo.hasReleases,
                )
            }
        } catch (e: Exception) {
            log.info("Forgejo search failed host={}: {}", host, e.message)
            emptyList()
        }
    }

    companion object {
        // Default allowlist used when `FORGEJO_TRUSTED_HOSTS` env is unset.
        // Three canonical public forges; matches the client's hardcoded
        // `RepositoryUrlParser` defaults.
        val DEFAULT_TRUSTED_HOSTS: Set<String> = setOf(
            "codeberg.org",
            "gitea.com",
            "git.disroot.org",
        )

        // Map from request `sources` short name → host. `"github"` is the
        // sentinel "no forge call, use the existing GitHub search path".
        val SOURCE_TO_HOST: Map<String, String?> = mapOf(
            "github" to null,
            "codeberg" to "codeberg.org",
            "gitea" to "gitea.com",
            "disroot" to "git.disroot.org",
        )

        fun parseTrustedHostsEnv(raw: String?): Set<String> =
            raw?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_TRUSTED_HOSTS
    }
}
