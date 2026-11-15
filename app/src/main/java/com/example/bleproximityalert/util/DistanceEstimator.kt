package com.example.bleproximityalert.util

import kotlin.math.pow

object DistanceEstimator {
    private const val DEFAULT_TX_POWER = -59.0
    private const val DEFAULT_PATH_LOSS_EXPONENT = 2.2

    fun estimateDistance(rssi: Int, txPower: Double = DEFAULT_TX_POWER, n: Double = DEFAULT_PATH_LOSS_EXPONENT): Double {
        if (rssi == 0 || rssi <= -100) {
            return -1.0
        }
        val rawDistance = 10.0.pow((txPower - rssi.toDouble()) / (10.0 * n))
        val clamped = rawDistance.coerceIn(0.1, 30.0)
        return Math.round(clamped * 10.0) / 10.0
    }
}
