package com.example.bleproximityalert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bleproximityalert.MainViewModel
import com.example.bleproximityalert.model.CalibrationModel
import com.example.bleproximityalert.util.DistanceEstimator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationWizardScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(1) } // 1: 1m sample, 2: 3m sample, 3: result
    var isSampling by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(10) }
    var sampleCount by remember { mutableStateOf(0) }
    var targetSamples by remember { mutableStateOf(30) }

    var rssiAt1m by remember { mutableStateOf(-59.0) }
    var rssiAt3m by remember { mutableStateOf(-75.0) }
    var computedN by remember { mutableStateOf(2.2) }

    val devAddr = selectedDevice?.address ?: ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Headset Calibration Wizard") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Calibrate for ${selectedDevice?.name ?: "Headset"}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Custom calibration tunes path-loss exponents to eliminate false alerts at tight distances (e.g. 1m).",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                when (step) {
                    1 -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Step 1: 1 Meter Sampling", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Place your phone exactly 1 meter away from your headphones and hold still.", textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(16.dp))
                                if (isSampling) {
                                    Text("Sampling... $countdown s", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text("Collected $sampleCount / $targetSamples", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Sample 1m RSSI", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    2 -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Step 2: 3 Meter Sampling", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Now move your phone to 3 meters away from your headphones and hold still.", textAlign = TextAlign.Center)
                                Spacer(modifier = Modifier.height(16.dp))
                                if (isSampling) {
                                    Text("Sampling... $countdown s", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text("Collected $sampleCount / $targetSamples", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Sample 3m RSSI", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    3 -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Calibration Result", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("1m TxPower: ${rssiAt1m.toInt()} dBm")
                                Text("3m RSSI: ${rssiAt3m.toInt()} dBm")
                                Text("Computed Path Loss Exponent (n): ${String.format("%.2f", computedN)}")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Status: Calibrated for High Accuracy", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (step == 1) {
                    Button(
                        enabled = !isSampling,
                        onClick = {
                            isSampling = true
                            countdown = 10
                            sampleCount = 0
                            val samples = mutableListOf<Double>()
                            scope.launch {
                                val end = System.currentTimeMillis() + 10_000L
                                while (System.currentTimeMillis() < end) {
                                    countdown = ((end - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                                    selectedDevice?.rssi?.let { samples.add(it.toDouble()) }
                                    sampleCount = samples.size
                                    delay(333L)
                                }
                                rssiAt1m = if (samples.isNotEmpty()) {
                                    val sorted = samples.sorted()
                                    val mid = sorted.size / 2
                                    if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
                                } else (selectedDevice?.rssi ?: -58).toDouble()
                                isSampling = false
                                step = 2
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start 1m Sampling (10s)")
                    }
                } else if (step == 2) {
                    Button(
                        enabled = !isSampling,
                        onClick = {
                            isSampling = true
                            countdown = 10
                            sampleCount = 0
                            val samples = mutableListOf<Double>()
                            scope.launch {
                                val end = System.currentTimeMillis() + 10_000L
                                while (System.currentTimeMillis() < end) {
                                    countdown = ((end - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                                    selectedDevice?.rssi?.let { samples.add(it.toDouble()) }
                                    sampleCount = samples.size
                                    delay(333L)
                                }
                                rssiAt3m = if (samples.isNotEmpty()) {
                                    val sorted = samples.sorted()
                                    val mid = sorted.size / 2
                                    if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
                                } else (selectedDevice?.rssi ?: -74).toDouble()
                                val computed = DistanceEstimator.computeCalibrationFromPoints(rssiAt1m, rssiAt3m, 3.0)
                                computedN = computed.second
                                isSampling = false
                                step = 3
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start 3m Sampling (10s)")
                    }
                } else {
                    Button(
                        onClick = {
                            val model = CalibrationModel(
                                deviceAddress = devAddr,
                                txPowerAtOneMeterDbm = rssiAt1m,
                                pathLossExponent = computedN,
                                calibrated = true
                            )
                            viewModel.saveCalibration(model)
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Calibration")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}
