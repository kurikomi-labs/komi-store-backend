package zed.rainxch.githubstore.ranking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchScoreTest {

    @Test
    fun `cold start with zero stars returns zero`() {
        val s = SearchScore.compute(stars = 0)
        assertEquals(0.0, s, 0.0001)
    }

    @Test
    fun `more stars produce higher score when everything else equal`() {
        val low = SearchScore.compute(stars = 100)
        val high = SearchScore.compute(stars = 10_000)
        assertTrue(high > low, "expected high($high) > low($low)")
    }

    @Test
    fun `star contribution saturates at million-star ceiling`() {
        val million = SearchScore.compute(stars = 1_000_000)
        val tenMillion = SearchScore.compute(stars = 10_000_000)
        // log10(1M+1)/6 ≈ 1.0, so the star factor is clamped.
        assertTrue(tenMillion - million < 0.01, "star factor should saturate past 1M")
    }

    @Test
    fun `download contribution moves the score and saturates`() {
        val none = SearchScore.compute(stars = 1000, downloads = 0)
        val many = SearchScore.compute(stars = 1000, downloads = 10_000_000)
        assertTrue(many > none, "downloads should lift the score")
        // 0.30 weight, log10(10M+1)/7 ≈ 1.0 → delta ≈ 0.30 at saturation.
        assertEquals(0.30, many - none, 0.005)
    }

    @Test
    fun `download factor saturates past ten million`() {
        val tenM = SearchScore.compute(stars = 0, downloads = 10_000_000)
        val hundredM = SearchScore.compute(stars = 0, downloads = 100_000_000)
        assertTrue(hundredM - tenM < 0.01, "download factor should saturate past 10M")
    }

    @Test
    fun `fresh release is worth exactly the recency-weight ceiling`() {
        val yearOld = SearchScore.compute(stars = 1000, daysSinceRelease = 1000.0)
        val freshToday = SearchScore.compute(stars = 1000, daysSinceRelease = 0.0)
        // daysSinceRelease = 0 → exp(0) = 1.0 → recency factor = 1.0 → weight 0.25.
        // 1000-day-old → exp(-1000/90) ≈ 0 → recency factor ≈ 0.
        assertEquals(0.25, freshToday - yearOld, 0.0005)
    }

    @Test
    fun `null daysSinceRelease is treated as zero recency contribution`() {
        val nullRelease = SearchScore.compute(stars = 1000, daysSinceRelease = null)
        val ancient    = SearchScore.compute(stars = 1000, daysSinceRelease = 10_000.0)
        // Both should collapse the recency factor to ~0.
        assertTrue(kotlin.math.abs(nullRelease - ancient) < 0.001)
    }

    @Test
    fun `score is bounded in zero to one`() {
        val maxed = SearchScore.compute(
            stars = 10_000_000,
            downloads = 100_000_000,
            daysSinceRelease = 0.0,
        )
        assertTrue(maxed in 0.0..1.0, "expected 0 <= $maxed <= 1")

        val zeroed = SearchScore.compute(stars = 0)
        assertTrue(zeroed in 0.0..1.0)
    }

    @Test
    fun `negative stars or downloads never produce NaN`() {
        // Schema makes these impossible (NOT NULL DEFAULT 0) but the floor
        // guards against ln(<=0) = -Infinity/NaN poisoning the score.
        val negStars = SearchScore.compute(stars = -100)
        val negDownloads = SearchScore.compute(stars = 10, downloads = -5)
        assertTrue(!negStars.isNaN() && negStars in 0.0..1.0, "negative stars produced $negStars")
        assertTrue(!negDownloads.isNaN() && negDownloads in 0.0..1.0, "negative downloads produced $negDownloads")
        // -100 stars floored to 0 → identical to stars=0.
        assertEquals(SearchScore.compute(stars = 0), negStars, 0.0)
    }
}
