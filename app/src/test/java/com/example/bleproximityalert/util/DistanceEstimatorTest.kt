package com.example.bleproximityalert.util

import com.example.bleproximityalert.model.CalibrationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceEstimatorTest {

    @Test
    fun nullRssiReturnsNull() {
        assertNull(DistanceEstimator.estimateDistance(null as Int?))
    }

    @Test
    fun zeroRssiReturnsNull() {
        assertNull(DistanceEstimator.estimateDistance(0))
    }

    @Test
    fun veryWeakRssiReturnsNull() {
        assertNull(DistanceEstimator.estimateDistance(-100))
    }

    @Test
    fun tooWeakBelowThresholdReturnsNull() {
        // rssi <= -100 condition: -101 <= -100 true -> null
        assertNull(DistanceEstimator.estimateDistance(-101))
    }

    @Test
    fun strongSignalClampsToMin() {
        // rssi = -30 -> raw distance tiny -> clamped 0.1
        val d = DistanceEstimator.estimateDistance(-30)
        assertNotNull(d)
        assertEquals(0.1, d!!, 0.001)
    }

    @Test
    fun weakSignalClampsToMax() {
        // rssi = -92 -> raw distance ~31.6m -> clamped 30.0
        val d = DistanceEstimator.estimateDistance(-92)
        assertNotNull(d)
        assertEquals(30.0, d!!, 0.001)
    }

    @Test
    fun midRangeDistanceComputed() {
        // default txPower -59, n 2.2; rssi -59 => 10^(0/(22))=1.0m
        val d = DistanceEstimator.estimateDistance(-59)
        assertNotNull(d)
        assertEquals(1.0, d!!, 0.05)
    }

    @Test
    fun calibrationAppliedWhenCalibrated() {
        val cal = CalibrationModel(
            deviceAddress = "aa",
            txPowerAtOneMeterDbm = -50.0,
            pathLossExponent = 2.0,
            calibrated = true
        )
        val d = DistanceEstimator.estimateDistance(-59, cal)
        assertNotNull(d)
        // with txPower -50, rssi -59: 10^(( -50 - (-59))/(20)) = 10^(9/20)=~2.82m
        assertTrue(d!! > 2.0 && d < 3.5)
    }

    @Test
    fun uncalibratedUsesDefaults() {
        val cal = CalibrationModel(deviceAddress = "aa", calibrated = false)
        val d1 = DistanceEstimator.estimateDistance(-59, cal)
        val d2 = DistanceEstimator.estimateDistance(-59)
        assertEquals(d1, d2)
    }

    @Test
    fun resolutionOneDecimal() {
        val d = DistanceEstimator.estimateDistance(-65)
        assertNotNull(d)
        // check one decimal
        val scaled = Math.round(d!! * 10.0)
        assertEquals(d, scaled / 10.0, 0.0001)
    }

    @Test
    fun estimateRangeReturnsPair() {
        val r = DistanceEstimator.estimateRange(-60.0, 4.0)
        assertNotNull(r)
        assertTrue(r!!.first <= r.second)
    }

    @Test
    fun estimateRangeNullForWeak() {
        assertNull(DistanceEstimator.estimateRange(-101.0, 4.0))
    }

    @Test
    fun computeCalibrationFromPoints() {
        val (tx, n) = DistanceEstimator.computeCalibrationFromPoints(-59.0, -70.0, 3.0)
        assertEquals(-59.0, tx, 0.001)
        assertTrue(n in 1.2..4.5)
    }

    @Test
    fun computeCalibrationClampsN() {
        val (_, n) = DistanceEstimator.computeCalibrationFromPoints(-59.0, -59.0, 3.0)
        // zero slope -> n=0 -> clamped to 1.2
        assertEquals(1.2, n, 0.001)
    }
}
