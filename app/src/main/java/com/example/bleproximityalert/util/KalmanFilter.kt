package com.example.bleproximityalert.util

class KalmanFilter(
    private var processNoiseQ: Double = 0.08,
    private var measurementNoiseR: Double = 4.0
) {
    private var estimatedRssi: Double? = null
    private var errorCovarianceP: Double = 1.0

    fun applyFilter(measurement: Double): Double {
        val currentEst = estimatedRssi
        if (currentEst == null) {
            estimatedRssi = measurement
            errorCovarianceP = 1.0
            return measurement
        }

        val priorEst = currentEst
        val priorCov = errorCovarianceP + processNoiseQ

        val kalmanGain = priorCov / (priorCov + measurementNoiseR)
        val newEst = priorEst + kalmanGain * (measurement - priorEst)
        errorCovarianceP = (1.0 - kalmanGain) * priorCov

        estimatedRssi = newEst
        return newEst
    }

    fun reset() {
        estimatedRssi = null
        errorCovarianceP = 1.0
    }
}
