package com.example.bleproximityalert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bleproximityalert.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val thresholdConfig by viewModel.thresholdConfig.collectAsState()
    val voiceEnabled by viewModel.voiceEnabled.collectAsState()
    val voiceSpeed by viewModel.voiceSpeed.collectAsState()
    val voiceCooldown by viewModel.voiceCooldownSeconds.collectAsState()
    val powerMode by viewModel.powerMode.collectAsState()
    val bgLocation by viewModel.backgroundLocationEnabled.collectAsState()
    val sensitivity by viewModel.sensitivity.collectAsState()
    val autoMonitor by viewModel.autoMonitorOnConnect.collectAsState()
    val distances = listOf(9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0, 0.5, 0.1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = "Warning Distance Threshold",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            items(distances.size) { idx ->
                val distance = distances[idx]
                val isSelected = thresholdConfig.warningDistanceMeters == distance
                val labelText = if (distance >= 1.0) "${distance.toInt()} meters" else "$distance meters"
                Card(
                    onClick = { viewModel.updateThreshold(distance) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSelected, onClick = { viewModel.updateThreshold(distance) })
                        Spacer(modifier = Modifier.height(0.dp).weight(1f))
                        Text(labelText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Auto-monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-monitor on connect", fontWeight = FontWeight.Medium)
                                Text(
                                    "Start monitoring automatically when your selected headset connects.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = autoMonitor, onCheckedChange = { viewModel.setAutoMonitorOnConnect(it) })
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Voice alerts enabled", modifier = Modifier.weight(1f))
                            Switch(checked = voiceEnabled, onCheckedChange = { viewModel.setVoiceEnabled(it) })
                        }
                        Text("Speed: ${String.format("%.1f", voiceSpeed)}")
                        Slider(value = voiceSpeed, onValueChange = { viewModel.setVoiceSpeed(it) }, valueRange = 0.5f..1.5f)
                        Text("Cooldown: ${voiceCooldown}s")
                        Slider(value = voiceCooldown.toFloat(), onValueChange = { viewModel.setVoiceCooldownSeconds(it.toInt()) }, valueRange = 10f..120f)
                        OutlinedButton(onClick = { viewModel.testTtsVoice() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null)
                            Spacer(modifier = Modifier.padding(start = 6.dp))
                            Text("Test Voice")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Power mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        listOf("Battery saver", "Balanced", "Responsive").forEach { mode ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = powerMode == mode, onClick = { viewModel.setPowerMode(mode) })
                                Text(mode)
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Location privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Save location on disconnect", modifier = Modifier.weight(1f))
                            Switch(checked = bgLocation, onCheckedChange = { viewModel.setBackgroundLocationEnabled(it) })
                        }
                        Text("Location data is stored only on this phone; nothing is shared.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Sensitivity (debounce seconds): $sensitivity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Slider(value = sensitivity.toFloat(), onValueChange = { viewModel.setSensitivity(it.toInt()) }, valueRange = 1f..10f)
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenCalibration, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.padding(start = 6.dp))
                        Text("Headset Distance Calibration Wizard")
                    }
                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(modifier = Modifier.padding(start = 6.dp))
                        Text("Headset Diagnostics & RSSI Probe")
                    }
                    OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Place, contentDescription = null)
                        Spacer(modifier = Modifier.padding(start = 6.dp))
                        Text("Disconnect History & Last Location")
                    }
                }
            }
        }
    }
}