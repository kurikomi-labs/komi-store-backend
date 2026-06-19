package zed.rainxch.githubstore.ranking

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Feed-v2 read-path ranking. Turns a pool of ALREADY-ELIGIBLE repos (the quality
 * gate is a SQL WHERE upstream — junk never reaches here) into a ranked list by
 *
 *   final_rank_key = W_MOMENTUM·momentum + cooldown · (W_POPULARITY·popularity + W_RECENCY·recency)
 *
 * Cooldown multiplies ONLY the base (popularity + recency), never momentum: a
 * genuine ongoing surge must not fight its own exposure penalty (else a spiking
 * repo gets shown, demoted while still surging, and drops out). Momentum is the
 * short-lived "what's hot" overlay; the base × cooldown drives long-tail rotation.
 *
 * Weights are declared on terms that each genuinely span [0, 1] — momentum is
 * rescaled out of the logistic's true [0.047, 0.953] range, popularity/recency
 * are natively [0, 1] — so the declared 0.45/0.30/0.25 are the EFFECTIVE weights.
 *
 * Popularity is computed natively from log1p(stars)/log1p(downloads), NOT via
 * SearchScore.compute (which caps at 0.50, re-injects a recency term that would
 * double-count, and historically had no download term).
 */
object FeedRankScorer {

    const val W_MOMENTUM = 0.45
    const val W_POPULARITY = 0.30
    const val W_RECENCY = 0.25

    // Within momentum: stars lead (universal, clean signal); downloads weigh less
    // (cold for the first ~1-2 weeks after capture starts, noisier via asset churn).
    const val MOMENTUM_STAR = 0.55
    const val MOMENTUM_DL = 0.45

    // log1p ceilings at which a totals factor saturates to 1.0. Calibration
    // constants (a high percentile of the pool), overridable.
    val starPopNorm: Double =
        System.getenv("FEED_POP_STAR_NORM")?.toDoubleOrNull()?.takeIf { it > 1.0 } ?: 100_000.0
    val dlPopNorm: Double =
        System.getenv("FEED_POP_DL_NORM")?.toDoubleOrNull()?.takeIf { it > 1.0 } ?: 10_000_000.0

    private const val RELEASE_HALFLIFE_DAYS = 90.0

    // logistic(±3) bounds — the achievable range after the z-clamp, used to
    // rescale momentum back to a true [0, 1].
    private val LOGISTIC_MIN = 1.0 / (1.0 + exp(3.0))   // ≈ 0.04743
    private val LOGISTIC_MAX = 1.0 / (1.0 + exp(-3.0))  // ≈ 0.95257

    data class Input(
        val id: Long,
        // EWMA velocities from repo_daily_snapshot; null when history hasn't
        // matured (the worker leaves them NULL) → 0 momentum contribution, so
        // the repo ranks on base alone (the COALESCE-to-static fallback).
        val starVelocityEwma: Double?,
        val dlVelocityEwma: Double?,
        val stars: Long,
        val downloads: Long,
        val daysSinceRelease: Double?,
        val daysSinceShown: Int?,
        val shownCount: Int,
    )

    data class Ranked(
        val id: Long,
        val key: Double,
        val momentum: Double,
        val popularity: Double,
        val recency: Double,
        val cooldown: Double,
    )

    /** Ranks the eligible pool, highest key first. Pure: same input → same order. */
    fun rank(pool: List<Input>): List<Ranked> {
        if (pool.isEmpty()) return emptyList()
        val starMomentum = normalizeVelocity(pool.map { it.starVelocityEwma })
        val dlMomentum = normalizeVelocity(pool.map { it.dlVelocityEwma })

        return pool.mapIndexed { i, r ->
            val momentum = MOMENTUM_STAR * starMomentum[i] + MOMENTUM_DL * dlMomentum[i]
            val popularity = 0.5 * popFactor(r.stars, starPopNorm) + 0.5 * popFactor(r.downloads, dlPopNorm)
            val recency = r.daysSinceRelease?.let { exp(-it / RELEASE_HALFLIFE_DAYS).coerceIn(0.0, 1.0) } ?: 0.0
            val base = W_POPULARITY * popularity + W_RECENCY * recency
            val cooldown = CooldownFactor.factor(r.daysSinceShown, r.shownCount)
            val key = W_MOMENTUM * momentum + cooldown * base
            Ranked(r.id, key, momentum, popularity, recency, cooldown)
        }.sortedByDescending { it.key }
    }

    /** log1p(value)/log1p(norm), clamped to [0, 1]. value floored at 0. */
    private fun popFactor(value: Long, norm: Double): Double =
        (ln(value.coerceAtLeast(0L) + 1.0) / ln(norm + 1.0)).coerceIn(0.0, 1.0)

    /**
     * Pool-relative momentum normalization. Repos WITH a velocity are ranked
     * among themselves via robust median/MAD z-score → logistic → rescale to a
     * true [0, 1]; repos with NULL velocity (no matured history) get 0, so they
     * rank on base alone and a velocity-bearing repo always out-momentums a
     * history-less one. When the whole pool is null (cold start) every momentum
     * is 0 → ranking collapses to base = the static fallback.
     */
    private fun normalizeVelocity(values: List<Double?>): List<Double> {
        val present = values.filterNotNull().map { ln(it.coerceAtLeast(0.0) + 1.0) }
        if (present.isEmpty()) return List(values.size) { 0.0 }
        val med = median(present)
        val mad = median(present.map { abs(it - med) }).coerceAtLeast(1e-9)
        return values.map { v ->
            if (v == null) return@map 0.0
            val log = ln(v.coerceAtLeast(0.0) + 1.0)
            val z = ((log - med) / (1.4826 * mad)).coerceIn(-3.0, 3.0)
            val logistic = 1.0 / (1.0 + exp(-z))
            ((logistic - LOGISTIC_MIN) / (LOGISTIC_MAX - LOGISTIC_MIN)).coerceIn(0.0, 1.0)
        }
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        if (n == 0) return 0.0
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
