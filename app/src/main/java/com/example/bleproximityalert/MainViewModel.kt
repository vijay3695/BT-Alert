package com.example.bleproximityalert

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bleproximityalert.alert.AlertEngine
import com.example.bleproximityalert.alert.AlertEvaluation
import com.example.bleproximityalert.alert.ThresholdConfig
import com.example.bleproximityalert.ble.BleScannerManager
import com.example.bleproximityalert.ble.RssiCapabilityProbe
import com.example.bleproximityalert.ble.RssiSourceType
import com.example.bleproximityalert.model.BleDevice
import com.example.bleproximityalert.model.CalibrationModel
import com.example.bleproximityalert.model.ConnectedAudioDevice
import com.example.bleproximityalert.model.DisconnectEvent
import com.example.bleproximityalert.model.ProximityState
import com.example.bleproximityalert.service.BleForegroundService
import com.example.bleproximityalert.util.CalibrationStorage
import com.example.bleproximityalert.util.ConnectionMonitor
import com.example.bleproximityalert.util.DisconnectRecorder
import com.example.bleproximityalert.util.PermissionHelper
import com.example.bleproximityalert.util.TextToSpeechHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = BleScannerManager(application)
    private val ttsHelper = TextToSpeechHelper(application)
    private val connectionMonitor = ConnectionMonitor(application)
    private val disconnectRecorder = DisconnectRecorder(application)
    private val calibrationStorage = CalibrationStorage(application)
    private val capabilityProbe = RssiCapabilityProbe(application)

    private var alertEngine = AlertEngine(loadThresholdConfig())

    val connectedAudioDevices: StateFlow<List<ConnectedAudioDevice>> = connectionMonitor.connectedDevices

    private val _selectedDevice = MutableStateFlow<BleDevice?>(null)
    val selectedDevice: StateFlow<BleDevice?> = _selectedDevice.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _alertDevice = MutableStateFlow<BleDevice?>(null)
    val alertDevice: StateFlow<BleDevice?> = _alertDevice.asStateFlow()

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    private val _thresholdConfig = MutableStateFlow(alertEngine.config)
    val thresholdConfig: StateFlow<ThresholdConfig> = _thresholdConfig.asStateFlow()

    private val _isRssiAvailable = MutableStateFlow(true)
    val isRssiAvailable: StateFlow<Boolean> = _isRssiAvailable.asStateFlow()

    private val _rssiProbeDetails = MutableStateFlow<String>("Live signal scanning active...")
    val rssiProbeDetails: StateFlow<String> = _rssiProbeDetails.asStateFlow()

    private val _disconnectHistory = MutableStateFlow<List<DisconnectEvent>>(emptyList())
    val disconnectHistory: StateFlow<List<DisconnectEvent>> = _disconnectHistory.asStateFlow()

    private var scanJob: Job? = null
    private var disconnectObserverJob: Job? = null
    private var probeJob: Job? = null

    init {
        checkPermissions()
        startObservingScanResults()
        startObservingDisconnections()
        refreshDisconnectHistory()
    }

    fun checkPermissions() {
        _hasPermissions.value = PermissionHelper.hasRequiredBluetoothPermissions(getApplication())
    }

    fun loadPairedAudioDevices() {
        connectionMonitor.refreshConnectedDevices()
    }

    fun selectDevice(device: ConnectedAudioDevice) {
        val calibration = calibrationStorage.getCalibration(device.address)
        val bleDevice = BleDevice(
            address = device.address,
            name = device.name,
            isConnected = true,
            isCurrentAudioOutput = device.isCurrentAudioOutput
        )
        _selectedDevice.value = bleDevice
        alertEngine.resetEngine()
        scanner.selectDevice(device.address, device.name)

        _isRssiAvailable.value = true

        if (_isMonitoring.value) {
            stopMonitoring()
            startMonitoring()
        }
    }

    fun selectBleDevice(device: BleDevice) {
        _selectedDevice.value = device
        alertEngine.resetEngine()
        scanner.selectDevice(device.address, device.name)

        _isRssiAvailable.value = true

        if (_isMonitoring.value) {
            stopMonitoring()
            startMonitoring()
        }
    }

    fun clearSelectedDevice() {
        _selectedDevice.value = null
        scanner.selectDevice(null)
    }

    fun startMonitoring() {
        if (!_hasPermissions.value) return
        val device = _selectedDevice.value ?: return

        scanner.selectDevice(device.address, device.name)
        scanner.startScan()
        _isMonitoring.value = true
        _isRssiAvailable.value = true
        startForegroundService()
    }

    fun stopMonitoring() {
        scanner.stopScan()
        _isMonitoring.value = false
        stopForegroundService()
        _alertDevice.value = null
    }

    fun dismissAlert() {
        ttsHelper.stop()
        _alertDevice.value = null
    }

    fun updateThreshold(distanceMeters: Double) {
        val newConfig = _thresholdConfig.value.copy(warningDistanceMeters = distanceMeters)
        _thresholdConfig.value = newConfig
        alertEngine = AlertEngine(newConfig)
        saveThresholdConfig(newConfig)
    }

    fun testTtsVoice() {
        ttsHelper.speak("This is a test of the proximity alert voice inside your headphones.", requireBtSink = false)
    }

    fun saveCalibration(calibration: CalibrationModel) {
        calibrationStorage.saveCalibration(calibration)
    }

    fun refreshDisconnectHistory() {
        _disconnectHistory.value = disconnectRecorder.getEvents()
    }

    fun clearDisconnectHistory() {
        disconnectRecorder.clearHistory()
        refreshDisconnectHistory()
    }

    private fun startObservingScanResults() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.scanResults.collect { results ->
                evaluateSelectedDevice(results)
            }
        }
    }

    private fun startObservingDisconnections() {
        disconnectObserverJob?.cancel()
        disconnectObserverJob = viewModelScope.launch {
            connectionMonitor.onDeviceDisconnected.collect { disconnectedAddr ->
                if (disconnectedAddr != null && _selectedDevice.value?.address == disconnectedAddr) {
                    val currentDev = _selectedDevice.value
                    if (currentDev != null) {
                        disconnectRecorder.recordDisconnect(
                            deviceName = currentDev.name,
                            deviceAddress = currentDev.address,
                            lastRssi = currentDev.rssi,
                            lastDistanceMeters = currentDev.distanceMeters
                        )
                        _selectedDevice.value = currentDev.copy(isConnected = false, state = ProximityState.DISCONNECTED)
                        refreshDisconnectHistory()
                    }
                }
            }
        }
    }

    private fun evaluateSelectedDevice(results: List<BleDevice>) {
        val selectedAddress = _selectedDevice.value?.address ?: return
        val selectedName = _selectedDevice.value?.name ?: ""

        val tracked = results.firstOrNull { it.address == selectedAddress || (selectedName.isNotEmpty() && it.name.contains(selectedName, ignoreCase = true)) }
        if (tracked != null) {
            val isConnected = connectionMonitor.isDeviceConnected(tracked.address) || tracked.isConnected

            val evaluation = alertEngine.evaluate(
                address = selectedAddress,
                deviceName = tracked.name,
                distanceMeters = tracked.distanceMeters ?: 0.3,
                rssi = tracked.rssi ?: -45,
                timestampMs = System.currentTimeMillis(),
                isConnected = isConnected,
                isRssiAvailable = true
            )

            val updatedDevice = tracked.copy(
                isConnected = isConnected,
                state = evaluation.state,
                trendSlopeDbmPerSec = evaluation.trendSlopeDbmPerSec
            )

            _selectedDevice.value = updatedDevice

            if (evaluation.shouldAlert && evaluation.voiceMessage != null && isConnected) {
                _alertDevice.value = updatedDevice
                ttsHelper.speak(evaluation.voiceMessage)
            }
        }
    }

    private fun startForegroundService() {
        try {
            val intent = Intent(getApplication(), BleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to start foreground service", e)
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(getApplication(), BleForegroundService::class.java)
            getApplication<Application>().stopService(intent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to stop foreground service", e)
        }
    }

    private fun loadThresholdConfig(): ThresholdConfig {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getFloat(KEY_WARNING_DISTANCE, 2.0f)
        return ThresholdConfig(warningDistanceMeters = saved.toDouble())
    }

    private fun saveThresholdConfig(config: ThresholdConfig) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putFloat(KEY_WARNING_DISTANCE, config.warningDistanceMeters.toFloat())
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
        connectionMonitor.unregister()
        ttsHelper.shutdown()
        scanJob?.cancel()
        disconnectObserverJob?.cancel()
        probeJob?.cancel()
    }

    companion object {
        private const val PREFS_NAME = "ble_proximity_alert_prefs"
        private const val KEY_WARNING_DISTANCE = "warning_distance_meters"
    }
}
