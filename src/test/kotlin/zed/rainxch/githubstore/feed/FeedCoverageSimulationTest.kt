package zed.rainxch.githubstore.feed

import zed.rainxch.githubstore.ranking.CooldownFactor
import zed.rainxch.githubstore.ranking.FeedRankScorer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Coverage regression guard for the brief's two load-bearing claims (§4c, §4d):
 *
 *  1. Selecting the WHOLE eligible set (not four capped top-N pools) + a cooldown
 *     whose FREQUENCY term reads shown_count lets the feed walk the long tail.
 *  2. A recency-ONLY cooldown (last_shown only, no shown_count) does NOT — it
 *     strands a chunk of the pool, the exact failure the brief simulated
 *     (~750/2000 un-surfaceable). The frequency term is what fixes it.
 *
 * The model freezes a static popularity+recency base per repo (the real
 * FeedRankScorer computes it; velocity is null = cold start, so momentum is 0
 * and the base drives ranking), then runs an N-day rotation picking the top-F by
 * `cooldown × base` each day, recording exposures exactly like the real
 * day-gated write. If a future change weakens the frequency term, the frequency
 * arm's coverage collapses toward the recency-only arm and this test fails.
 */
class FeedCoverageSimulationTest {

    private data class Repo(val id: Long, val stars: Long, val downloads: Long, val relDays: Double)

    private val eligibleSize = 1_500
    private val dailySlots = 500
    private val days = 60

    // Static, skewed catalog: a long tail of small repos under a few elites, so
    // coverage is genuinely contested (a flat pool would round-robin trivially).
    private val repos: List<Repo> = run {
        val rng = Random(20_260_619)
        (0 until eligibleSize).map { i ->
            val stars = exp(ln(50.0) + rng.nextDouble() * (ln(30_000.0) - ln(50.0))).toLong()
            val downloads = stars * (5 + rng.nextInt(50))
            val relDays = rng.nextInt(0, 120).toDouble()
            Repo(i.toLong(), stars, downloads, relDays)
        }
    }

    // Frozen base from the REAL scorer (velocity null → momentum 0 → key == base
    // when never shown). Reused by both arms so they differ ONLY in cooldown.
    private val base: Map<Long, Double> = run {
        val ranked = FeedRankScorer.rank(
            repos.map { FeedRankScorer.Input(it.id, null, null, it.stars, it.downloads, it.relDays, null, 0) }
        )
        ranked.associate { it.id to (FeedRankScorer.W_POPULARITY * it.popularity + FeedRankScorer.W_RECENCY * it.recency) }
    }

    private fun simulate(cooldown: (daysSinceShown: Int?, shownCount: Int) -> Double): Int {
        val lastShown = HashMap<Long, Int>()
        val count = HashMap<Long, Int>()
        val distinct = HashSet<Long>()
        for (d in 0 until days) {
            val placed = repos
                .map { r ->
                    val dss = lastShown[r.id]?.let { d - it }
                    r.id to cooldown(dss, count[r.id] ?: 0) * (base[r.id] ?: 0.0)
                }
                .sortedByDescending { it.second }
                .take(dailySlots)
                .map { it.first }
            placed.forEach { id ->
                lastShown[id] = d
                count[id] = (count[id] ?: 0) + 1
                distinct.add(id)
            }
        }
        return distinct.size
    }

    @Test
    fun `frequency cooldown covers more of the tail than recency-only`() {
        val frequencyCoverage = simulate { dss, c -> CooldownFactor.factor(dss, c) }

        // Recency-only control: same FLOOR/TAU, but ignores shown_count — the
        // pre-fix design the brief proved insufficient.
        val floor = CooldownFactor.floor
        val tau = CooldownFactor.tauDays
        val recencyOnlyCoverage = simulate { dss, _ ->
            if (dss == null) 1.0
            else floor + (1.0 - floor) * (1.0 - exp(-dss.coerceAtLeast(0).toDouble() / tau))
        }

        println("[coverage-sim] E=$eligibleSize F=$dailySlots days=$days → frequency=$frequencyCoverage recencyOnly=$recencyOnlyCoverage")

        // Core claim: the frequency term strictly improves long-tail coverage.
        assertTrue(
            frequencyCoverage > recencyOnlyCoverage,
            "frequency cooldown ($frequencyCoverage) must surface more distinct repos than recency-only ($recencyOnlyCoverage)",
        )
        // Recency-only strands part of the pool (it never reaches full coverage).
        assertTrue(
            recencyOnlyCoverage < eligibleSize,
            "recency-only unexpectedly covered the whole pool — distribution no longer exercises the regression",
        )
    }
}
