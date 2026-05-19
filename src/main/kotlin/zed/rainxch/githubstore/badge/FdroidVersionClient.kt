package zed.rainxch.githubstore.badge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class FdroidVersionClient(private val packageId: String) {

    private val log = LoggerFactory.getLogger(FdroidVersionClient::class.java)

    // Lazy so the CIO engine + non-daemon selector threads only spawn on
    // first version-resolve call (degraded-path fallback for the F-Droid
    // badge). Tests that instantiate the client for DI never trigger init.
    private val http: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
            expectSuccess = false
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class FdroidPackageEntry(
        val versionName: String? = null,
        val versionCode: Long? = null,
    )

    @Serializable
    private data class FdroidPackageResponse(
        val packageName: String? = null,
        val suggestedVersionCode: Long? = null,
        val packages: List<FdroidPackageEntry> = emptyList(),
    )

    suspend fun latestVersionName(): String {
        val url = "https://f-droid.org/api/v1/packages/$packageId"
        val response = http.get(url) {
            header("User-Agent", "GithubStoreBackend/1.0 (BadgeService)")
            header("Accept", "application/json")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("F-Droid responded ${response.status.value}")
        }
        val body = response.bodyAsText()
        val parsed = json.decodeFromString(FdroidPackageResponse.serializer(), body)

        // Prefer the package whose versionCode matches suggestedVersionCode;
        // fall back to the first entry if there's no match (older index format).
        val suggested = parsed.suggestedVersionCode?.let { code ->
            parsed.packages.firstOrNull { it.versionCode == code }
        }
        val pick = suggested ?: parsed.packages.firstOrNull()
        return pick?.versionName?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("F-Droid returned no usable versionName for $packageId")
    }
}
