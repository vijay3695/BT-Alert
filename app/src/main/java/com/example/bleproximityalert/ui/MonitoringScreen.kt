package com.example.bleproximityalert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bleproximityalert.MainViewModel
import com.example.bleproximityalert.model.ProximityState
import com.example.bleproximityalert.model.RssiProbeState

@Composable
fun MonitoringScreen(
    viewModel: MainViewModel,
    onStop: () -> Unit,
    onChangeDevice: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val thresholdConfig by viewModel.thresholdConfig.collectAsState()
    val isRssiAvailable by viewModel.isRssiAvailable.collectAsState()
    val rssiProbeState by viewModel.rssiProbeState.collectAsState()
    val rssiProbeDetails by viewModel.rssiProbeDetails.collectAsState()

    val formattedThreshold = if (thresholdConfig.warningDistanceMeters >= 1.0) {
        "${thresholdConfig.warningDistanceMeters.toInt()} m"
    } else {
        "${String.format("%.1f", thresholdConfig.warningDistanceMeters)} m"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Monitoring Active",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        selectedDevice?.let { device ->
            when {
                !device.isConnected || device.state == ProximityState.DISCONNECTED -> {
                    DisconnectedCard(
                        device = device,
                        onChangeDevice = onChangeDevice
                    )
                }
                rssiProbeState == RssiProbeState.Probing -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(device.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Probing signal capability…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                !isRssiAvailable || device.state == ProximityState.UNAVAILABLE -> {
                    UnavailableCard(
                        device = device,
                        probeDetails = rssiProbeDetails,
                        onReProbe = { viewModel.reProbeSelectedDevice() }
                    )
                }
                else -> {
                    AvailableMonitoringCard(
                        device = device,
                        formattedThreshold = formattedThreshold,
                        thresholdMeters = thresholdConfig.warningDistanceMeters
                    )
                }
            }
        }

        if (selectedDevice != null && thresholdConfig.warningDistanceMeters < 2.0 && !viewModel.isDeviceCalibrated(selectedDevice!!.address)) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Text(
                    text = "Calibrate for reliable close-range alerts.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Running in the background. Alerts will fire even with the screen off.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Stop Monitoring")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onChangeDevice,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Select Different Device")
            }
        }
    }
}

@Composable
private fun DisconnectedCard(
    device: com.example.bleproximityalert.model.BleDevice,
    onChangeDevice: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text("Device disconnected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(device.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            device.distanceMeters?.let { dist ->
                if (dist > 0) {
                    Text("Last known distance: ${String.format("%.1f", dist)} m", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Text(
                "The connection dropped. You likely walked out of range. Reconnect your earphones in Bluetooth settings, or choose another device.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onChangeDevice, modifier = Modifier.fillMaxWidth()) {
                Text("Select Different Device")
            }
        }
    }
}

@Composable
private fun UnavailableCard(
    device: com.example.bleproximityalert.model.BleDevice,
    probeDetails: String,
    onReProbe: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Connected and being watched",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "This headset does not share signal strength with Android, so there is no distance reading to show.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You will still get an alert if the connection drops, with your last known location saved.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = probeDetails,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(onClick = onReProbe, modifier = Modifier.fillMaxWidth()) {
                Text("Check Signal Again")
            }
        }
    }
}

