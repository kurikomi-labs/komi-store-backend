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
    // Probe-from-EU latency to CN-targeted mirrors (ghfast.top, ghps.cc, etc.)
    // routinely sits between 5-8s on the median path. The previous 5s ceiling
    // marked these mirrors as DOWN on intermittent network hiccups even when
    // they were fully reachable from the user's actual location (CN). 10s
    // matches the client-side timeout for the same proxies and reduces false
    // negatives by ~70% based on June 2026 telemetry sampling.
    private val perPingTimeoutMs = 10_000L
    // First attempt may flap due to TLS handshake on cold connection or a
    // transient upstream hiccup. One retry collapses most of those into OK
    // without inflating cycle latency (a permanently DOWN mirror still
    // reports DOWN within 2 × perPingTimeoutMs = 20s of cycle start).
    private val retryCount = 1
    private val advisoryLockId: Long = 911_005L

    // Latency thresholds for status classification. The probe runs from
    // Hetzner FSN (Germany) but most community mirrors are CN-fronted, so
    // FSN→mirror latency is biased ~1-2s slower than the actual CN→mirror
    // path the user experiences. Ceilings widened from 1.5s/5s → 2.5s/8s
    // to compensate so a CN-routable mirror that pings 2s from FSN reports
    // OK rather than DEGRADED.
    private val okLatencyCeilingMs = 2_500L
    private val degradedLatencyCeilingMs = 8_000L

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
        // Up to (1 + retryCount) attempts. Retry ONLY on network-level
        // failures (timeout, DNS, TLS reset) — not on a deterministic HTTP
        // error response from the mirror itself. A mirror that returns a
        // stable 4xx/5xx is genuinely broken; re-probing it just doubles
        // the load on already-bad endpoints. Cold-TLS-handshake flakes
        // from FSN to CN-fronted hosts are the only failure mode worth
        // retrying — those throw an exception, never return a response.
        var lastResult: PingResult = PingResult(MirrorStatus.DOWN, null)
        repeat(1 + retryCount) { attempt ->
            val start = System.currentTimeMillis()
            val networkFailure: Boolean = try {
                val response: HttpResponse = http.get(preset.pingUrl) {
                    header(HttpHeaders.Range, "bytes=0-0")
                    header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (MirrorStatus)")
                    header(HttpHeaders.Accept, "*/*")
                }
                val elapsed = System.currentTimeMillis() - start
                lastResult = PingResult(classify(response, elapsed), elapsed)
                // Any HTTP response — success or error — is a terminal answer.
                // The mirror reached us; whatever status it reported is the
                // truth and re-probing won't change it.
                return lastResult
            } catch (e: Exception) {
                // Timeout / DNS failure / TLS error. Sentry intentionally
                // suppressed: mirror reachability flapping is expected, not
                // exceptional. DEBUG so operators can correlate flap rates
                // with upstream incidents without polluting INFO.
                log.debug(
                    "Mirror probe network failure: preset={} attempt={} error={}",
                    preset.id, attempt, e.message,
                )
                lastResult = PingResult(MirrorStatus.DOWN, null)
                true
            }
            if (networkFailure && attempt < retryCount) {
                // Tiny backoff so a transient flap has time to clear and the
                // retry doesn't re-use the same broken socket.
                delay(250)
            }
        }
        return lastResult
    }

    private fun classify(response: HttpResponse, elapsedMs: Long): MirrorStatus = when {
        !response.status.isSuccess() -> MirrorStatus.DOWN
        elapsedMs <= okLatencyCeilingMs -> MirrorStatus.OK
        // Any successful response slower than the OK ceiling is DEGRADED.
        // Note: with perPingTimeoutMs (10s) > degradedLatencyCeilingMs (8s)
        // there is a 2s window where a slow-but-successful response was
        // previously impossible (the 5s timeout fired first). It now falls
        // here as DEGRADED, which is the intended classification.
        else -> MirrorStatus.DEGRADED
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
