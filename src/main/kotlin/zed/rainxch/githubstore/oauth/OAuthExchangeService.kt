package zed.rainxch.githubstore.oauth

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Server-side half of the GitHub OAuth web flow. Holds the client_secret —
// the whole point of having a backend in the mix. Client never sees it.
open class OAuthExchangeService(
    private val clientId: String,
    private val clientSecret: String,
    private val callbackUrl: String,
) {

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
        // Read GitHub's error bodies verbatim so we can forward the upstream
        // error code; otherwise ktor would throw on non-2xx.
        expectSuccess = false
    }

    private val responseJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Exchange GitHub authorization code for an access token.
     *
     * Returns one of:
     *   - Success(accessToken)            — GitHub returned 2xx with a token
     *   - UpstreamError(errorCode)        — GitHub returned 2xx with `error` field
     *   - UpstreamFailure                  — GitHub 5xx / timeout / network error
     *
     * The handler maps Success → mint handoff, UpstreamError → 400 with the
     * upstream error code, UpstreamFailure → 502.
     */
    open suspend fun exchange(code: String): Result {
        val response = try {
            http.post("https://github.com/login/oauth/access_token") {
                accept(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (OAuthExchange)")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("code", code)
                    append("redirect_uri", callbackUrl)
                }))
            }
        } catch (_: Exception) {
            return Result.UpstreamFailure
        }

        if (!response.status.isSuccess()) return Result.UpstreamFailure
        val body = try {
            response.bodyAsText()
        } catch (_: Exception) {
            return Result.UpstreamFailure
        }

        val parsed = try {
            responseJson.decodeFromString(TokenResponse.serializer(), body)
        } catch (_: Exception) {
            return Result.UpstreamFailure
        }

        return when {
            parsed.access_token != null -> Result.Success(parsed.access_token)
            parsed.error != null -> Result.UpstreamError(parsed.error)
            else -> Result.UpstreamFailure
        }
    }

    // Same DTO trick as AuthRoutes.kt: only declare the fields we need to
    // read. access_token + error are the entire surface; access_token never
    // becomes a logged-or-persisted value beyond the in-memory `Success`
    // wrapper that immediately enters Redis-equivalent storage with a 60s
    // TTL.
    @Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val error: String? = null,
    )

    sealed class Result {
        data class Success(val accessToken: String) : Result()
        data class UpstreamError(val errorCode: String) : Result()
        data object UpstreamFailure : Result()
    }
}
