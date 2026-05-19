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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.model.RepoLicense
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse

// Anonymous Forgejo / Gitea fetch client for /v1/repo, /v1/releases, and
// other host-keyed proxy paths. Mirrors ForgejoSearchClient's hardening:
//   - Trusted-host allowlist (constructor + per-call defence in depth)
//   - 6-second per-call timeout (slightly more than search's 4s because
//     repo + releases payloads can be larger than search results)
//   - Anonymous reads only — no PAT forwarding
//   - ResourceCacheRepository-backed cache keyed by `forgejo:{host}:{kind}:{owner}/{name}`
//     so GitHub + forge entries with the same `owner/name` don't collide
open class ForgejoResourceClient(
    private val cache: ResourceCacheRepository,
    private val trustedHosts: Set<String> = ForgejoSearchClient.DEFAULT_TRUSTED_HOSTS,
) {
    private val log = LoggerFactory.getLogger(ForgejoResourceClient::class.java)

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 6_000
            connectTimeoutMillis = 4_000
            socketTimeoutMillis = 6_000
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun isTrusted(host: String): Boolean = host in trustedHosts

    /**
     * Fetch repo metadata from a trusted forge host. Returns a unified
     * `RepoResponse` so the client never has to branch on host for the
     * detail-page render. License is sniffed (`/contents/LICENSE` +
     * fallbacks) opportunistically — if the LICENSE call 404s or times
     * out, the response carries null license fields rather than failing.
     *
     * `null` on any failure (timeout, untrusted host, 404). The route
     * layer translates that to a 404 response to the client.
     */
    open suspend fun fetchRepo(host: String, owner: String, name: String): RepoResponse? {
        if (host !in trustedHosts) {
            log.warn("ForgejoResourceClient.fetchRepo called with untrusted host={}", host)
            return null
        }

        val cacheKey = "forgejo:$host:repo:$owner/$name"
        val cached = cache.get(cacheKey)
        if (cached != null && cached.isFresh() && cached.status == 200) {
            runCatching {
                return json.decodeFromString(RepoResponse.serializer(), cached.body)
            }.onFailure { log.info("Forgejo repo cache decode failed for $cacheKey: ${it.message}") }
        }

        val detail = fetchDetail(host, owner, name) ?: return null
        val license = sniffLicense(host, owner, name, detail.defaultBranch)
        val response = detail.toRepoResponse(host = host, license = license)

        runCatching {
            cache.put(
                key = cacheKey,
                body = json.encodeToString(RepoResponse.serializer(), response),
                etag = null,
                status = 200,
                contentType = "application/json",
                ttlSeconds = REPO_TTL_SECONDS,
            )
        }.onFailure { log.warn("Forgejo repo cache put failed: ${it.message}") }
        return response
    }

    /**
     * Fetch a page of releases from a trusted forge host. Returns the
     * upstream JSON body with CRLF endings normalized to LF (Forgejo
     * release bodies often arrive with mixed line endings that break
     * markdown table rendering on the client) — same workaround the
     * client currently does in `DetailsRepositoryImpl.processForgejoBody`,
     * now centralized so each user doesn't re-do the string scan.
     *
     * `null` on any failure. Route translates to 502 or 404 as appropriate.
     *
     * Note: returns raw upstream JSON, NOT a unified release-shape. Field
     * translation (asset.content_type defaults, published_at offset parsing)
     * stays on the client until the unified release DTO ships (deferred).
     */
    open suspend fun fetchReleasesRaw(
        host: String,
        owner: String,
        name: String,
        page: Int,
        perPage: Int,
    ): String? {
        if (host !in trustedHosts) {
            log.warn("ForgejoResourceClient.fetchReleasesRaw untrusted host={}", host)
            return null
        }

        val cacheKey = "forgejo:$host:releases:$owner/$name?page=$page&per_page=$perPage"
        val cached = cache.get(cacheKey)
        if (cached != null && cached.isFresh() && cached.status == 200) return cached.body

        val url = "https://$host/api/v1/repos/$owner/$name/releases"
        return try {
            val resp = http.get(url) {
                parameter("page", page)
                parameter("limit", perPage)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ForgejoResource)")
            }
            if (!resp.status.isSuccess()) {
                log.info("Forgejo releases non-2xx host={} status={}", host, resp.status.value)
                return null
            }
            val raw: String = resp.body()
            // Strip \r from \r\n so markdown table separator rows render
            // correctly on the client. Lone \r (classic Mac newline) is rare
            // enough that we don't bother — replacing only the \r in CRLF
            // pairs preserves any intentional carriage returns in code blocks.
            val normalized = raw.replace("\r\n", "\n")
            runCatching {
                cache.put(
                    key = cacheKey,
                    body = normalized,
                    etag = null,
                    status = 200,
                    contentType = "application/json",
                    ttlSeconds = RELEASES_TTL_SECONDS,
                )
            }
            normalized
        } catch (e: Exception) {
            log.info("Forgejo releases fetch failed host={}: {}", host, e.message)
            null
        }
    }

    private suspend fun fetchDetail(host: String, owner: String, name: String): ForgejoRepoDetail? {
        val url = "https://$host/api/v1/repos/$owner/$name"
        return try {
            val resp: HttpResponse = http.get(url) {
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ForgejoResource)")
            }
            if (!resp.status.isSuccess()) {
                log.info("Forgejo repo detail non-2xx host={} status={}", host, resp.status.value)
                return null
            }
            resp.body<ForgejoRepoDetail>()
        } catch (e: Exception) {
            log.info("Forgejo repo detail failed host={}: {}", host, e.message)
            null
        }
    }

    /**
     * SPDX sniff via the contents endpoint. Forgejo has no /license
     * endpoint and no `license` field on the repo payload — operators
     * detect by reading LICENSE / LICENSE.md / COPYING etc. and grepping
     * the header line. Cached in the same ResourceCacheRepository so
     * repeat lookups don't re-decode base64 across user sessions.
     */
    private suspend fun sniffLicense(host: String, owner: String, name: String, branch: String?): RepoLicense? {
        val ref = branch?.takeIf { it.isNotBlank() } ?: "main"
        for (path in LICENSE_PATHS) {
            val sniffKey = "forgejo:$host:license:$owner/$name/$path@$ref"
            val cached = cache.get(sniffKey)
            if (cached != null && cached.isFresh() && cached.status == 200) {
                runCatching {
                    return json.decodeFromString(RepoLicense.serializer(), cached.body)
                }.onFailure { /* fall through and refetch */ }
            }

            val body = fetchContentText(host, owner, name, path, ref) ?: continue
            val spdx = sniffSpdx(body)
            val license = RepoLicense(spdxId = spdx, name = spdx)
            runCatching {
                cache.put(
                    key = sniffKey,
                    body = json.encodeToString(RepoLicense.serializer(), license),
                    etag = null,
                    status = 200,
                    contentType = "application/json",
                    ttlSeconds = LICENSE_TTL_SECONDS,
                )
            }
            return license
        }
        return null
    }

    private suspend fun fetchContentText(
        host: String,
        owner: String,
        name: String,
        path: String,
        ref: String,
    ): String? {
        val url = "https://$host/api/v1/repos/$owner/$name/contents/$path"
        return try {
            val resp = http.get(url) {
                parameter("ref", ref)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ForgejoResource)")
            }
            if (!resp.status.isSuccess()) return null
            val parsed = resp.body<ForgejoContent>()
            if (parsed.encoding != "base64") return null
            val decoded = runCatching {
                java.util.Base64.getMimeDecoder()
                    .decode(parsed.content.orEmpty())
                    .toString(Charsets.UTF_8)
            }.getOrNull()
            decoded
        } catch (e: Exception) {
            log.info("Forgejo content fetch failed host={} path={}: {}", host, path, e.message)
            null
        }
    }

    companion object {
        // Common LICENSE filenames sniffed in order. Stop at first hit.
        private val LICENSE_PATHS = listOf("LICENSE", "LICENSE.md", "LICENSE.txt", "COPYING", "COPYING.md")

        // Repo metadata barely moves; aggressive cache. Same TTL as the
        // GitHub-side repo lookup uses (24h via the resource_cache table).
        const val REPO_TTL_SECONDS: Long = 24L * 60 * 60

        // License rarely changes for a tagged release; 7d is safe.
        const val LICENSE_TTL_SECONDS: Long = 7L * 24 * 60 * 60

        // Forge releases publish less aggressively than GitHub on busy repos
        // (Codeberg-hosted projects often release weekly, not daily). 1 hour
        // matches the GitHub release-list cache TTL so the dispatch
        // freshness is the same.
        const val RELEASES_TTL_SECONDS: Long = 3600L

        // Pattern from `LICENSE` header lines that name an SPDX-style
        // identifier. Catches the common shapes:
        //   "MIT License"
        //   "Apache License Version 2.0"
        //   "GNU General Public License v3.0"
        //   "Mozilla Public License 2.0"
        // Anything we don't recognise falls through to null, which the
        // client renders as "License: —" — same as a missing LICENSE file.
        private val SPDX_PATTERNS = listOf(
            Regex("\\bMIT\\b") to "MIT",
            Regex("Apache License,?\\s*Version\\s*2\\.0", RegexOption.IGNORE_CASE) to "Apache-2.0",
            Regex("BSD 3[- ]Clause", RegexOption.IGNORE_CASE) to "BSD-3-Clause",
            Regex("BSD 2[- ]Clause", RegexOption.IGNORE_CASE) to "BSD-2-Clause",
            Regex("GNU GENERAL PUBLIC LICENSE.*Version 3", RegexOption.IGNORE_CASE) to "GPL-3.0",
            Regex("GNU GENERAL PUBLIC LICENSE.*Version 2", RegexOption.IGNORE_CASE) to "GPL-2.0",
            Regex("GNU LESSER GENERAL PUBLIC LICENSE.*Version 3", RegexOption.IGNORE_CASE) to "LGPL-3.0",
            Regex("GNU AFFERO GENERAL PUBLIC LICENSE.*Version 3", RegexOption.IGNORE_CASE) to "AGPL-3.0",
            Regex("Mozilla Public License,?\\s*Version\\s*2\\.0", RegexOption.IGNORE_CASE) to "MPL-2.0",
            Regex("\\bISC\\b") to "ISC",
            Regex("UNLICENSE", RegexOption.IGNORE_CASE) to "Unlicense",
        )

        internal fun sniffSpdx(text: String): String? {
            val head = text.lineSequence().take(40).joinToString("\n")
            for ((pattern, spdx) in SPDX_PATTERNS) {
                if (pattern.containsMatchIn(head)) return spdx
            }
            return null
        }
    }
}

@Serializable
internal data class ForgejoRepoDetail(
    val id: Long = 0,
    val name: String,
    @SerialName("full_name") val fullName: String? = null,
    val owner: ForgejoUser,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("stars_count") val starsCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    @SerialName("open_issues_count") val openIssuesCount: Int = 0,
    val language: String? = null,
    val archived: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toRepoResponse(host: String, license: RepoLicense?): RepoResponse = RepoResponse(
        id = id,
        name = name,
        fullName = fullName ?: "${owner.login}/$name",
        owner = RepoOwner(login = owner.login, avatarUrl = null),
        description = description,
        defaultBranch = defaultBranch,
        htmlUrl = htmlUrl ?: "https://$host/${owner.login}/$name",
        stargazersCount = starsCount,
        forksCount = forksCount,
        openIssuesCount = openIssuesCount,
        licenseSpdxId = license?.spdxId,
        licenseName = license?.name,
        license = license,
        language = language,
        topics = emptyList(),
        releasesUrl = (htmlUrl ?: "https://$host/${owner.login}/$name") + "/releases",
        updatedAt = updatedAt,
        createdAt = createdAt,
    )
}

@Serializable
internal data class ForgejoContent(
    val content: String? = null,
    val encoding: String? = null,
)
