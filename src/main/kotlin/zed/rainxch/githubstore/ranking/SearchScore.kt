package zed.rainxch.githubstore.ranking

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln

/**
 * The unified search ranking score. Used by:
 *  - SignalAggregationWorker (hourly, with download counts from the fetcher)
 *  - GitHubSearchClient at ingest time (cold-start, downloads default to 0)
 *
 * Each factor is clamped to [0, 1] so the total sits in [0, 1]. Weights sum
 * to 1.0 — tune them here, not in callers.
 *
 * History: the original formula weighted 0.30·ctr + 0.20·install_success_rate.
 * The E6 audit killed client telemetry, so those two inputs went constant
 * (the worker's Laplace smoothing returns a flat 0.5 for every repo with no
 * events) — half the weight became pure noise that discriminated nothing.
 * They are replaced by `downloads` (GitHub release-asset download totals),
 * an install-proxy signal that is server-side catalog data, collected by
 * GitHub, requiring zero client telemetry. This keeps the "we collect
 * nothing" privacy stance while restoring real popularity discrimination.
 */
object SearchScore {
    fun compute(
        stars: Int,
        downloads: Long = 0,
        daysSinceRelease: Double? = null,
    ): Double {
        val starFactor = (ln((stars + 1).toDouble()) / ln(10.0) / 6.0).coerceIn(0.0, 1.0)
        // log10(downloads+1)/7 → ~10M downloads saturates to 1.0. Release-asset
        // totals span many orders of magnitude, same as stars, so the same
        // log compression applies; /7 is a touch wider than stars' /6 because
        // download counts run an order of magnitude higher than star counts.
        val downloadFactor = (ln((downloads + 1).toDouble()) / ln(10.0) / 7.0).coerceIn(0.0, 1.0)
        val recencyFactor = daysSinceRelease?.let { exp(-it / 90.0).coerceIn(0.0, 1.0) } ?: 0.0
        return 0.45 * starFactor + 0.30 * downloadFactor + 0.25 * recencyFactor
    }

    // Clock-skew safeguard: a release date in the future would otherwise hand
    // a repo a recency factor > 1 (and possibly negative `days`, which the
    // caller might floor inconsistently). Both call sites — Kotlin ingest and
    // the SQL aggregation worker — must funnel through this floor or share
    // its SQL equivalent (GREATEST(..., 0)).
    fun daysSinceRelease(releaseDate: Instant?, now: Instant = Instant.now()): Double? {
        if (releaseDate == null) return null
        return ChronoUnit.DAYS.between(releaseDate, now).toDouble().coerceAtLeast(0.0)
    }
}
