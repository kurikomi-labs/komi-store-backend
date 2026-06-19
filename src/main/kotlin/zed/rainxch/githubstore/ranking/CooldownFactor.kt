package zed.rainxch.githubstore.ranking

import kotlin.math.exp

/**
 * Feed-v2 rotation memory. A gentle multiplier in [floor, 1] applied to a
 * repo's BASE score (popularity + recency, NOT momentum — see FeedRankScorer)
 * so the feed walks the eligible quality pool across days instead of re-showing
 * the same top slice every day.
 *
 * Two terms, because least-recently-shown alone does not round-robin a tail
 * (simulation in the design brief: a recency-only multiplier left ~750/2000
 * repos permanently un-surfaceable):
 *  - recency_term   = 1 − exp(−daysSinceShown / TAU)  → recovers after a show
 *  - frequency_term = 1 / (1 + shownCount)            → least-FREQUENTLY-shown
 *
 * FLOOR keeps a recently/often-shown repo at ≥ FLOOR of its base, so the best
 * repos still recur (they only yield to equally-good but less-shown peers, never
 * to junk — junk fails the eligibility gate and never reaches scoring). FLOOR is
 * NOT an absolute quality guarantee: a demoted repo stays ahead only of peers
 * within 1/FLOOR× of its base; that is intended rotation, not a violated
 * invariant.
 *
 * Global, not per-user: callers key exposure on (repo_id, platform) only, so this
 * never touches the anonymous, CDN-shared feed invariant.
 */
object CooldownFactor {

    val floor: Double =
        System.getenv("FEED_COOLDOWN_FLOOR")?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: 0.5

    // Recency recovery time-constant. 4.5 → ~14 days to recover ~95% of the
    // recency term. Override via FEED_COOLDOWN_TAU_DAYS.
    val tauDays: Double =
        System.getenv("FEED_COOLDOWN_TAU_DAYS")?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 4.5

    /**
     * @param daysSinceShown days since this repo was last placed in the feed for
     *   this platform, or null if it has never been shown.
     * @param shownCount how many times it has been shown in the trailing
     *   coverage window.
     * @return multiplier in [floor, 1]; 1.0 for a never-shown repo.
     */
    fun factor(daysSinceShown: Int?, shownCount: Int): Double {
        if (daysSinceShown == null) return 1.0
        val recency = 1.0 - exp(-daysSinceShown.coerceAtLeast(0).toDouble() / tauDays)
        val frequency = 1.0 / (1.0 + shownCount.coerceAtLeast(0))
        return floor + (1.0 - floor) * (0.5 * recency + 0.5 * frequency)
    }
}
