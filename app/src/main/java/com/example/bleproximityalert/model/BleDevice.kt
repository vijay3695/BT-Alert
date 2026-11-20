package com.example.bleproximityalert.model

enum class ProximityState {
    UNAVAILABLE,
    SAFE,
    MOVING_AWAY,
    AT_LIMIT,
    LINK_RISK,
    IMMINENT,
    DISCONNECTED
}

enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}

enum class RssiProbeState {
    Probing,
    Available,
    Unavailable
}

data class BleDevice(
    val address: String,
    val name: String,
    val rssi: Int? = null,
    val distanceMeters: Double? = null,
    val minDistanceMeters: Double? = null,
    val maxDistanceMeters: Double? = null,
    val confidence: ConfidenceLevel = ConfidenceLevel.NONE,
    val state: ProximityState = ProximityState.UNAVAILABLE,
    val trendSlopeDbmPerSec: Double? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val isConnected: Boolean = false,
    val isCurrentAudioOutput: Boolean = false,
    val rssiSourceLabel: String = "None"
)

data class ConnectedAudioDevice(
    val name: String,
    val address: String,
    val supportsA2dp: Boolean,
    val supportsHeadset: Boolean,
    val isCurrentAudioOutput: Boolean
)

data class CalibrationModel(
    val deviceAddress: String,
    val txPowerAtOneMeterDbm: Double = -59.0,
    val pathLossExponent: Double = 2.2,
    val calibrated: Boolean = false,
    val learnedDisconnectRssiDbm: Double? = null
)

data class DisconnectEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val deviceName: String,
    val deviceAddress: String,
    val lastRssi: Int?,
    val lastDistanceMeters: Double?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null
)
