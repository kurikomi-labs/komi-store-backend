package zed.rainxch.githubstore.ingest

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory

// `open` so route tests can swap in a fake client that returns canned
// GitHubDeviceResponse values without touching real HTTP. `clientId` is a
// constructor parameter (defaulted to the env var) for the same reason —
// tests don't need to set GITHUB_OAUTH_CLIENT_ID just to construct an
// override.
open class GitHubDeviceClient(
    private val clientId: String =
        System.getenv("OAUTH_CLIENT_ID")?.takeIf { it.isNotBlank() }
            ?: error(
                "OAUTH_CLIENT_ID env var is required to serve /v1/auth/device/* routes. " +
                    "Set it to the same OAuth App client_id the KMP client has in BuildKonfig. " +
                    "(Renamed from GITHUB_OAUTH_CLIENT_ID — update /opt/github-store-backend/.env to match.)"
            ),
) {
    private val log = LoggerFactory.getLogger(GitHubDeviceClient::class.java)

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
        // Read status manually so we can forward GitHub's error bodies verbatim
        // instead of having ktor throw on non-2xx.
        expectSuccess = false
    }

    open suspend fun startDeviceFlow(): GitHubDeviceResponse =
        proxyCall("https://github.com/login/device/code") {
            append("client_id", clientId)
        }

    open suspend fun pollDeviceToken(deviceCode: String): GitHubDeviceResponse =
        proxyCall("https://github.com/login/oauth/access_token") {
            append("client_id", clientId)
            append("device_code", deviceCode)
            append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        }

    private suspend fun proxyCall(
        url: String,
        body: ParametersBuilder.() -> Unit,
    ): GitHubDeviceResponse {
        val resp = http.post(url) {
            accept(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (DeviceFlow)")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build(body)))
        }
        val status = resp.status
        val text = resp.bodyAsText()
        if (!status.isSuccess()) {
            // Non-2xx bodies from GitHub's device-flow endpoints only contain
            // error codes, never tokens — safe to log for operational visibility.
            log.warn(
                "GitHub device-flow non-2xx: url={} status={} body={}",
                url, status.value, text.take(300),
            )
        }
        return GitHubDeviceResponse(status = status, body = text)
    }
}

data class GitHubDeviceResponse(
    val status: HttpStatusCode,
    val body: String,
)
