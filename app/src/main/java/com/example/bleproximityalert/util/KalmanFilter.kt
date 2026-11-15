package com.example.bleproximityalert.util

import com.example.bleproximityalert.model.ConfidenceLevel
import kotlin.math.sqrt

class KalmanFilter(
    private var processNoiseQ: Double = 0.08,
    private var measurementNoiseR: Double = 4.0
) {
    private var estimate: Double? = null
    private var errorCovarianceP: Double = 1.0

    private val rawWindow = mutableListOf<Double>()
    private val filteredHistory = mutableListOf<Double>()

    fun applyFilter(measurement: Double): Double {
        // 1. Moving Median filter over last 5 raw samples to reject outliers
        rawWindow.add(measurement)
        if (rawWindow.size > 5) {
            rawWindow.removeAt(0)
        }
        val medianMeasurement = rawWindow.sorted()[rawWindow.size / 2]

        // 2. Kalman Filter update
        val currentEst = estimate
        if (currentEst == null) {
            estimate = medianMeasurement
            errorCovarianceP = 1.0
            filteredHistory.add(medianMeasurement)
            return medianMeasurement
        }

        val priorEst = currentEst
        val priorCov = errorCovarianceP + processNoiseQ

        val kalmanGain = priorCov / (priorCov + measurementNoiseR)
        val newEst = priorEst + kalmanGain * (medianMeasurement - priorEst)
        errorCovarianceP = (1.0 - kalmanGain) * priorCov

        estimate = newEst
        filteredHistory.add(newEst)
        if (filteredHistory.size > 30) {
            filteredHistory.removeAt(0)
        }

        return newEst
    }

    fun computeVariance(): Double {
        if (filteredHistory.size < 3) return 4.0
        val mean = filteredHistory.average()
        val sumSquares = filteredHistory.sumOf { (it - mean) * (it - mean) }
        return sumSquares / (filteredHistory.size - 1)
    }

    fun computeConfidence(sampleCount: Int, isCalibrated: Boolean): ConfidenceLevel {
        if (sampleCount < 5) return ConfidenceLevel.LOW
        val variance = computeVariance()
        val stdDev = sqrt(variance)

        return when {
            isCalibrated && stdDev <= 2.0 -> ConfidenceLevel.HIGH
            stdDev <= 3.5 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    fun reset() {
        estimate = null
        errorCovarianceP = 1.0
        rawWindow.clear()
        filteredHistory.clear()
    }
}
