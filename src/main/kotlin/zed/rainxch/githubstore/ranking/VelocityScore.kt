package zed.rainxch.githubstore.ranking

import kotlin.math.exp
import kotlin.math.ln

/**
 * Velocity (rate-of-change) signal for feed-v2. The leading "what's hot now"
 * engagement proxy, computed entirely from public GitHub counters captured in
 * repo_daily_snapshot — zero client telemetry.
 *
 * This object owns the EWMA + per-day-delta math ONLY (the data-gathering join
 * over snapshot history lives in VelocityAggregationWorker, the read-path
 * normalization/popularity/momentum composition lives in the feed scorer).
 * The formula lives here exactly once — never inline alpha() or the delta floor
 * elsewhere, same discipline as SearchScore.
 *
 * GitHub exposes downloads only as a running total (no history endpoint), so
 * the snapshot time-series is the sole source of download velocity and a missed
 * capture day cannot be backfilled.
 */
object VelocityScore {

    // EWMA half-life. 7 days reacts within a week (feels "hot") while ignoring
    // single-day noise; too short (~2d) thrashes, too long (~30d) degenerates
    // into a totals score. Override via VELOCITY_EWMA_HALFLIFE_DAYS.
    val ewmaHalflifeDays: Double =
        System.getenv("VELOCITY_EWMA_HALFLIFE_DAYS")?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 7.0

    // Trailing window (days) over which a raw star/download delta is measured.
    // Override via VELOCITY_WINDOW_DAYS.
    val velocityWindowDays: Int =
        System.getenv("VELOCITY_WINDOW_DAYS")?.toIntOrNull()?.takeIf { it >= 1 } ?: 7

    /** EWMA smoothing factor: α = 1 − exp(−ln2 / halflife). In (0, 1]. */
    fun alpha(halflifeDays: Double = ewmaHalflifeDays): Double =
        1.0 - exp(-ln(2.0) / halflifeDays)

    /**
     * Per-day rate from two cumulative totals [daysBetween] apart. Floored at 0
     * — a drop (un-star, deleted/re-tagged asset) is not negative momentum,
     * same defensive posture as the SearchScore stars floor — and divided by the
     * ACTUAL day gap so a missed snapshot day doesn't read as doubled velocity.
     */
    fun perDay(currentTotal: Long, pastTotal: Long, daysBetween: Int): Double {
        val gap = daysBetween.coerceAtLeast(1)
        val delta = (currentTotal - pastTotal).coerceAtLeast(0L)
        return delta.toDouble() / gap
    }

    /**
     * One EWMA step: α·current + (1−α)·previous. The first observation (no prior
     * EWMA) seeds the average with itself, so a brand-new repo's first velocity
     * is just its raw per-day rate rather than a smeared-from-zero value.
     */
    fun ewmaStep(previous: Double?, current: Double, halflifeDays: Double = ewmaHalflifeDays): Double {
        if (previous == null) return current
        val a = alpha(halflifeDays)
        return a * current + (1.0 - a) * previous
    }
}
