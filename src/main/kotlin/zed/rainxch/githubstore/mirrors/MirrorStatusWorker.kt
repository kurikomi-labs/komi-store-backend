package zed.rainxch.githubstore.mirrors

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

// Hourly probe of every MirrorPreset. Updates MirrorStatusRegistry; route
// reads from the registry, never from the worker. Same advisory-lock +
// WorkerSupervisor pattern as SignalAggregationWorker / RetentionWorker.
//
// Probes use GET with Range: bytes=0-0 instead of HEAD because community
// mirrors derived from hunshcn/gh-proxy have inconsistent HEAD support;
// Range with a 1-byte window is universally accepted and gives the same
// liveness signal at near-zero transfer cost.
//
// Kill switch: env MIRRORS_PROBE_DISABLED=true exits the loop on next tick.
// Useful for freezing status while debugging without redeploying.
class MirrorStatusWorker(
    private val registry: MirrorStatusRegistry,
    private val supervisor: WorkerSupervisor? = null,
) {
    private val log = LoggerFactory.getLogger(MirrorStatusWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cycleInterval = 1.hours
    private val startupDelay = 30.seconds
    private val perPingTimeoutMs = 5_000L
    private val advisoryLockId: Long = 911_005L

    // Latency thresholds for status classification. Tuned for "user perception
    // from China" -- 1.5s feels fine on mobile, 5s feels broken.
    private val okLatencyCeilingMs = 1_500L
    private val degradedLatencyCeilingMs = 5_000L

    // Lazy so the CIO engine doesn't spawn non-daemon selector threads at
    // class init — test code that constructs the worker for unit checks
    // never triggers init.
    private val http: HttpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = perPingTimeoutMs
                connectTimeoutMillis = perPingTimeoutMs
                socketTimeoutMillis = perPingTimeoutMs
            }
            expectSuccess = false
        }
    }

    fun start(): Job = scope.launch {
        delay(startupDelay)
        while (true) {
            if (probeDisabled()) {
                log.info("MirrorStatus worker: MIRRORS_PROBE_DISABLED=true, sleeping cycle")
            } else {
                try {
                    if (tryRunCycle()) supervisor?.recordTick(WORKER_NAME)
                } catch (e: Exception) {
                    log.error("MirrorStatus cycle failed", e)
                }
            }
            delay(cycleInterval)
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    private fun probeDisabled(): Boolean =
        System.getenv("MIRRORS_PROBE_DISABLED")?.equals("true", ignoreCase = true) == true

    private suspend fun tryRunCycle(): Boolean {
        if (!acquireAdvisoryLock()) {
            log.info("MirrorStatus skipped: advisory lock held by another instance")
            return false
        }
        try {
            runCycle()
            return true
        } finally {
            releaseAdvisoryLock()
        }
    }

    suspend fun runCycle() {
        val results = coroutineScope {
            MirrorPresets.ALL.map { preset ->
                async { preset to pingOne(preset) }
            }.awaitAll()
        }

        var ok = 0; var degraded = 0; var down = 0
        for ((preset, ping) in results) {
            registry.update(preset.id, ping.status, ping.latencyMs)
            when (ping.status) {
                MirrorStatus.OK -> ok++
                MirrorStatus.DEGRADED -> degraded++
                MirrorStatus.DOWN -> down++
                MirrorStatus.UNKNOWN -> Unit
            }
        }

        log.info(
            "MirrorStatus cycle: ok={} degraded={} down={} (of {})",
            ok, degraded, down, results.size,
        )
    }

    private data class PingResult(val status: MirrorStatus, val latencyMs: Long?)

    private suspend fun pingOne(preset: MirrorPreset): PingResult {
        val start = System.currentTimeMillis()
        return try {
            val response: HttpResponse = http.get(preset.pingUrl) {
                header(HttpHeaders.Range, "bytes=0-0")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (MirrorStatus)")
                header(HttpHeaders.Accept, "*/*")
            }
            val elapsed = System.currentTimeMillis() - start
            val status = when {
                !response.status.isSuccess() -> MirrorStatus.DOWN
                elapsed <= okLatencyCeilingMs -> MirrorStatus.OK
                elapsed <= degradedLatencyCeilingMs -> MirrorStatus.DEGRADED
                else -> MirrorStatus.DEGRADED
            }
            PingResult(status, elapsed)
        } catch (_: Exception) {
            // Timeout / DNS failure / TLS error / etc -- all collapse to DOWN.
            // No Sentry: mirror reachability flapping is expected, not exceptional.
            PingResult(MirrorStatus.DOWN, null)
        }
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
            log.warn("MirrorStatus advisory unlock failed: {}", e.message)
        }
    }

    private companion object {
        const val WORKER_NAME = "mirror_status"
    }
}
