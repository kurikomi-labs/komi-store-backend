package zed.rainxch.githubstore.ingest

import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Daily retention sweep for repo_daily_snapshot — the velocity/analytics
 * time-series. Drops rows older than the retention window in bounded chunks.
 *
 * Retention is DELIBERATELY long (default 730 days, env DAILY_SNAPSHOT_RETENTION_DAYS).
 * The feed's velocity only needs ~90 days, but download velocity has NO backfill
 * (GitHub exposes no download-history endpoint), so any pruned day is gone
 * forever — and the future paid maintainer-analytics dashboard wants long
 * history. Never lower this below the dashboard's needs to match the feed's;
 * the feed reading only a trailing window is free, but deleting history is not
 * reversible. (Separate worker / advisory lock from RetentionWorker, which
 * sweeps `events` on a 90-day window — different table, different policy.)
 *
 * Postgres rejects LIMIT directly on DELETE, so we go through a CTID subquery
 * and loop in 10k-row chunks until a chunk deletes fewer rows than the cap.
 */
class SnapshotRetentionWorker(
    private val supervisor: WorkerSupervisor? = null,
) {
    private val log = LoggerFactory.getLogger(SnapshotRetentionWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val startupDelay = 25.minutes
    private val cycleInterval = 24.hours
    private val chunkSize = 10_000
    private val retentionDays = System.getenv("DAILY_SNAPSHOT_RETENTION_DAYS")?.toIntOrNull() ?: 730

    // Distinct from the other workers' lock ids (911_001..911_006 in use) so
    // this runs concurrently with them; only collides with another replica of
    // itself, which is the intent.
    private val advisoryLockId: Long = 911_008L

    fun start(): Job = scope.launch {
        delay(startupDelay)
        while (true) {
            try {
                if (tryRunCycle()) {
                    supervisor?.recordTick(WORKER_NAME)
                }
            } catch (e: Exception) {
                log.error("Snapshot retention cycle failed", e)
                Sentry.captureException(e)
            }
            delay(cycleInterval)
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    private fun tryRunCycle(): Boolean {
        if (!acquireAdvisoryLock()) {
            log.info("Snapshot retention skipped: advisory lock held by another instance")
            return false
        }
        try {
            runCycle()
            return true
        } finally {
            releaseAdvisoryLock()
        }
    }

    fun runCycle() {
        val swept = sweep()
        log.info("Snapshot retention cycle done: repo_daily_snapshot={} (>{}d)", swept, retentionDays)
    }

    private fun sweep(): Long {
        var total = 0L
        while (true) {
            val deleted = deleteChunk()
            total += deleted
            if (deleted < chunkSize) break
        }
        return total
    }

    private fun deleteChunk(): Int = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val sql = """
            DELETE FROM repo_daily_snapshot
            WHERE ctid IN (
                SELECT ctid FROM repo_daily_snapshot
                WHERE snapshot_date < (CURRENT_DATE - INTERVAL '$retentionDays days')
                LIMIT $chunkSize
            )
        """.trimIndent()
        conn.prepareStatement(sql).use { ps -> ps.executeUpdate() }
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
                    ps.executeQuery().close()
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to release advisory lock {}: {}", advisoryLockId, e.message)
        }
    }

    companion object {
        const val WORKER_NAME = "snapshot-retention"
    }
}
