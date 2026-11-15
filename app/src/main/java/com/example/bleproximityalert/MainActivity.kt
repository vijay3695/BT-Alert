package com.example.bleproximityalert

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.bleproximityalert.ui.AlertScreen
import com.example.bleproximityalert.ui.DeviceSelectionScreen
import com.example.bleproximityalert.ui.MonitoringScreen
import com.example.bleproximityalert.ui.PermissionRequestScreen
import com.example.bleproximityalert.ui.SettingsScreen
import com.example.bleproximityalert.ui.StartMonitoringScreen
import com.example.bleproximityalert.util.PermissionHelper

enum class AppScreen {
    DEVICE_SELECTION,
    START_MONITORING,
    MONITORING,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.checkPermissions()
        if (viewModel.hasPermissions.value) {
            viewModel.loadPairedAudioDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val hasPermissions by viewModel.hasPermissions.collectAsState()
                val selectedDevice by viewModel.selectedDevice.collectAsState()
                val isMonitoring by viewModel.isMonitoring.collectAsState()
                val alertDevice by viewModel.alertDevice.collectAsState()

                var activeScreen by remember { mutableStateOf(AppScreen.DEVICE_SELECTION) }

                when {
                    !hasPermissions -> {
                        PermissionRequestScreen(onRequest = { requestPermissions() })
                    }

                    alertDevice != null -> {
                        AlertScreen(
                            viewModel = viewModel,
                            onDismiss = { viewModel.dismissAlert() }
                        )
                    }

                    activeScreen == AppScreen.SETTINGS -> {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = {
                                activeScreen = if (selectedDevice != null) {
                                    if (isMonitoring) AppScreen.MONITORING else AppScreen.START_MONITORING
                                } else {
                                    AppScreen.DEVICE_SELECTION
                                }
                            }
                        )
                    }

                    selectedDevice == null -> {
                        DeviceSelectionScreen(
                            viewModel = viewModel,
                            onDeviceSelected = { device ->
                                viewModel.selectDevice(device)
                                activeScreen = AppScreen.START_MONITORING
                            },
                            onOpenSettings = { activeScreen = AppScreen.SETTINGS }
                        )
                    }

                    !isMonitoring -> {
                        StartMonitoringScreen(
                            viewModel = viewModel,
                            selectedDevice = selectedDevice!!,
                            onStart = {
                                viewModel.startMonitoring()
                                activeScreen = AppScreen.MONITORING
                            },
                            onChangeDevice = {
                                viewModel.clearSelectedDevice()
                                activeScreen = AppScreen.DEVICE_SELECTION
                            },
                            onOpenSettings = { activeScreen = AppScreen.SETTINGS }
                        )
                    }

                    else -> {
                        MonitoringScreen(
                            viewModel = viewModel,
                            onStop = {
                                viewModel.stopMonitoring()
                                activeScreen = AppScreen.START_MONITORING
                            },
                            onChangeDevice = {
                                viewModel.stopMonitoring()
                                viewModel.clearSelectedDevice()
                                activeScreen = AppScreen.DEVICE_SELECTION
                            },
                            onOpenSettings = { activeScreen = AppScreen.SETTINGS }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions()
        if (viewModel.hasPermissions.value && viewModel.selectedDevice.value == null) {
            viewModel.loadPairedAudioDevices()
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
    }
}
