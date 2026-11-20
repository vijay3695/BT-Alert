package com.example.bleproximityalert.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                        formattedThreshold = formattedThreshold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Running in the background — alerts will fire even with the screen off.",
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
                "The connection dropped — you likely walked out of range. Reconnect your earphones in Bluetooth settings, or choose another device.",
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Live Distance Not Available for ${device.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = probeDetails,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Connection monitoring stays active: if your device disconnects, the app saves your last GPS location and notifies you.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onReProbe, modifier = Modifier.fillMaxWidth()) {
                Text("Check Signal Again")
            }
        }
    }
}

@Composable
private fun AvailableMonitoringCard(
    device: com.example.bleproximityalert.model.BleDevice,
    formattedThreshold: String
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
    val stateColor = when (device.state) {
        ProximityState.IMMINENT -> MaterialTheme.colorScheme.error
        ProximityState.LINK_RISK -> MaterialTheme.colorScheme.error
        ProximityState.AT_LIMIT -> MaterialTheme.colorScheme.tertiary
        ProximityState.MOVING_AWAY -> MaterialTheme.colorScheme.secondary
        ProximityState.DISCONNECTED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(device.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(stateIcon, contentDescription = null, tint = stateColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stateLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = stateColor)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Estimated Distance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = device.distanceMeters?.let { String.format("%.1f m", it) } ?: "--",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = stateColor,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
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

            if (device.trendSlopeDbmPerSec != null && device.trendSlopeDbmPerSec < -1.0) {
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