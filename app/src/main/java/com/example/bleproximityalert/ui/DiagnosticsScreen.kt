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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bleproximityalert.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isRssiAvailable by viewModel.isRssiAvailable.collectAsState()
    val probeDetails by viewModel.rssiProbeDetails.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Headset Diagnostics") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Headset Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Selected Device: ${selectedDevice?.name ?: "None"}")
                        Text("Address: ${selectedDevice?.address ?: "N/A"}")
                        Text("Connected: ${selectedDevice?.isConnected ?: false}")
                        Text("Current Audio Output: ${selectedDevice?.isCurrentAudioOutput ?: false}")
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("RSSI Capability Probe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("RSSI Available: $isRssiAvailable")
                        Text("Details: $probeDetails")
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Live Metrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Raw/Filtered RSSI: ${selectedDevice?.rssi?.let { "$it dBm" } ?: "—"}")
                        Text("Estimated Distance: ${selectedDevice?.distanceMeters?.let { String.format("%.1f m", it) } ?: "—"}")
                        Text("Proximity State: ${selectedDevice?.state ?: "N/A"}")
                        Text("Trend Slope: ${selectedDevice?.trendSlopeDbmPerSec?.let { String.format("%.2f dBm/s", it) } ?: "N/A"}")
Text("Signal Source: ${selectedDevice?.rssiSourceLabel ?: "—"}")
Text("Confidence: ${selectedDevice?.confidence?.name ?: "—"}")
Text("Range: ${if (selectedDevice?.minDistanceMeters != null && selectedDevice?.maxDistanceMeters != null) "${String.format("%.1f", selectedDevice!!.minDistanceMeters)} – ${String.format("%.1f", selectedDevice!!.maxDistanceMeters)} m" else "—"}")
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.testTtsVoice() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test In-Ear Voice Alert")
                    }
                    OutlinedButton(
                        onClick = { selectedDevice?.let { viewModel.selectDevice(it.address, it.name, it.isCurrentAudioOutput) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Re-Run Capability Probe")
                    }
                }
            }
        }
    }
}
