package com.example.bleproximityalert.alert

import com.example.bleproximityalert.model.ProximityState
import com.example.bleproximityalert.util.TrendAnalyzer

data class AlertEvaluation(
    val state: ProximityState,
    val shouldAlert: Boolean,
    val distanceMeters: Double?,
    val trendSlopeDbmPerSec: Double?,
    val predictedSecondsToLoss: Double?,
    val voiceMessage: String?
)

class AlertEngine(val config: ThresholdConfig = ThresholdConfig()) {
    private val trendAnalyzer = TrendAnalyzer()

    private var currentState: ProximityState = ProximityState.SAFE
    private var candidateState: ProximityState = ProximityState.SAFE
    private var candidateStateStartMs: Long = 0L

    private var lastAlertTimestampMs: Long = 0L
    private var lastAlertState: ProximityState = ProximityState.SAFE

    fun evaluate(
        address: String,
        deviceName: String,
        distanceMeters: Double?,
        rssi: Int?,
        timestampMs: Long = System.currentTimeMillis(),
        isConnected: Boolean,
        isRssiAvailable: Boolean
    ): AlertEvaluation {
        if (!isConnected) {
            resetEngine()
            return AlertEvaluation(
                state = ProximityState.DISCONNECTED,
                shouldAlert = false,
                distanceMeters = null,
                trendSlopeDbmPerSec = null,
                predictedSecondsToLoss = null,
                voiceMessage = null
            )
        }

        if (!isRssiAvailable || rssi == null || distanceMeters == null || distanceMeters < 0) {
            return AlertEvaluation(
                state = ProximityState.UNAVAILABLE,
                shouldAlert = false,
                distanceMeters = null,
                trendSlopeDbmPerSec = null,
                predictedSecondsToLoss = null,
                voiceMessage = null
            )
        }

        trendAnalyzer.addSample(rssi.toDouble(), timestampMs)
        val slope = trendAnalyzer.calculateSlopeDbmPerSecond()
        val secondsToLoss = trendAnalyzer.predictSecondsToLoss(rssi.toDouble())

        // Signal-trend alerts (LINK_RISK / IMMINENT) only matter when you're
        // already in the warning zone. Otherwise moving the phone a short distance
        // (e.g. 0.4 m -> 3 m with a 6 m threshold) triggers a false warning.
        val inWarningZone = distanceMeters >= config.warningDistanceMeters * 0.6
        val rawTargetState = when {
            rssi <= -85 || (inWarningZone && secondsToLoss != null && secondsToLoss <= 7.0) || distanceMeters >= 9.0 -> {
                ProximityState.IMMINENT
            }
            inWarningZone && secondsToLoss != null && secondsToLoss <= 20.0 -> {
                ProximityState.LINK_RISK
            }
            distanceMeters >= config.warningDistanceMeters ||
                    (distanceMeters >= config.warningDistanceMeters * 0.85 && slope != null && slope < -1.0) -> {
                ProximityState.AT_LIMIT
            }
            slope != null && slope < -1.0 && distanceMeters >= config.warningDistanceMeters * 0.7 -> {
                ProximityState.MOVING_AWAY
            }
            else -> {
                // Hysteresis: only exit AT_LIMIT/MOVING_AWAY when distance is <= warningDistance * 0.85
                if ((currentState == ProximityState.AT_LIMIT || currentState == ProximityState.MOVING_AWAY) &&
                    distanceMeters > config.warningDistanceMeters * 0.85
                ) {
                    currentState
                } else {
                    ProximityState.SAFE
                }
            }
        }

        // Debounce logic: Candidate condition must remain true for required duration
        val requiredDebounceMs = when (rawTargetState) {
            ProximityState.IMMINENT -> 1000L
            ProximityState.LINK_RISK -> 1500L
            ProximityState.AT_LIMIT, ProximityState.MOVING_AWAY -> 2000L
            else -> 0L
        }

        if (rawTargetState != candidateState) {
            candidateState = rawTargetState
            candidateStateStartMs = timestampMs
        }

        val stateSustainedDuration = timestampMs - candidateStateStartMs
        if (stateSustainedDuration >= requiredDebounceMs) {
            currentState = rawTargetState
        }

        // Cooldown evaluation per state
        val cooldownMs = when (currentState) {
            ProximityState.IMMINENT -> 10000L
            ProximityState.LINK_RISK -> 20000L
            ProximityState.AT_LIMIT -> 30000L
            ProximityState.MOVING_AWAY -> 45000L
            else -> 60000L
        }

        val stateEscalated = currentState.ordinal > lastAlertState.ordinal
        val cooldownPassed = (timestampMs - lastAlertTimestampMs) > cooldownMs

        val shouldAlert = (currentState != ProximityState.SAFE && currentState != ProximityState.UNAVAILABLE) &&
                (stateEscalated || cooldownPassed)

        var voiceMessage: String? = null
        if (shouldAlert) {
            lastAlertTimestampMs = timestampMs
            lastAlertState = currentState
            voiceMessage = buildPoliteVoicePhrase(currentState, deviceName, distanceMeters)
        } else if (currentState == ProximityState.SAFE) {
            lastAlertState = ProximityState.SAFE
        }

        return AlertEvaluation(
            state = currentState,
            shouldAlert = shouldAlert,
            distanceMeters = distanceMeters,
            trendSlopeDbmPerSec = slope,
            predictedSecondsToLoss = secondsToLoss,
            voiceMessage = voiceMessage
        )
    }

    private fun buildPoliteVoicePhrase(
        state: ProximityState,
        deviceName: String,
        distanceMeters: Double
    ): String {
        val distText = String.format("%.1f", distanceMeters)
        return when (state) {
            ProximityState.MOVING_AWAY -> {
                "Your headphones are getting farther away. Current distance is about $distText meters. Please keep your phone nearby."
            }
            ProximityState.AT_LIMIT -> {
                "You are near your headphone alert distance of $distText meters. Please keep your phone with you."
            }
            ProximityState.LINK_RISK -> {
                "The headphone connection is getting weak at $distText meters. You may lose audio soon."
            }
            ProximityState.IMMINENT -> {
                "Please move closer to your headphones to avoid losing the connection."
            }
            else -> ""
        }
    }

    fun resetEngine() {
        trendAnalyzer.clear()
        currentState = ProximityState.SAFE
        candidateState = ProximityState.SAFE
        candidateStateStartMs = 0L
        lastAlertTimestampMs = 0L
        lastAlertState = ProximityState.SAFE
    }
}