@Composable
private fun AvailableMonitoringCard(
    device: com.example.bleproximityalert.model.BleDevice,
    formattedThreshold: String,
    thresholdMeters: Double
) {
    val stateLabel = when (device.state) {
        ProximityState.SAFE -> "Safe"
        ProximityState.MOVING_AWAY -> "Moving away"
        ProximityState.AT_LIMIT -> "At limit"
        ProximityState.LINK_RISK -> "Link risk"
        ProximityState.IMMINENT -> "Imminent"
        ProximityState.DISCONNECTED -> "Disconnected"
        ProximityState.UNAVAILABLE -> "Unavailable"
    }
    val stateIcon = when (device.state) {
        ProximityState.SAFE -> Icons.Default.Check
        ProximityState.MOVING_AWAY -> Icons.Default.ArrowForward
        else -> Icons.Default.Warning
    }
    val reduceMotion = rememberReduceMotion()

    val stateColor = animateColorAsState(
        targetValue = when (device.state) {
            ProximityState.IMMINENT -> MaterialTheme.colorScheme.error
            ProximityState.LINK_RISK -> MaterialTheme.colorScheme.error
            ProximityState.AT_LIMIT -> MaterialTheme.colorScheme.tertiary
            ProximityState.MOVING_AWAY -> MaterialTheme.colorScheme.secondary
            ProximityState.DISCONNECTED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(200),
        label = "stateColor"
    ).value

    val distanceText = if (device.distanceMeters != null && device.distanceMeters > 0) {
        String.format("~%.1f m", device.distanceMeters)
    } else "--"

    val progress = animateFloatAsState(
        targetValue = if (device.distanceMeters != null) {
            (device.distanceMeters / 10.0).toFloat().coerceIn(0f, 1f)
        } else 0f,
        animationSpec = tween(200),
        label = "progress"
    ).value

    var lastUpdateSec by remember { mutableStateOf((System.currentTimeMillis() - device.lastSeen) / 1000f) }
    LaunchedEffect(device.lastSeen) {
        while (true) {
            lastUpdateSec = (System.currentTimeMillis() - device.lastSeen) / 1000f
            kotlinx.coroutines.delay(1000L)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(device.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(12.dp))

            Crossfade(
                targetState = stateLabel,
                animationSpec = tween(200),
                label = "state"
            ) { label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(stateIcon, contentDescription = null, tint = stateColor, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = stateColor)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Estimated Distance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = distanceText,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = stateColor,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Proximity scale: current position vs alert threshold, colored by alert state.
            val threshold = thresholdMeters
            val currentDist = device.distanceMeters ?: threshold
            val scaleMax = (threshold * 3.0).coerceAtLeast(2.0)
            val fillFraction = (1f - (currentDist / scaleMax).toFloat().coerceIn(0f, 1f))
            val thresholdFraction = (1f - (threshold / scaleMax).toFloat().coerceIn(0f, 1f))
            // Use the state color so the bar matches the alert state (Safe = teal,
            // At limit = amber, Link risk/Imminent = red) instead of a raw
            // distance threshold that makes 0.5 m look red when the threshold is 3 m.
            val barColor = stateColor
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
            ) {
                val totalWidth = maxWidth
                val markerOffset = totalWidth * thresholdFraction
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Proximity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                    ) {
                        // Background track
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                        )
                        // Filled portion (distance from threshold)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fillFraction)
                                .background(barColor, shape = MaterialTheme.shapes.small)
                        )
                        // Threshold marker
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(3.dp)
                                .offset(x = markerOffset - 1.5.dp)
                                .background(MaterialTheme.colorScheme.onSurface)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Near", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Far", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            AssistChip(
                onClick = {},
                label = { Text("Confidence: ${device.confidence.name.lowercase().replaceFirstChar { it.uppercase() }}") }
            )
            val isStale = lastUpdateSec > 30f
            Text(
                text = if (isStale) "Signal lost — headset stopped broadcasting. Tap Check Signal Again."
                       else "Last update: ${String.format("%.1f", lastUpdateSec)} s ago",
                style = MaterialTheme.typography.bodySmall,
                color = if (isStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("RSSI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(device.rssi?.let { "$it dBm" } ?: "N/A", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Alert Threshold", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formattedThreshold, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Signal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when (device.confidence) {
                            com.example.bleproximityalert.model.ConfidenceLevel.HIGH -> "Strong"
                            com.example.bleproximityalert.model.ConfidenceLevel.MEDIUM -> "Fair"
                            com.example.bleproximityalert.model.ConfidenceLevel.LOW -> "Weak"
                            else -> "Unknown"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            AnimatedVisibility(
                visible = device.trendSlopeDbmPerSec != null && device.trendSlopeDbmPerSec < -1.0,
                enter = if (reduceMotion) EnterTransition.None else fadeIn(),
                exit = if (reduceMotion) ExitTransition.None else fadeOut()
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Signal dropping", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}
