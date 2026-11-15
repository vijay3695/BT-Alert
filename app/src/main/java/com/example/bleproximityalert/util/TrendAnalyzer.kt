package com.example.bleproximityalert.util

data class TimedRssi(
    val timeMs: Long,
    val rssi: Double
)

class TrendAnalyzer {
    private val samples = mutableListOf<TimedRssi>()

    fun addSample(rssi: Double, timestampMs: Long = System.currentTimeMillis()) {
        samples.add(TimedRssi(timestampMs, rssi))
        val cutoff = timestampMs - WINDOW_DURATION_MS
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) {
            samples.removeAt(0)
        }
        while (samples.size > 50) {
            samples.removeAt(0)
        }
    }

    fun calculateSlopeDbmPerSecond(): Double? {
        if (samples.size < 4) return null
        val t0 = samples.first().timeMs
        val xs = samples.map { (it.timeMs - t0) / 1000.0 }
        val ys = samples.map { it.rssi }

        if ((xs.last() - xs.first()) < 2.5) return null

        val xMean = xs.average()
        val yMean = ys.average()

        val numerator = xs.indices.sumOf { (xs[it] - xMean) * (ys[it] - yMean) }
        val denominator = xs.indices.sumOf { (xs[it] - xMean) * (xs[it] - xMean) }

        if (denominator == 0.0) return null
        return numerator / denominator
    }

    fun predictSecondsToLoss(
        currentRssi: Double,
        lossThresholdDbm: Double = -85.0
    ): Double? {
        val slope = calculateSlopeDbmPerSecond() ?: return null
        if (slope >= -0.2) return null // Signal is stable or improving

        val margin = currentRssi - lossThresholdDbm
        if (margin <= 0.0) return 0.0

        val seconds = margin / (-slope)
        return Math.round(seconds * 10.0) / 10.0
    }

    fun clear() {
        samples.clear()
    }

    companion object {
        const val WINDOW_DURATION_MS = 8000L
    }
}
