package zed.rainxch.githubstore.match

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// Daily ingester that pulls the F-Droid index and seeds (certificate, owner, repo)
// rows into the signing_fingerprint table. F-Droid's index ships fingerprint +
// source-code URL for every package; we filter to GitHub-hosted URLs and skip
// the rest. The /v1/external-match fingerprint strategy and the /v1/signing-seeds
// dump both read this table.
//
// Cadence: every 24h. Initial run on startup if the table is empty (avoids a
// fresh deploy waiting up to 24h for the first sync).
class FdroidSeedWorker(
    private val signingFingerprintRepository: SigningFingerprintRepository,
    private val supervisor: WorkerSupervisor? = null,
) {

    private val log = LoggerFactory.getLogger(FdroidSeedWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cycleInterval = 24.hours
    private val initialDelay = 2.minutes

    private val advisoryLockId: Long = 911_004L

    // Lazy so the CIO engine doesn't spawn non-daemon selector threads at
    // class init. Test code that exercises parseIndexUrlsEnv etc. never
    // triggers init; the worker's first crawl after `start()` does.
    private val http: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 60_000
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
        }
    }

    fun start(): Job = scope.launch {
        delay(initialDelay)
        try {
            if (signingFingerprintRepository.isEmpty()) {
                log.info("FdroidSeedWorker: signing_fingerprint table empty, running initial seed")
                if (tryRunCycle()) supervisor?.recordTick(WORKER_NAME)
            }
        } catch (e: Exception) {
            log.warn("FdroidSeedWorker: failed initial empty-check; continuing on the cycle schedule", e)
        }
        while (true) {
            delay(cycleInterval)
            try {
                if (tryRunCycle()) supervisor?.recordTick(WORKER_NAME)
            } catch (e: Exception) {
                log.error("FdroidSeed cycle failed", e)
                Sentry.captureException(e)
            }
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    private suspend fun tryRunCycle(): Boolean {
        if (!acquireAdvisoryLock()) {
            log.info("FdroidSeed skipped: advisory lock held by another instance")
            return false
        }
        try {
            runOnce()
            return true
        } finally {
            releaseAdvisoryLock()
        }
    }

    suspend fun runOnce() {
        val rows = fetchAndExtract()
        if (rows.isEmpty()) {
            log.warn("FdroidSeedWorker: extracted zero rows; skipping upsert")
            return
        }
        signingFingerprintRepository.upsertBatch(rows)
        log.info("FdroidSeedWorker: upserted {} signing-fingerprint rows", rows.size)
    }

    private fun acquireAdvisoryLock(): Boolean = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
            ps.setLong(1, advisoryLockId)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }

    private fun releaseAdvisoryLock() {
        try {
            transaction {
                val conn = TransactionManager.current().connection.connection as java.sql.Connection
                conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                    ps.setLong(1, advisoryLockId)
                    ps.execute()
                }
            }
        } catch (e: Exception) {
            log.warn("FdroidSeed advisory unlock failed: {}", e.message)
        }
    }

    /**
     * Fetches F-Droid's index-v2.json and extracts (fingerprint, owner, repo,
     * observedAt) tuples for every package whose source-code URL is a github.com
     * repo. Skips entries with malformed certs or non-GitHub source URLs.
     */
    private suspend fun fetchAndExtract(): List<SigningSeedRow> {
        val resp = http.get(FDROID_INDEX_URL) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (FdroidSeedWorker)")
        }
        if (!resp.status.isSuccess()) {
            log.warn("FdroidSeedWorker: F-Droid index responded {}", resp.status.value)
            return emptyList()
        }

        val root: JsonElement = resp.body()
        val packages = root.jsonObject["packages"]?.jsonObject ?: run {
            log.warn("FdroidSeedWorker: F-Droid index missing 'packages' object")
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val out = mutableListOf<SigningSeedRow>()

        for ((_, pkgEl) in packages) {
            val pkg = pkgEl.jsonObject
            val metadata = pkg["metadata"]?.jsonObject ?: continue
            val sourceCode = metadata["sourceCode"]?.toLocalized() ?: continue
            val (owner, repo) = parseGithubUrl(sourceCode) ?: continue

            // Prefer the per-version signer hashes (these are the actual cert
            // SHA-256s that ship in releases). Fall back to preferredSigner.
            val fingerprints = extractFingerprints(pkg)
            for (fp in fingerprints) {
                out += SigningSeedRow(
                    fingerprint = formatColonHex(fp),
                    owner = owner,
                    repo = repo,
                    observedAt = now,
                )
            }
        }

        return out.distinctBy { Triple(it.fingerprint, it.owner, it.repo) }
    }

    private fun extractFingerprints(pkg: JsonObject): Set<String> {
        val out = mutableSetOf<String>()
        // 1. preferredSigner is a top-level uppercase hex string.
        pkg["preferredSigner"]?.jsonPrimitive?.contentOrNull?.let { out += it }
        // 2. versions[*].manifest.signer.sha256[*]
        pkg["versions"]?.jsonObject?.values?.forEach { ver ->
            val sha = ver.jsonObject["manifest"]?.jsonObject
                ?.get("signer")?.jsonObject
                ?.get("sha256")?.jsonArray
            sha?.forEach { entry ->
                entry.jsonPrimitive.contentOrNull?.let { out += it }
            }
        }
        // F-Droid sometimes ships these as lowercase or with mixed length --
        // filter to canonical 64-char hex only.
        return out.filter { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
            .toSet()
    }

    /**
     * F-Droid's metadata strings are localized:
     *   "sourceCode": { "en-US": "https://github.com/...", "de": "..." }
     * but sometimes also a bare string for legacy entries. Pick any non-empty
     * value -- they're URLs, not display text, so locale doesn't matter.
     */
    private fun JsonElement.toLocalized(): String? {
        return when (this) {
            is JsonObject -> values.firstNotNullOfOrNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            else -> jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseGithubUrl(url: String): Pair<String, String>? = GithubSourceUrl.parse(url)

    /** AB:CD:EF:... 32 octets. F-Droid hashes are 64 hex chars; insert colons. */
    private fun formatColonHex(hex: String): String =
        hex.uppercase().chunked(2).joinToString(":")

    private companion object {
        const val FDROID_INDEX_URL = "https://f-droid.org/repo/index-v2.json"
        const val WORKER_NAME = "fdroid_seed"
    }
}
