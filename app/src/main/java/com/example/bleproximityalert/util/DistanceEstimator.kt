package com.example.bleproximityalert.util

import com.example.bleproximityalert.model.CalibrationModel
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object DistanceEstimator {
    private const val DEFAULT_TX_POWER = -59.0
    private const val DEFAULT_PATH_LOSS_EXPONENT = 2.2

    fun estimateDistance(
        rssi: Int?,
        calibration: CalibrationModel = CalibrationModel(deviceAddress = "")
    ): Double? {
        if (rssi == null || rssi == 0 || rssi <= -100) return null

        val txPower = if (calibration.calibrated) calibration.txPowerAtOneMeterDbm else DEFAULT_TX_POWER
        val n = if (calibration.calibrated) calibration.pathLossExponent else DEFAULT_PATH_LOSS_EXPONENT

        val rawDistance = 10.0.pow((txPower - rssi.toDouble()) / (10.0 * n))
        val clamped = rawDistance.coerceIn(0.1, 30.0)
        return (clamped * 10.0).roundToInt() / 10.0
    }

    fun estimateRange(
        filteredRssi: Double?,
        variance: Double,
        calibration: CalibrationModel = CalibrationModel(deviceAddress = "")
    ): Pair<Double, Double>? {
        if (filteredRssi == null || filteredRssi <= -100.0) return null

        val stdDev = sqrt(variance).coerceIn(1.0, 6.0)
        val txPower = if (calibration.calibrated) calibration.txPowerAtOneMeterDbm else DEFAULT_TX_POWER
        val n = if (calibration.calibrated) calibration.pathLossExponent else DEFAULT_PATH_LOSS_EXPONENT

        val lowerRssi = filteredRssi + stdDev
        val upperRssi = filteredRssi - stdDev

        val minMeters = 10.0.pow((txPower - lowerRssi) / (10.0 * n)).coerceIn(0.1, 30.0)
        val maxMeters = 10.0.pow((txPower - upperRssi) / (10.0 * n)).coerceIn(0.1, 30.0)

        val roundedMin = (minMeters * 10.0).roundToInt() / 10.0
        val roundedMax = (maxMeters * 10.0).roundToInt() / 10.0

        return Pair(roundedMin, roundedMax)
    }

    fun computeCalibrationFromPoints(
        rssiAt1m: Double,
        rssiAt3m: Double,
        d2Meters: Double = 3.0
    ): Pair<Double, Double> {
        val n = (rssiAt1m - rssiAt3m) / (10.0 * log10(d2Meters))
        val clampedN = n.coerceIn(1.2, 4.5)
        return Pair(rssiAt1m, clampedN)
    }
}
