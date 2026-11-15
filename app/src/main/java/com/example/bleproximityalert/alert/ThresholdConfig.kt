package com.example.bleproximityalert.alert

data class ThresholdConfig(
    val warningDistanceMeters: Double = 2.0
) {
    fun getAlertLevel(distanceMeters: Double?, rssi: Int?): AlertLevel {
        if (distanceMeters == null || distanceMeters < 0) return AlertLevel.NORMAL

        val isCriticalSetting = warningDistanceMeters <= 0.5

        return when {
            (rssi != null && rssi <= -84) || distanceMeters >= 9.0 -> AlertLevel.IMMINENT_DISCONNECT
            distanceMeters >= warningDistanceMeters -> {
                if (isCriticalSetting || distanceMeters >= 7.0 || (rssi != null && rssi <= -80)) {
                    AlertLevel.CRITICAL
                } else {
                    AlertLevel.WARNING
                }
            }
            else -> AlertLevel.NORMAL
        }
    }
}

enum class AlertLevel {
    NORMAL,
    WARNING,
    CRITICAL,
    IMMINENT_DISCONNECT
}
