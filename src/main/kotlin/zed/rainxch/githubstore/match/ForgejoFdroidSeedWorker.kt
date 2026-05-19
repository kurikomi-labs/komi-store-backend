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

// Forgejo-side F-Droid index crawler (brief 3.2 ingest path). Reads each
// configured F-Droid repo's `index-v2.json`, extracts (fingerprint, owner,
// repo) triples for packages whose `sourceCode` URL points at a trusted
// forge host, and seeds them into `signing_fingerprint` with the matching
// `host` column populated.
//
// Why a separate worker from FdroidSeedWorker:
//   1. Different upstream URLs (operator-configurable via env), different
//      cadence guarantees, and an independent advisory-lock id so the two
//      can run in the same hour without contending.
//   2. The GitHub side reads f-droid.org's primary index, which is the
//      ground truth for GitHub-distributed apps. Forge-distributed apps
//      typically ship from a project-hosted F-Droid repo (Gadgetbridge's
//      Codeberg pages repo, etc.) that f-droid.org doesn't mirror.
//   3. Keeping the upstream-URL set in env (FORGEJO_FDROID_INDEX_URLS)
//      means operators can add a new project-hosted F-Droid repo with a
//      config flip, no code change.
//
// Idempotent: signing_fingerprint's PK is (host, fingerprint, owner, repo)
// post-V17, so re-runs are no-ops on already-seen tuples.
class ForgejoFdroidSeedWorker(
    private val signingFingerprintRepository: SigningFingerprintRepository,
    private val trustedHosts: Set<String> = ForgejoSearchClient.DEFAULT_TRUSTED_HOSTS,
    private val indexUrls: List<String> = DEFAULT_INDEX_URLS,
    private val supervisor: WorkerSupervisor? = null,
) {

    private val log = LoggerFactory.getLogger(ForgejoFdroidSeedWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cycleInterval = 24.hours
    private val initialDelay = 3.minutes // staggered after FdroidSeedWorker's 2min

    // Distinct from FdroidSeedWorker's 911_004 so the two seed workers can
    // run in the same hour without serialising on a shared lock.
    private val advisoryLockId: Long = 911_006L

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        expectSuccess = false
    }

    fun start(): Job = scope.launch {
        if (indexUrls.isEmpty()) {
            log.info("ForgejoFdroidSeedWorker disabled: indexUrls empty")
            return@launch
        }
        delay(initialDelay)
        while (true) {
            try {
                if (tryRunCycle()) supervisor?.recordTick(WORKER_NAME)
            } catch (e: Exception) {
                log.error("ForgejoFdroidSeed cycle failed", e)
                Sentry.captureException(e)
            }
            delay(cycleInterval)
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    private suspend fun tryRunCycle(): Boolean {
        if (!acquireAdvisoryLock()) {
            log.info("ForgejoFdroidSeed skipped: advisory lock held by another instance")
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
        val allRows = mutableListOf<SigningSeedRow>()
        for (url in indexUrls) {
            val rows = runCatching { fetchAndExtract(url) }
                .onFailure { log.warn("ForgejoFdroidSeed: index {} failed: {}", url, it.message) }
                .getOrDefault(emptyList())
            if (rows.isNotEmpty()) {
                log.info("ForgejoFdroidSeed: extracted {} rows from {}", rows.size, url)
            }
            allRows += rows
        }
        if (allRows.isEmpty()) {
            log.warn("ForgejoFdroidSeed: extracted zero rows across {} indexes", indexUrls.size)
            return
        }
        // Cross-index dedup before upsert. PK collision would be a no-op
        // anyway, but cutting duplicates in-memory saves Postgres round-trips.
        val deduped = allRows.distinctBy { Quad(it.host, it.fingerprint, it.owner, it.repo) }
        signingFingerprintRepository.upsertBatch(deduped)
        log.info("ForgejoFdroidSeed: upserted {} forge signing-fingerprint rows", deduped.size)
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
            log.warn("ForgejoFdroidSeed advisory unlock failed: {}", e.message)
        }
    }

    /**
     * Pull one F-Droid index-v2.json. Extracts (fingerprint, owner, repo)
     * triples for packages whose `sourceCode` URL parses against the trusted
     * forge allowlist. Each row's `host` is set from the source URL — so the
     * SAME index document can produce rows for multiple forges if the index
     * happens to mix them (rare but cheap to handle correctly).
     */
    internal suspend fun fetchAndExtract(indexUrl: String): List<SigningSeedRow> {
        val resp = http.get(indexUrl) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (ForgejoFdroidSeed)")
        }
        if (!resp.status.isSuccess()) {
            log.info("ForgejoFdroidSeed: {} responded {}", indexUrl, resp.status.value)
            return emptyList()
        }
        val root: JsonElement = resp.body()
        val packages = root.jsonObject["packages"]?.jsonObject ?: run {
            log.info("ForgejoFdroidSeed: {} missing 'packages' object", indexUrl)
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val out = mutableListOf<SigningSeedRow>()

        for ((_, pkgEl) in packages) {
            val pkg = pkgEl.jsonObject
            val metadata = pkg["metadata"]?.jsonObject ?: continue
            val sourceCode = metadata["sourceCode"]?.toLocalized() ?: continue
            val parsed = ForgeSourceUrl.parse(sourceCode, trustedHosts) ?: continue
            val fingerprints = extractFingerprints(pkg)
            for (fp in fingerprints) {
                out += SigningSeedRow(
                    fingerprint = formatColonHex(fp),
                    owner = parsed.owner,
                    repo = parsed.repo,
                    observedAt = now,
                    host = parsed.host,
                )
            }
        }
        return out
    }

    private fun extractFingerprints(pkg: JsonObject): Set<String> {
        val out = mutableSetOf<String>()
        pkg["preferredSigner"]?.jsonPrimitive?.contentOrNull?.let { out += it }
        pkg["versions"]?.jsonObject?.values?.forEach { ver ->
            val sha = ver.jsonObject["manifest"]?.jsonObject
                ?.get("signer")?.jsonObject
                ?.get("sha256")?.jsonArray
            sha?.forEach { entry ->
                entry.jsonPrimitive.contentOrNull?.let { out += it }
            }
        }
        return out.filter {
            it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }
        }.toSet()
    }

    private fun JsonElement.toLocalized(): String? = when (this) {
        is JsonObject -> values.firstNotNullOfOrNull {
            it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() }
        }
        else -> jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun formatColonHex(hex: String): String =
        hex.uppercase().chunked(2).joinToString(":")

    // Internal-quad helper for in-memory dedup of (host, fingerprint, owner,
    // repo) across multiple index sweeps. Kotlin's stdlib only ships Triple.
    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    companion object {
        const val WORKER_NAME = "forgejo_fdroid_seed"

        // Default index list, narrow on purpose. Operator extends via
        // FORGEJO_FDROID_INDEX_URLS env without a code change. Gadgetbridge
        // ships from `freeyourgadget.codeberg.page/fdroid/repo` and is the
        // canonical forge-distributed app the brief calls out; everything
        // else is opt-in to keep the daily fetch footprint predictable.
        val DEFAULT_INDEX_URLS: List<String> = listOf(
            "https://freeyourgadget.codeberg.page/fdroid/repo/index-v2.json",
        )

        fun parseIndexUrlsEnv(raw: String?): List<String> =
            raw?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && (it.startsWith("https://") || it.startsWith("http://")) }
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_INDEX_URLS
    }
}
