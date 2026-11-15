package com.example.bleproximityalert.alert

data class RssiSample(
    val rssi: Int,
    val timestampMs: Long
)

data class DeviceSignalState(
    val address: String,
    val samples: MutableList<RssiSample> = mutableListOf(),
    var lastAlertLevel: AlertLevel = AlertLevel.NORMAL,
    var lastAlertTimestampMs: Long = 0L
) {
    fun addSample(rssi: Int, timestampMs: Long) {
        samples.add(RssiSample(rssi, timestampMs))
        val cutoff = timestampMs - RSSI_DEGRADE_WINDOW_MS
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeAt(0)
        }
        while (samples.size > 50) {
            samples.removeAt(0)
        }
    }

    fun isRssiDegrading(): Boolean {
        if (samples.size < 2) return false
        val recent = samples.takeLast(5)
        val first = recent.first().rssi
        val last = recent.last().rssi
        return (first - last) >= RSSI_DEGRADE_THRESHOLD_DBM
    }

    fun clear() {
        samples.clear()
        lastAlertLevel = AlertLevel.NORMAL
        lastAlertTimestampMs = 0L
    }

    companion object {
        const val RSSI_DEGRADE_WINDOW_MS = 3000L
        const val RSSI_DEGRADE_THRESHOLD_DBM = 4
    }
}

class AlertEngine(val config: ThresholdConfig = ThresholdConfig()) {
    private val states = mutableMapOf<String, DeviceSignalState>()

    fun evaluate(
        address: String,
        distanceMeters: Double?,
        rssi: Int,
        timestampMs: Long,
        isConnected: Boolean
    ): AlertEvaluation {
        if (!isConnected || distanceMeters == null || distanceMeters < 0) {
            return AlertEvaluation(
                level = AlertLevel.NORMAL,
                shouldAlert = false,
                trendDegrading = false,
                distanceMeters = distanceMeters
            )
        }

        val state = states.getOrPut(address) { DeviceSignalState(address) }
        state.addSample(rssi, timestampMs)

        val baseLevel = config.getAlertLevel(distanceMeters, rssi)
        val trendDegrading = state.isRssiDegrading()

        val effectiveLevel = when {
            baseLevel == AlertLevel.IMMINENT_DISCONNECT -> AlertLevel.IMMINENT_DISCONNECT
            baseLevel == AlertLevel.CRITICAL -> AlertLevel.CRITICAL
            baseLevel == AlertLevel.WARNING && trendDegrading -> AlertLevel.CRITICAL
            baseLevel == AlertLevel.WARNING -> AlertLevel.WARNING
            trendDegrading && (distanceMeters >= config.warningDistanceMeters * 0.8) -> AlertLevel.WARNING
            else -> AlertLevel.NORMAL
        }

        val levelEscalated = effectiveLevel.ordinal > state.lastAlertLevel.ordinal
        val cooldownPassed = (timestampMs - state.lastAlertTimestampMs) > 5000L

        val shouldAlert = effectiveLevel != AlertLevel.NORMAL && (levelEscalated || cooldownPassed)

        val evaluation = AlertEvaluation(
            level = effectiveLevel,
            shouldAlert = shouldAlert,
            trendDegrading = trendDegrading,
            distanceMeters = distanceMeters
        )

        if (shouldAlert) {
            state.lastAlertLevel = effectiveLevel
            state.lastAlertTimestampMs = timestampMs
        } else if (effectiveLevel == AlertLevel.NORMAL) {
            state.lastAlertLevel = AlertLevel.NORMAL
        }

        return evaluation
    }

    fun reset(address: String) {
        states.remove(address)?.clear()
    }

    fun resetAll() {
        states.values.forEach { it.clear() }
        states.clear()
    }
}

data class AlertEvaluation(
    val level: AlertLevel,
    val shouldAlert: Boolean,
    val trendDegrading: Boolean,
    val distanceMeters: Double?
)
