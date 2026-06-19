package zed.rainxch.githubstore.ranking

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VelocityScoreTest {

    @Test
    fun `perDay divides the floored delta by the day gap`() {
        // 700 new stars over 7 days = 100/day.
        assertEquals(100.0, VelocityScore.perDay(currentTotal = 1700, pastTotal = 1000, daysBetween = 7), 1e-9)
    }

    @Test
    fun `perDay floors a negative delta at zero`() {
        // An un-star / asset deletion is not negative momentum.
        assertEquals(0.0, VelocityScore.perDay(currentTotal = 900, pastTotal = 1000, daysBetween = 7), 1e-9)
    }

    @Test
    fun `perDay treats a zero or negative gap as one day`() {
        // Guards a same-day or clock-skew gap from dividing by zero.
        assertEquals(50.0, VelocityScore.perDay(currentTotal = 50, pastTotal = 0, daysBetween = 0), 1e-9)
    }

    @Test
    fun `alpha is in the open-zero-to-one range and rises as half-life shrinks`() {
        val a7 = VelocityScore.alpha(7.0)
        val a2 = VelocityScore.alpha(2.0)
        assertTrue(a7 > 0.0 && a7 < 1.0, "alpha(7) out of range: $a7")
        assertTrue(a2 > a7, "shorter half-life should react faster: a2=$a2 a7=$a7")
        // 7-day half-life ≈ 0.094.
        assertTrue(abs(a7 - 0.0942) < 0.001, "alpha(7) expected ~0.094, got $a7")
    }

    @Test
    fun `ewmaStep with no prior seeds the average with the current value`() {
        assertEquals(42.0, VelocityScore.ewmaStep(previous = null, current = 42.0), 1e-9)
    }

    @Test
    fun `ewmaStep blends toward the current value by alpha`() {
        val hl = 7.0
        val a = VelocityScore.alpha(hl)
        // prev=0, current=100 → exactly alpha*100.
        assertEquals(a * 100.0, VelocityScore.ewmaStep(previous = 0.0, current = 100.0, halflifeDays = hl), 1e-9)
        // A spike moves the average only fractionally — spike resistance.
        val smoothed = VelocityScore.ewmaStep(previous = 10.0, current = 1000.0, halflifeDays = hl)
        assertTrue(smoothed < 110.0, "single spike should not dominate the EWMA, got $smoothed")
        assertTrue(smoothed > 10.0, "EWMA should still move toward the spike, got $smoothed")
    }

    @Test
    fun `steady rate converges to that rate under repeated EWMA steps`() {
        var ewma: Double? = null
        repeat(200) { ewma = VelocityScore.ewmaStep(ewma, current = 25.0) }
        assertEquals(25.0, ewma!!, 0.01)
    }
}
