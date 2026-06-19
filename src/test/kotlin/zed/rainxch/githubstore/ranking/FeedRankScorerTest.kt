package zed.rainxch.githubstore.ranking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CooldownFactorTest {

    @Test
    fun `never shown is full weight`() {
        assertEquals(1.0, CooldownFactor.factor(daysSinceShown = null, shownCount = 0), 1e-9)
    }

    @Test
    fun `shown today and often sits near the floor`() {
        val f = CooldownFactor.factor(daysSinceShown = 0, shownCount = 20)
        // recency≈0, frequency≈0.048 → factor = 0.5 + 0.5*(0.5*0 + 0.5*0.048) ≈ 0.512
        assertTrue(f in CooldownFactor.floor..(CooldownFactor.floor + 0.05), "expected near floor, got $f")
    }

    @Test
    fun `factor is always within floor and one`() {
        for (d in listOf(null, 0, 1, 5, 30, 365)) {
            for (c in listOf(0, 1, 5, 100)) {
                val f = CooldownFactor.factor(d, c)
                assertTrue(f in CooldownFactor.floor..1.0 + 1e-9, "d=$d c=$c → $f out of [floor,1]")
            }
        }
    }

    @Test
    fun `recovers as days since shown grow`() {
        val recent = CooldownFactor.factor(daysSinceShown = 0, shownCount = 1)
        val old = CooldownFactor.factor(daysSinceShown = 30, shownCount = 1)
        assertTrue(old > recent, "cooldown should recover with time: old=$old recent=$recent")
    }

    @Test
    fun `frequency term punishes repeat shows even after time passes`() {
        // Same days-since-shown, different show counts → fewer shows ranks higher.
        val rare = CooldownFactor.factor(daysSinceShown = 5, shownCount = 1)
        val spammed = CooldownFactor.factor(daysSinceShown = 5, shownCount = 50)
        assertTrue(rare > spammed, "least-frequently-shown should win: rare=$rare spammed=$spammed")
    }
}

class FeedRankScorerTest {

    private fun input(
        id: Long,
        starVel: Double? = null,
        dlVel: Double? = null,
        stars: Long = 100,
        downloads: Long = 0,
        daysSinceRelease: Double? = 10.0,
        daysSinceShown: Int? = null,
        shownCount: Int = 0,
    ) = FeedRankScorer.Input(id, starVel, dlVel, stars, downloads, daysSinceRelease, daysSinceShown, shownCount)

    @Test
    fun `empty pool ranks empty`() {
        assertTrue(FeedRankScorer.rank(emptyList()).isEmpty())
    }

    @Test
    fun `weights compose into a key bounded in zero to one`() {
        val ranked = FeedRankScorer.rank((1L..20L).map { input(it, starVel = it.toDouble(), stars = it * 100) })
        ranked.forEach {
            assertTrue(it.key in 0.0..1.0 + 1e-9, "key out of range: ${it.key}")
            assertTrue(it.momentum in 0.0..1.0 + 1e-9, "momentum out of range: ${it.momentum}")
            assertTrue(it.popularity in 0.0..1.0 + 1e-9, "popularity out of range: ${it.popularity}")
            assertTrue(it.recency in 0.0..1.0 + 1e-9, "recency out of range: ${it.recency}")
        }
    }

    @Test
    fun `null velocity yields zero momentum and ranks on base`() {
        // No velocity anywhere → momentum 0 for all → order follows base (popularity+recency).
        val ranked = FeedRankScorer.rank(
            listOf(
                input(1, stars = 50_000, daysSinceRelease = 1.0),   // strong base
                input(2, stars = 100, daysSinceRelease = 300.0),    // weak base
            )
        )
        assertTrue(ranked.all { it.momentum == 0.0 }, "history-less repos must get 0 momentum")
        assertEquals(1L, ranked.first().id, "stronger base should rank first when momentum is absent")
    }

    @Test
    fun `a repo with velocity out-ranks an identical one without`() {
        val ranked = FeedRankScorer.rank(
            listOf(
                input(1, starVel = null, stars = 1000, daysSinceRelease = 10.0),
                input(2, starVel = 500.0, stars = 1000, daysSinceRelease = 10.0),
            )
        )
        assertEquals(2L, ranked.first().id, "the surging repo should win")
        assertTrue(ranked.first { it.id == 2L }.momentum > 0.0)
    }

    @Test
    fun `cooldown demotes a recently-shown repo but not via its momentum`() {
        // Two repos, identical strong momentum + base; one shown today+often.
        val fresh = input(1, starVel = 1000.0, stars = 5000, daysSinceRelease = 5.0, daysSinceShown = null, shownCount = 0)
        val shown = input(2, starVel = 1000.0, stars = 5000, daysSinceRelease = 5.0, daysSinceShown = 0, shownCount = 30)
        val ranked = FeedRankScorer.rank(listOf(fresh, shown))
        val f = ranked.first { it.id == 1L }
        val s = ranked.first { it.id == 2L }
        // Same momentum (cooldown must NOT touch it); cooldown < 1 only on the shown one.
        assertEquals(f.momentum, s.momentum, 1e-9)
        assertTrue(s.cooldown < f.cooldown, "shown repo should have a lower cooldown factor")
        assertTrue(f.key > s.key, "fresh repo ranks above an equally-good recently-shown one")
        // Momentum survives cooldown: the shown repo's key still includes its full momentum term.
        assertTrue(s.key >= FeedRankScorer.W_MOMENTUM * s.momentum, "momentum must not be penalised by cooldown")
    }

    @Test
    fun `ranking is deterministic`() {
        val pool = (1L..30L).map { input(it, starVel = (it % 7).toDouble(), stars = it * 37, daysSinceShown = (it % 5).toInt(), shownCount = (it % 3).toInt()) }
        assertEquals(FeedRankScorer.rank(pool).map { it.id }, FeedRankScorer.rank(pool).map { it.id })
    }
}
