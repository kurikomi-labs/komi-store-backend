package zed.rainxch.githubstore.ingest

import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.ranking.VelocityScore
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Computes per-repo star/download velocity (EWMA of the daily rate of change)
 * from repo_daily_snapshot and writes star_velocity_ewma / dl_velocity_ewma
 * back onto today's snapshot row. This is the feed-v2 momentum signal — the
 * leading "what's hot now" indicator, all public-data, zero client telemetry.
 *
 * SQL gathers the inputs (today's totals, the totals one window ago, the prior
 * EWMA) in a single windowed query; the math lives in VelocityScore so the
 * formula stays centralized and unit-tested. Repos without a snapshot at least
 * one window old are skipped (EWMA stays NULL) — the feed scorer COALESCEs a
 * null velocity to its static fallback, so a brand-new or history-less repo is
 * never penalised, just ranked on totals until its series matures.
 *
 * Idempotent within a UTC day: re-running recomputes the same per-day delta
 * (same snapshots) against the same prior EWMA, so the written value converges.
 */
class VelocityAggregationWorker(
    private val supervisor: WorkerSupervisor? = null,
) {
    private val log = LoggerFactory.getLogger(VelocityAggregationWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val startupDelay = 30.minutes
    private val cycleInterval = 24.hours
    private val writeChunkSize = 1_000
    private val windowDays = VelocityScore.velocityWindowDays

    // Distinct from the other workers (911_001..911_006 + 911_008 retention) so
    // it runs concurrently; only collides with another replica of itself.
    private val advisoryLockId: Long = 911_007L

    fun start(): Job = scope.launch {
        delay(startupDelay)
        while (true) {
            try {
                if (tryRunCycle()) {
                    supervisor?.recordTick(WORKER_NAME)
                }
            } catch (e: Exception) {
                log.error("Velocity aggregation cycle failed", e)
                Sentry.captureException(e)
            }
            delay(cycleInterval)
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    // Re-entry point for ad-hoc / operator execution.
    suspend fun runOnce(): Boolean = tryRunCycle()

    /**
     * One transaction pairs `pg_try_advisory_xact_lock` with the read + write.
     * Xact-scoped locks release automatically at COMMIT, so the lock and the
     * write never disagree — unlike a session-scoped `pg_try_advisory_lock`,
     * which Hikari leaks back into the pool on transaction exit (see
     * FdroidSeedWorker). Holding both the SELECT and the UPDATEs in one
     * transaction is fine here: a once-daily pass over ~12k rows of
     * repo_daily_snapshot, a table only the fetcher writes (once/day), so
     * contention is negligible.
     */
    private suspend fun tryRunCycle(): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val locked = conn.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { ps ->
            ps.setLong(1, advisoryLockId)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
        if (!locked) {
            log.info("VelocityAggregation skipped: advisory lock held by another instance")
            return@newSuspendedTransaction false
        }
        val started = System.currentTimeMillis()
        val inputs = readInputs(conn)
        if (inputs.isEmpty()) {
            log.info("VelocityAggregation: no repos with a snapshot >={}d old yet — skipping (history still accruing)", windowDays)
            return@newSuspendedTransaction true
        }
        val scored = inputs.map { row ->
            val starVel = VelocityScore.ewmaStep(row.prevStarEwma, VelocityScore.perDay(row.curStars, row.pastStars, row.daysBetween))
            val dlVel = VelocityScore.ewmaStep(row.prevDlEwma, VelocityScore.perDay(row.curDownloads, row.pastDownloads, row.daysBetween))
            ScoredVelocity(row.repoId, starVel, dlVel)
        }
        val written = writeAll(conn, scored)
        val elapsed = System.currentTimeMillis() - started
        log.info("VelocityAggregation cycle: {} repos scored, {} rows written, {} ms", scored.size, written, elapsed)
        true
    }

    private fun readInputs(conn: java.sql.Connection): List<VelocityInput> {
        val out = mutableListOf<VelocityInput>()
        // today  = this UTC day's snapshot row (the one we'll write velocity onto)
        // past   = most recent snapshot on/before (today - window) — the delta baseline
        // prev   = most recent snapshot strictly before today — its EWMA seeds today's
        conn.prepareStatement(
            """
            WITH today AS (
                SELECT repo_id, snapshot_date, stars, download_count
                FROM repo_daily_snapshot
                WHERE snapshot_date = (NOW() AT TIME ZONE 'UTC')::date
            ),
            past AS (
                SELECT DISTINCT ON (repo_id) repo_id, snapshot_date AS past_date,
                       stars AS past_stars, download_count AS past_dl
                FROM repo_daily_snapshot
                WHERE snapshot_date <= ((NOW() AT TIME ZONE 'UTC')::date - make_interval(days => ?))
                ORDER BY repo_id, snapshot_date DESC
            ),
            prev AS (
                SELECT DISTINCT ON (repo_id) repo_id,
                       star_velocity_ewma AS prev_star, dl_velocity_ewma AS prev_dl
                FROM repo_daily_snapshot
                WHERE snapshot_date < (NOW() AT TIME ZONE 'UTC')::date
                ORDER BY repo_id, snapshot_date DESC
            )
            SELECT t.repo_id, t.stars AS cur_stars, t.download_count AS cur_dl,
                   p.past_stars, p.past_dl,
                   (t.snapshot_date - p.past_date) AS days_between,
                   pv.prev_star, pv.prev_dl
            FROM today t
            JOIN past p ON p.repo_id = t.repo_id
            LEFT JOIN prev pv ON pv.repo_id = t.repo_id
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, windowDays)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        VelocityInput(
                            repoId = rs.getLong("repo_id"),
                            curStars = rs.getLong("cur_stars"),
                            curDownloads = rs.getLong("cur_dl"),
                            pastStars = rs.getLong("past_stars"),
                            pastDownloads = rs.getLong("past_dl"),
                            daysBetween = rs.getInt("days_between"),
                            prevStarEwma = (rs.getObject("prev_star") as? Number)?.toDouble(),
                            prevDlEwma = (rs.getObject("prev_dl") as? Number)?.toDouble(),
                        )
                    )
                }
            }
        }
        return out
    }

    // Writes within the caller's transaction (same connection that holds the
    // xact lock). Batched, flushed every writeChunkSize rows to bound the
    // driver's batch array; all in the one cycle transaction.
    private fun writeAll(conn: java.sql.Connection, scored: List<ScoredVelocity>): Int {
        if (scored.isEmpty()) return 0
        var written = 0
        conn.prepareStatement(
            """
            UPDATE repo_daily_snapshot
            SET star_velocity_ewma = ?, dl_velocity_ewma = ?
            WHERE repo_id = ? AND snapshot_date = (NOW() AT TIME ZONE 'UTC')::date
            """.trimIndent()
        ).use { ps ->
            scored.forEachIndexed { i, s ->
                ps.setFloat(1, s.starVelocity.toFloat())
                ps.setFloat(2, s.dlVelocity.toFloat())
                ps.setLong(3, s.repoId)
                ps.addBatch()
                if ((i + 1) % writeChunkSize == 0) written += ps.executeBatch().sum()
            }
            written += ps.executeBatch().sum()
        }
        return written
    }

    private data class VelocityInput(
        val repoId: Long,
        val curStars: Long,
        val curDownloads: Long,
        val pastStars: Long,
        val pastDownloads: Long,
        val daysBetween: Int,
        val prevStarEwma: Double?,
        val prevDlEwma: Double?,
    )

    private data class ScoredVelocity(val repoId: Long, val starVelocity: Double, val dlVelocity: Double)

    companion object {
        const val WORKER_NAME = "velocity-aggregation"
    }
}
