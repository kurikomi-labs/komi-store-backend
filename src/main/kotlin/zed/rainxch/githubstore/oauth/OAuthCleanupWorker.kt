package zed.rainxch.githubstore.oauth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import kotlin.time.Duration.Companion.minutes

// Postgres has no native TTL. Every five minutes, DELETE every row whose
// `expires_at` is in the past. Request-time queries already filter by
// `expires_at > NOW()` so the worker missing a tick never serves an expired
// row; the worker is purely about keeping the table small. Single instance
// so no advisory lock; if we go multi-node, add the same advisory-lock
// pattern MirrorStatusWorker uses.
class OAuthCleanupWorker(
    private val store: OAuthEphemeralStore,
    private val supervisor: WorkerSupervisor? = null,
) {
    private val log = LoggerFactory.getLogger(OAuthCleanupWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cycleInterval = 5.minutes

    fun start(): Job = scope.launch {
        // Brief startup delay matches the other workers — DB pool warms,
        // migrations finish, etc.
        delay(30_000L)
        while (true) {
            if (cleanupDisabled()) {
                log.info("OAuth cleanup: OAUTH_CLEANUP_DISABLED=true, sleeping cycle")
            } else {
                try {
                    val reaped = store.reapExpired()
                    if (reaped > 0) {
                        log.info("OAuth cleanup reaped {} expired rows", reaped)
                    }
                    supervisor?.recordTick(WORKER_NAME)
                } catch (e: Exception) {
                    log.error("OAuth cleanup cycle failed", e)
                }
            }
            delay(cycleInterval)
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    // Per-iteration kill switch. Lets the operator stop the DELETE-on-hot-row
    // loop without restarting the app if it ever starts contending with
    // /exchange or /handoff under load. Request-time `expires_at > NOW()`
    // filters keep correctness intact while the worker sleeps — the only
    // cost is unbounded table growth, which the operator can clear manually
    // via `DELETE FROM oauth_ephemeral WHERE expires_at < NOW();` before
    // re-enabling.
    private fun cleanupDisabled(): Boolean =
        System.getenv("OAUTH_CLEANUP_DISABLED")?.equals("true", ignoreCase = true) == true

    private companion object {
        const val WORKER_NAME = "oauth_cleanup"
    }
}
