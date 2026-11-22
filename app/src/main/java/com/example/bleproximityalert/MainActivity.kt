package com.example.bleproximityalert

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.bleproximityalert.ui.AlertScreen
import com.example.bleproximityalert.ui.CalibrationWizardScreen
import com.example.bleproximityalert.ui.DeviceSelectionScreen
import com.example.bleproximityalert.ui.DiagnosticsScreen
import com.example.bleproximityalert.ui.DisconnectHistoryScreen
import com.example.bleproximityalert.ui.MonitoringScreen
import com.example.bleproximityalert.ui.PermissionRequestScreen
import com.example.bleproximityalert.ui.SettingsScreen
import com.example.bleproximityalert.ui.StartMonitoringScreen
import com.example.bleproximityalert.util.PermissionHelper

enum class AppScreen {
    DEVICE_SELECTION,
    START_MONITORING,
    MONITORING,
    SETTINGS,
    DIAGNOSTICS,
    CALIBRATION,
    DISCONNECT_HISTORY
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.updatePermissionResult(result)
        if (viewModel.hasPermissions.value) {
            viewModel.loadPairedAudioDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF2EC4B6),
                    secondary = androidx.compose.ui.graphics.Color(0xFF7DD3C0),
                    tertiary = androidx.compose.ui.graphics.Color(0xFFFF9F1C)
                )
            }
            MaterialTheme(colorScheme = colorScheme) {
                val hasPermissions by viewModel.hasPermissions.collectAsState()
                val selectedDevice by viewModel.selectedDevice.collectAsState()
                val isMonitoring by viewModel.isMonitoring.collectAsState()
                val alertDevice by viewModel.alertDevice.collectAsState()
                val deniedPermissions by viewModel.deniedPermissions.collectAsState()

                var activeScreen by rememberSaveable { mutableStateOf(AppScreen.DEVICE_SELECTION) }
                var showExitDialog by remember { mutableStateOf(false) }

                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text("Exit app?") },
                        text = { Text("Monitoring will continue in the background if active. Are you sure you want to exit?") },
                        confirmButton = {
                            TextButton(onClick = { finish() }) { Text("Exit") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                BackHandler(enabled = true) {
                    when (activeScreen) {
                        AppScreen.SETTINGS -> activeScreen = if (selectedDevice != null) AppScreen.START_MONITORING else AppScreen.DEVICE_SELECTION
                        AppScreen.DIAGNOSTICS -> activeScreen = AppScreen.SETTINGS
                        AppScreen.CALIBRATION -> activeScreen = AppScreen.SETTINGS
                        AppScreen.DISCONNECT_HISTORY -> activeScreen = AppScreen.SETTINGS
                        else -> showExitDialog = true
                    }
                }

                when {
                    !hasPermissions -> {
                        PermissionRequestScreen(
                            deniedPermissions = deniedPermissions,
                            onRequest = { requestPermissions() }
                        )
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
                            onBack = { activeScreen = if (selectedDevice != null) AppScreen.START_MONITORING else AppScreen.DEVICE_SELECTION },
                            onOpenDiagnostics = { activeScreen = AppScreen.DIAGNOSTICS },
                            onOpenCalibration = { activeScreen = AppScreen.CALIBRATION },
                            onOpenHistory = { activeScreen = AppScreen.DISCONNECT_HISTORY },
                        )
                    }

                    activeScreen == AppScreen.DIAGNOSTICS -> {
                        DiagnosticsScreen(
                            viewModel = viewModel,
                            onBack = { activeScreen = AppScreen.SETTINGS }
                        )
                    }

                    activeScreen == AppScreen.CALIBRATION -> {
                        CalibrationWizardScreen(
                            viewModel = viewModel,
                            onBack = { activeScreen = AppScreen.SETTINGS }
                        )
                    }

                    activeScreen == AppScreen.DISCONNECT_HISTORY -> {
                        DisconnectHistoryScreen(
                            viewModel = viewModel,
                            onBack = { activeScreen = AppScreen.SETTINGS }
                        )
                    }

                    selectedDevice == null -> {
                        DeviceSelectionScreen(
                            viewModel = viewModel,
                            onDeviceSelected = { device ->
                                viewModel.selectDevice(device.address, device.name, device.isCurrentAudioOutput)
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
