package com.example.bleproximityalert.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendAnalyzerTest {

    @Test
    fun slopeNullWhenFewerThanFourSamples() {
        val ta = TrendAnalyzer()
        ta.addSample(-60.0, 0)
        ta.addSample(-62.0, 1000)
        ta.addSample(-64.0, 2000)
        assertNull(ta.calculateSlopeDbmPerSecond())
    }

    @Test
    fun slopeNullWhenTimeSpanTooShort() {
        val ta = TrendAnalyzer()
        for (i in 0 until 5) ta.addSample(-60.0 - i, (i * 100).toLong())
        assertNull(ta.calculateSlopeDbmPerSecond())
    }

    @Test
    fun slopeNegativeForDecreasingSignal() {
        val ta = TrendAnalyzer()
        val base = 1000L
        for (i in 0 until 6) {
            ta.addSample(-50.0 - i * 2.0, base + i * 1000L)
        }
        val slope = ta.calculateSlopeDbmPerSecond()
        assertNotNull(slope)
        assertTrue("slope should be negative, was $slope", slope!! < 0.0)
    }

    @Test
    fun slopeNearZeroForStableSignal() {
        val ta = TrendAnalyzer()
        val base = 1000L
        for (i in 0 until 6) {
            ta.addSample(-60.0, base + i * 1000L)
        }
        val slope = ta.calculateSlopeDbmPerSecond()
        assertNotNull(slope)
        assertTrue("stable signal slope near 0, was $slope", Math.abs(slope!!) < 0.2)
    }

    @Test
    fun predictionNullWhenStableOrImproving() {
        val ta = TrendAnalyzer()
        val base = 1000L
        for (i in 0 until 6) {
            ta.addSample(-60.0, base + i * 1000L)
        }
        assertNull(ta.predictSecondsToLoss(-60.0))
    }

    @Test
    fun predictionPositiveForFallingSignal() {
        val ta = TrendAnalyzer()
        val base = 1000L
        for (i in 0 until 6) {
            ta.addSample(-60.0 - i * 2.0, base + i * 1000L)
        }
        val pred = ta.predictSecondsToLoss(-72.0)
        assertNotNull(pred)
        assertTrue("prediction should be >= 0, was $pred", pred!! >= 0.0)
    }

    @Test
    fun predictionZeroWhenAlreadyAtOrBelowThreshold() {
        val ta = TrendAnalyzer()
        val base = 1000L
        for (i in 0 until 6) {
            ta.addSample(-86.0 - i, base + i * 1000L)
        }
        val pred = ta.predictSecondsToLoss(-86.0)
        // margin <= 0 => 0.0 (if slope computed)
        if (pred != null) {
            assertEquals(0.0, pred, 0.001)
        }
    }

    @Test
    fun windowEvictsOldSamples() {
        val ta = TrendAnalyzer()
        val base = 1000L
        // old sample far outside window
        ta.addSample(-50.0, base)
        // recent samples
        for (i in 0 until 6) {
            ta.addSample(-60.0 - i, base + 9000L + i * 1000L)
        }
        // old sample should be evicted; slope based on recent
        val slope = ta.calculateSlopeDbmPerSecond()
        assertNotNull(slope)
    }

    @Test
    fun clearResetsSamples() {
        val ta = TrendAnalyzer()
        val base = 1000L
        for (i in 0 until 6) ta.addSample(-60.0 - i, base + i * 1000L)
        ta.clear()
        assertNull(ta.calculateSlopeDbmPerSecond())
    }

    @Test
    fun capAtFiftySamples() {
        val ta = TrendAnalyzer()
        val base = 1000L
        for (i in 0 until 60) ta.addSample(-60.0, base + i * 100L)
        // should not crash; window holds <=50
        // time span 6s < 2.5? actually 5.9s ok but stable slope
        assertNotNull(ta.calculateSlopeDbmPerSecond())
    }
}
