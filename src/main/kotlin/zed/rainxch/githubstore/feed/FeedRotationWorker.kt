package zed.rainxch.githubstore.feed

import io.sentry.Sentry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Touches every feed variant (the all-platform feed plus the four platforms)
 * once per UTC day so its cooldown ages even when no real request — and no
 * external offline-cache cron — hit it that day. The build itself
 * (FeedService.buildV2) does the placed-only, day-gated exposure write; this
 * worker only guarantees the build happens. It is the design brief's §7 "daily
 * sweep" alternative to a request-triggered-only write: without it, a low-traffic
 * platform's rotation silently freezes (brief §7 / §11, "never-requested
 * platforms stall"), and exposure aging would depend on an external GitHub
 * Actions cron in another repo.
 *
 * No advisory lock (unlike the DB-mutating workers): the work is idempotent cache
 * builds, and the exposure write's own `last_shown_epochday < today` guard is the
 * concurrency control — a second build the same day (this worker, a request, or
 * another replica) writes nothing. The 24h cycle plus that day-gate guarantees
 * "at least one build per platform per UTC day" regardless of when the loop fires.
 *
 * A no-op for exposure when FEED_V2_RANKING is off: buildLegacy writes no
 * exposure, so the worker just warms the legacy daily cache (harmless).
 */
class FeedRotationWorker(
    private val feedService: FeedService,
    private val supervisor: WorkerSupervisor? = null,
) {
    private val log = LoggerFactory.getLogger(FeedRotationWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Lands after VelocityAggregationWorker (30m) so the first build of the day
    // ranks on fresh velocity. Correctness does not depend on it — the day-gate
    // makes any firing time safe — it only avoids ranking on stale momentum.
    private val startupDelay = 35.minutes
    private val cycleInterval = 24.hours

    // null = the all-platform feed; the four platforms rotate independently, so
    // each must be touched on its own to age its own cooldown.
    private val variants = listOf(null, "android", "windows", "macos", "linux")

    fun start(): Job = scope.launch {
        delay(startupDelay)
        while (true) {
            try {
                sweep()
                supervisor?.recordTick(WORKER_NAME)
            } catch (e: CancellationException) {
                // Scope cancelled on shutdown (workerSupervisor.cancelAll) — not
                // a failure. Re-throw so the coroutine actually stops instead of
                // logging a spurious error + Sentry event and looping again.
                throw e
            } catch (e: Exception) {
                log.error("FeedRotationWorker cycle failed", e)
                Sentry.captureException(e)
            }
            delay(cycleInterval)
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    // Re-entry point for ad-hoc / operator execution.
    suspend fun runOnce() = sweep()

    private suspend fun sweep() {
        var built = 0
        for (platform in variants) {
            try {
                // page() builds + caches the feed for (platform, today) on a cache
                // miss; buildV2 does the day-gated, placed-only exposure write. A
                // minimal page request (1 item) is enough to force the build.
                feedService.page(platform, page = 1, limit = 1)
                built++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One platform's build failing must not skip the rest of the
                // sweep for the whole day — isolate it and carry on.
                log.error("FeedRotationWorker: build failed for platform={}", platform ?: "all", e)
                Sentry.captureException(e)
            }
        }
        log.info("FeedRotationWorker: ensured daily build for {}/{} feed variants", built, variants.size)
    }

    companion object {
        const val WORKER_NAME = "feed-rotation"
    }
}
