package zed.rainxch.githubstore.ingest

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.util.FeatureFlags
import java.util.concurrent.ConcurrentHashMap

class GitHubResourceClient(
    private val cacheRepository: ResourceCacheRepository,
    // Rotation-pool token selector. Wired from AppModule to GitHubSearchClient's
    // pickFallbackToken so resource-proxy routes (/repo, /releases, /readme,
    // /user) share the same 4-token pool and the same fetcher quiet-window
    // guarantee. Null is allowed for tests; in that case anonymous calls are
    // made when the user doesn't supply X-GitHub-Token.
    private val fallbackTokenProvider: (() -> String?)? = null,
    // Returns true when the daily fetcher's quiet window is active. During
    // that window we never substitute a pool token for a rate-limited user
    // token — the pool belongs to the fetcher then.
    private val isQuietWindow: () -> Boolean = { false },
) {
    private val log = LoggerFactory.getLogger(GitHubResourceClient::class.java)

    // Lazy so the CIO engine + non-daemon selector threads only spawn on
    // first request — tests that wire this client via Koin without driving
    // a real HTTP call avoid leaking the engine into the JVM shutdown path.
    private val http: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    // Collapses concurrent fetches for the same key into one upstream call.
    // Each entry is removed in fetchCached's finally block once the lock is
    // released, so the map stays bounded by in-flight fetches.
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun fetchCached(
        cacheKey: String,
        upstreamUrl: String,
        userToken: String?,
        ttlSeconds: Long,
        negativeTtlSeconds: Long = 900,   // 15 min for 404/451/5xx
        contentType: String = "application/json",
        acceptHeader: String = "application/vnd.github+json",
    ): Result {
        // Segment storage by whether the upstream call carries a user token.
        // Without this, one user's bad-PAT 403 would poison the cache for
        // every subsequent caller (including those with a valid token) until
        // the negative TTL elapsed. The two slots are populated and read
        // independently — anon callers never read an authed cache entry and
        // vice versa.
        val effectiveKey = effectiveCacheKey(cacheKey, userToken)

        // Fresh cache hit — no upstream call, no lock.
        cacheRepository.get(effectiveKey)?.let { existing ->
            if (existing.isFresh()) {
                return if (existing.status in 200..299) {
                    Result.Hit(existing.body, existing.status, existing.contentType)
                } else {
                    Result.NegativeHit(existing.status)
                }
            }
        }

        // Kill switch: serve cached only, never hit upstream.
        if (FeatureFlags.disableLiveGitHubPassthrough) {
            return Result.UpstreamError("passthrough_disabled")
        }

        // Stale or missing — serialize per-key to avoid thundering herd.
        val mutex = fetchLocks.computeIfAbsent(effectiveKey) { Mutex() }
        try {
            return mutex.withLock {
                // Re-check after acquiring: another waiter may have just refreshed it.
                val afterLock = cacheRepository.get(effectiveKey)
                if (afterLock != null && afterLock.isFresh()) {
                    return@withLock if (afterLock.status in 200..299) {
                        Result.Hit(afterLock.body, afterLock.status, afterLock.contentType)
                    } else {
                        Result.NegativeHit(afterLock.status)
                    }
                }

                fetchFromUpstream(
                    cacheKey = effectiveKey,
                    upstreamUrl = upstreamUrl,
                    userToken = userToken,
                    ttlSeconds = ttlSeconds,
                    negativeTtlSeconds = negativeTtlSeconds,
                    contentType = contentType,
                    acceptHeader = acceptHeader,
                    existingEtag = afterLock?.etag,
                    existingBody = afterLock?.body,
                    existingStatus = afterLock?.status,
                    existingContentType = afterLock?.contentType,
                )
            }
        } finally {
            // Reclaim the per-key mutex so the map doesn't grow unbounded.
            // remove(key, value) is a no-op if a concurrent waiter has already
            // installed a new mutex for this key — safe under contention.
            fetchLocks.remove(effectiveKey, mutex)
        }
    }

    private fun effectiveCacheKey(cacheKey: String, userToken: String?): String =
        if (userToken != null) "$cacheKey|authed" else "$cacheKey|anon"

    private suspend fun fetchFromUpstream(
        cacheKey: String,
        upstreamUrl: String,
        userToken: String?,
        ttlSeconds: Long,
        negativeTtlSeconds: Long,
        contentType: String,
        acceptHeader: String,
        existingEtag: String?,
        existingBody: String?,
        existingStatus: Int?,
        existingContentType: String?,
    ): Result {
        val firstToken = userToken ?: fallbackTokenProvider?.invoke()
        val first = attemptUpstream(upstreamUrl, firstToken, acceptHeader, existingEtag)

        // Retry once with the fallback pool if the first attempt was made with
        // a user-supplied token that GitHub rate-limited. Skipped during the
        // fetcher's quiet window so the pool stays free for the daily run —
        // during that window a rate-limited user token is surfaced verbatim.
        // Anonymous-IP rate-limits (no user token) also benefit when a pool
        // token is available outside the quiet window.
        val response = if (
            first is AttemptResult.Limited &&
            !isQuietWindow() &&
            fallbackTokenProvider != null
        ) {
            val poolToken = fallbackTokenProvider.invoke()
            // If pool token equals the token we just used (e.g. user supplied
            // the same PAT, or pool only contains GITHUB_TOKEN), retrying is
            // pointless — the same key is already exhausted.
            if (poolToken != null && poolToken != firstToken) {
                log.info("Retrying rate-limited upstream call with fallback pool: url={}", upstreamUrl)
                when (val retry = attemptUpstream(upstreamUrl, poolToken, acceptHeader, existingEtag)) {
                    is AttemptResult.Ok -> retry.response
                    is AttemptResult.NetworkError -> {
                        if (existingBody != null && existingStatus in 200..299) {
                            return Result.StaleFallback(existingBody, existingStatus!!, existingContentType ?: contentType)
                        }
                        return Result.UpstreamError(retry.message)
                    }
                    is AttemptResult.Limited -> retry.response
                }
            } else first.response
        } else when (first) {
            is AttemptResult.Ok -> first.response
            is AttemptResult.Limited -> first.response
            is AttemptResult.NetworkError -> {
                if (existingBody != null && existingStatus in 200..299) {
                    return Result.StaleFallback(existingBody, existingStatus!!, existingContentType ?: contentType)
                }
                return Result.UpstreamError(first.message)
            }
        }

        val status = response.status.value
        val newEtag = response.headers[HttpHeaders.ETag]

        // 304 Not Modified — body unchanged, just bump TTL and serve cached.
        if (status == 304 && existingBody != null) {
            cacheRepository.refreshTtl(cacheKey, ttlSeconds)
            return Result.Hit(existingBody, existingStatus ?: 200, existingContentType ?: contentType)
        }

        // 200-299: fresh body, cache it.
        if (response.status.isSuccess()) {
            val body = response.bodyAsText()
            cacheRepository.put(
                key = cacheKey,
                body = body,
                etag = newEtag,
                status = status,
                contentType = contentType,
                ttlSeconds = ttlSeconds,
            )
            return Result.Hit(body, status, contentType)
        }

        // 401 from upstream is treated as a transient upstream error rather
        // than a NegativeHit. Two reasons:
        //   1. The 401 reflects a user-token rejection, not a permanent
        //      property of the resource — a user with a valid token would
        //      succeed on the same URL. Caching it (even negatively) would
        //      poison the slot for other tokens.
        //   2. We reserve 401 in our own response surface for future
        //      backend-auth needs; surfacing GitHub's 401 verbatim conflates
        //      "your session with us expired" with "your PAT was rejected
        //      upstream", which the client must distinguish.
        // The route handler already maps UpstreamError -> 502.
        if (status == 401) {
            log.info("Upstream 401: url={} (remapped to 502)", upstreamUrl)
            return Result.UpstreamError("upstream_401")
        }

        // 404 / 410 / 451 / etc. Cache the negative response briefly so a
        // flood of requests for nonexistent repos can't burn our quota.
        if (status in 400..499) {
            cacheRepository.put(
                key = cacheKey,
                body = "",
                etag = null,
                status = status,
                contentType = contentType,
                ttlSeconds = negativeTtlSeconds,
            )
            return Result.NegativeHit(status)
        }

        // 5xx from GitHub — transient, don't cache. Serve stale if we have it.
        log.warn("Upstream 5xx: url={} status={}", upstreamUrl, status)
        if (existingBody != null && existingStatus in 200..299) {
            return Result.StaleFallback(existingBody, existingStatus!!, existingContentType ?: contentType)
        }
        return Result.UpstreamError("upstream $status")
    }

    private suspend fun attemptUpstream(
        upstreamUrl: String,
        token: String?,
        acceptHeader: String,
        existingEtag: String?,
    ): AttemptResult {
        val response = try {
            http.get(upstreamUrl) {
                header(HttpHeaders.Accept, acceptHeader)
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ResourceProxy)")
                if (token != null) header(HttpHeaders.Authorization, "token $token")
                if (existingEtag != null) header(HttpHeaders.IfNoneMatch, existingEtag)
            }
        } catch (e: Exception) {
            log.warn("Upstream fetch failed: url={} err={}", upstreamUrl, e.message)
            return AttemptResult.NetworkError(e.message ?: "unknown")
        }
        return if (isRateLimited(response.status.value, response.headers)) {
            AttemptResult.Limited(response)
        } else {
            AttemptResult.Ok(response)
        }
    }

    // GitHub rate-limit detection. Two surfaces matter here:
    //   * Primary rate limit: 403 with `x-ratelimit-remaining: 0`. Resets
    //     hourly per token (or per IP when anonymous).
    //   * Secondary / abuse rate limit: 429 (newer responses) or 403 with a
    //     `retry-after` header (older responses) — bursts that trip GitHub's
    //     anti-abuse heuristics independent of the hourly budget.
    // A bare 403 without these markers is something else (auth scope, blocked
    // repo) and must NOT trigger a pool retry — the same pool token would hit
    // the same wall.
    private fun isRateLimited(status: Int, headers: io.ktor.http.Headers): Boolean {
        if (status == 429) return true
        if (status == 403) {
            val remaining = headers["x-ratelimit-remaining"]
            if (remaining == "0") return true
            if (headers["retry-after"] != null) return true
        }
        return false
    }

    private sealed class AttemptResult {
        data class Ok(val response: io.ktor.client.statement.HttpResponse) : AttemptResult()
        data class Limited(val response: io.ktor.client.statement.HttpResponse) : AttemptResult()
        data class NetworkError(val message: String) : AttemptResult()
    }

    sealed class Result {
        data class Hit(val body: String, val status: Int, val contentType: String) : Result()
        data class StaleFallback(val body: String, val status: Int, val contentType: String) : Result()
        data class NegativeHit(val status: Int) : Result()
        data class UpstreamError(val message: String) : Result()
    }
}
