package com.example.bleproximityalert.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KalmanFilterTest {

    @Test
    fun firstMeasurementReturnedAsIs() {
        val kf = KalmanFilter()
        val out = kf.applyFilter(-60.0)
        assertEquals(-60.0, out, 0.001)
    }

    @Test
    fun convergenceToStableSignal() {
        val kf = KalmanFilter()
        var last = 0.0
        for (i in 0 until 30) {
            last = kf.applyFilter(-60.0)
        }
        assertEquals(-60.0, last, 1.0)
    }

    @Test
    fun smoothsNoisySignal() {
        val kf = KalmanFilter()
        val noisy = listOf(-55.0, -70.0, -58.0, -62.0, -57.0, -65.0, -59.0, -61.0)
        var last = 0.0
        for (v in noisy) last = kf.applyFilter(v)
        // filtered value should be within reasonable range around -60
        assertTrue("filtered=$last", last > -70.0 && last < -50.0)
    }

    @Test
    fun resetClearsState() {
        val kf = KalmanFilter()
        for (i in 0 until 10) kf.applyFilter(-60.0)
        kf.reset()
        // after reset first sample returned as-is
        assertEquals(-75.0, kf.applyFilter(-75.0), 0.001)
    }

    @Test
    fun varianceLowForStable() {
        val kf = KalmanFilter()
        for (i in 0 until 10) kf.applyFilter(-60.0)
        assertTrue(kf.computeVariance() < 1.0)
    }

    @Test
    fun varianceHigherForNoisy() {
        val kf = KalmanFilter()
        val vals = listOf(-50.0, -70.0, -55.0, -75.0, -52.0, -68.0)
        for (v in vals) kf.applyFilter(v)
        assertTrue(kf.computeVariance() > 1.0)
    }

    @Test
    fun confidenceLowForFewSamples() {
        val kf = KalmanFilter()
        kf.applyFilter(-60.0)
        assertEquals(com.example.bleproximityalert.model.ConfidenceLevel.LOW, kf.computeConfidence(3, false))
    }

    @Test
    fun confidenceMediumOrHighForStableCalibrated() {
        val kf = KalmanFilter()
        for (i in 0 until 10) kf.applyFilter(-60.0)
        val c = kf.computeConfidence(10, true)
        assertTrue(c == com.example.bleproximityalert.model.ConfidenceLevel.HIGH || c == com.example.bleproximityalert.model.ConfidenceLevel.MEDIUM)
    }
}
