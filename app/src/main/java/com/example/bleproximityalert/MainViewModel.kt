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
import com.example.bleproximityalert.model.RssiProbeState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = BleScannerManager.getInstance(application)
    private val ttsHelper = TextToSpeechHelper(application)
    private val connectionMonitor = ConnectionMonitor.getInstance(application)
    private val disconnectRecorder = DisconnectRecorder(application)
    private val calibrationStorage = CalibrationStorage(application)
    private val capabilityProbe = RssiCapabilityProbe(application, scanner)

    private var alertEngine = AlertEngine(loadThresholdConfig())

    val connectedAudioDevices: StateFlow<List<ConnectedAudioDevice>> = connectionMonitor.connectedDevices

    private val _selectedDevice = MutableStateFlow<BleDevice?>(null)
    val selectedDevice: StateFlow<BleDevice?> = _selectedDevice.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    private val _alertDevice = MutableStateFlow<BleDevice?>(null)
    val alertDevice: StateFlow<BleDevice?> = _alertDevice.asStateFlow()

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    private val _thresholdConfig = MutableStateFlow(alertEngine.config)
    val thresholdConfig: StateFlow<ThresholdConfig> = _thresholdConfig.asStateFlow()

    private val _voiceEnabled = MutableStateFlow(true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()
    private val _voiceSpeed = MutableStateFlow(0.9f)
    val voiceSpeed: StateFlow<Float> = _voiceSpeed.asStateFlow()
    private val _voiceCooldownSeconds = MutableStateFlow(30)
    val voiceCooldownSeconds: StateFlow<Int> = _voiceCooldownSeconds.asStateFlow()
    private val _powerMode = MutableStateFlow("Balanced")
    val powerMode: StateFlow<String> = _powerMode.asStateFlow()
    private val _backgroundLocationEnabled = MutableStateFlow(false)
    val backgroundLocationEnabled: StateFlow<Boolean> = _backgroundLocationEnabled.asStateFlow()
    private val _autoMonitorOnConnect = MutableStateFlow(false)
    val autoMonitorOnConnect: StateFlow<Boolean> = _autoMonitorOnConnect.asStateFlow()
    private val _sensitivity = MutableStateFlow(2)
    val sensitivity: StateFlow<Int> = _sensitivity.asStateFlow()

    private val _isRssiAvailable = MutableStateFlow(true)
    val isRssiAvailable: StateFlow<Boolean> = _isRssiAvailable.asStateFlow()

    private val _rssiProbeState = MutableStateFlow(RssiProbeState.Available)
    val rssiProbeState: StateFlow<RssiProbeState> = _rssiProbeState.asStateFlow()

    private val _rssiProbeDetails = MutableStateFlow<String>("Live signal scanning active...")
    val rssiProbeDetails: StateFlow<String> = _rssiProbeDetails.asStateFlow()

    private val _isLoadingDevices = MutableStateFlow(false)
    val isLoadingDevices: StateFlow<Boolean> = _isLoadingDevices.asStateFlow()

    private val _deniedPermissions = MutableStateFlow<List<String>>(emptyList())
    val deniedPermissions: StateFlow<List<String>> = _deniedPermissions.asStateFlow()

    private val _disconnectHistory = MutableStateFlow<List<DisconnectEvent>>(emptyList())
    val disconnectHistory: StateFlow<List<DisconnectEvent>> = _disconnectHistory.asStateFlow()

    private var scanJob: Job? = null
    private var disconnectObserverJob: Job? = null
    private var probeJob: Job? = null
    private var probeAddress: String? = null

    init {
        checkPermissions()
        startObservingScanResults()
        startObservingDisconnections()
        refreshDisconnectHistory()
        loadSettings()
        // Start auto-monitor service if enabled.
        if (_autoMonitorOnConnect.value) {
            try {
                val intent = Intent(getApplication(), BleForegroundService::class.java).apply {
                    action = BleForegroundService.ACTION_LISTEN
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getApplication<Application>().startForegroundService(intent)
                } else {
                    getApplication<Application>().startService(intent)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to start auto-monitor service", e)
            }
        }
    }

    fun checkPermissions() {
        _hasPermissions.value = PermissionHelper.hasRequiredBluetoothPermissions(getApplication())
    }

    fun updatePermissionResult(result: Map<String, Boolean>) {
        val denied = result.filterValues { !it }.keys.toList()
        _deniedPermissions.value = denied
        checkPermissions()
    }

    fun loadPairedAudioDevices() {
        _isLoadingDevices.value = true
        connectionMonitor.refreshConnectedDevices()
        _isLoadingDevices.value = false
    }

    fun selectDevice(address: String, name: String, isCurrentAudioOutput: Boolean = false) {
        val bleDevice = BleDevice(
            address = address,
            name = name,
            isConnected = true,
            isCurrentAudioOutput = isCurrentAudioOutput
        )
        _selectedDevice.value = bleDevice
        alertEngine.resetEngine()
        scanner.selectDevice(address, name)
        // persistSelectedDevice(address, name) -- inlined below
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_SELECTED_ADDRESS, address)
            putString(KEY_SELECTED_NAME, name)
        }

        _isRssiAvailable.value = false
        _rssiProbeState.value = RssiProbeState.Probing
        _rssiProbeDetails.value = "Probing RSSI capability..."

        // Run capability probe to detect if headset exposes live RSSI. A repeated
        // selection of the same device must not cancel and restart an in-flight
        // probe: that tore down its receivers and left the UI stuck on "Probing".
        if (probeAddress?.equals(address, ignoreCase = true) == true && probeJob?.isActive == true) return
        probeAddress = address
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val result = capabilityProbe.probeDeviceCapabilities(address, name)
            _isRssiAvailable.value = result.sourceType != RssiSourceType.UNAVAILABLE
            _rssiProbeState.value = if (result.sourceType != RssiSourceType.UNAVAILABLE) RssiProbeState.Available else RssiProbeState.Unavailable
            _rssiProbeDetails.value = result.details
            val label = when (result.sourceType) {
                RssiSourceType.GATT_RSSI -> "GATT RSSI"
                RssiSourceType.BLE_SCAN_RSSI -> "BLE scan RSSI"
                RssiSourceType.CLASSIC_INQUIRY_RSSI -> "Classic inquiry RSSI"
                RssiSourceType.UNAVAILABLE -> "Unavailable"
            }
            _selectedDevice.value = _selectedDevice.value?.copy(rssiSourceLabel = label)
        }

        if (_isMonitoring.value) {
            stopMonitoring()
            startMonitoring()
        }
    }

    fun clearSelectedDevice() {
        _selectedDevice.value = null
        scanner.selectDevice(null)
        val prefs2 = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs2.edit {
            remove(KEY_SELECTED_ADDRESS)
            remove(KEY_SELECTED_NAME)
        }
    }

    fun startMonitoring() {
        if (_isMonitoring.value) return
        // Re-entrancy guard: one tap starts one monitoring session. Previously the
        // flag was only set after the capability probe joined (up to ~7s), so the
        // UI looked dead and repeated taps each raced another scan/service start.
        if (_isStarting.value) return
        val device = _selectedDevice.value
        if (device == null) {
            _startError.value = "Select a device first."
            return
        }
        if (!_hasPermissions.value) {
            _startError.value = "Grant the Bluetooth permissions first."
            return
        }

        _startError.value = null
        _isStarting.value = true

        // Do not block monitoring on the capability probe: it can take up to ~15s on
        // a classic-only headset and made "Start" look dead. Start scanning now; the
        // probe updates the source label and availability when it finishes.
        viewModelScope.launch {
            try {
                scanner.selectDevice(device.address, device.name)
                scanner.startScan()
                _isMonitoring.value = true
                // Keep the probe result; don't overwrite with true
                startForegroundService()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to start monitoring", e)
                _isMonitoring.value = false
                _startError.value = "Could not start monitoring: ${e.message ?: "unknown error"}"
            } finally {
                _isStarting.value = false
            }
        }
    }

    fun stopMonitoring() {
        scanner.stopScan()
        _isMonitoring.value = false
        _isStarting.value = false
        stopForegroundService()
        _alertDevice.value = null
    }

    /**
     * Re-run the signal-capability probe for the currently selected device without
     * leaving the monitoring screen. Needed when a first probe fails while the
     * earbuds are still pairing/connecting.
     */
    fun reProbeSelectedDevice() {
        val device = _selectedDevice.value ?: return
        if (_rssiProbeState.value == RssiProbeState.Probing) return

        _isRssiAvailable.value = false
        _rssiProbeState.value = RssiProbeState.Probing
        _rssiProbeDetails.value = "Re-checking signal capability..."
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            try {
                val result = capabilityProbe.probeDeviceCapabilities(device.address, device.name)
                _isRssiAvailable.value = result.sourceType != RssiSourceType.UNAVAILABLE
                _rssiProbeState.value = if (result.sourceType != RssiSourceType.UNAVAILABLE) RssiProbeState.Available else RssiProbeState.Unavailable
                _rssiProbeDetails.value = result.details
                val label = when (result.sourceType) {
                    RssiSourceType.GATT_RSSI -> "GATT RSSI"
                    RssiSourceType.BLE_SCAN_RSSI -> "BLE scan RSSI"
                    RssiSourceType.CLASSIC_INQUIRY_RSSI -> "Classic inquiry RSSI"
                    RssiSourceType.UNAVAILABLE -> "Unavailable"
                }
                _selectedDevice.value = _selectedDevice.value?.copy(rssiSourceLabel = label)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Re-probe failed", e)
                _isRssiAvailable.value = false
                _rssiProbeState.value = RssiProbeState.Unavailable
                _rssiProbeDetails.value = "Signal check failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun runManualDiscovery() {
        viewModelScope.launch {
            scanner.startScan(false)
            delay(10_000L)
            scanner.stopScan()
        }
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
        // Propagate to the foreground service if it is running.
        try {
            val intent = Intent(getApplication(), BleForegroundService::class.java).apply {
                action = BleForegroundService.ACTION_UPDATE_THRESHOLD
                putExtra(BleForegroundService.EXTRA_THRESHOLD, distanceMeters.toFloat())
            }
            getApplication<Application>().startService(intent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to propagate threshold to service", e)
        }
    }

    fun testTtsVoice() {
        ttsHelper.speak("This is a test of the proximity alert voice inside your headphones.", requireBtSink = false)
    }

    fun saveCalibration(calibration: CalibrationModel) {
        calibrationStorage.saveCalibration(calibration)
    }

    fun isDeviceCalibrated(address: String): Boolean {
        return calibrationStorage.getCalibration(address)?.calibrated == true
    }

    fun setAutoMonitorOnConnect(enabled: Boolean) {
        _autoMonitorOnConnect.value = enabled
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_AUTO_MONITOR, enabled) }
        // Start or stop the foreground service based on the toggle.
        if (enabled) {
            // Start service in listening mode (no active scan until device connects).
            try {
                val intent = Intent(getApplication(), BleForegroundService::class.java).apply {
                    action = BleForegroundService.ACTION_LISTEN
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getApplication<Application>().startForegroundService(intent)
                } else {
                    getApplication<Application>().startService(intent)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to start auto-monitor service", e)
            }
        } else {
            // Stop the service if not actively monitoring.
            if (!_isMonitoring.value) {
                try {
                    val intent = Intent(getApplication(), BleForegroundService::class.java).apply {
                        action = BleForegroundService.ACTION_STOP
                    }
                    getApplication<Application>().startService(intent)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to stop auto-monitor service", e)
                }
            }
        }
    }

    fun refreshDisconnectHistory() {
        _disconnectHistory.value = disconnectRecorder.getEvents()
    }

    fun clearDisconnectHistory() {
        disconnectRecorder.clearHistory()
        refreshDisconnectHistory()
    }

    fun deleteDisconnectEvent(event: com.example.bleproximityalert.model.DisconnectEvent) {
        disconnectRecorder.deleteEvent(event.timestampMs)
        refreshDisconnectHistory()
    }

    private fun startObservingScanResults() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.scanResults.collect { results ->
                evaluateSelectedDevice(results)
                promoteRssiAvailability(results)
            }
        }
    }

    /**
     * The probe only samples for a few seconds. If a live RSSI reading arrives later
     * from any source (BLE advertisement, GATT, or classic inquiry), the UI must follow
     * the data instead of staying stuck on the first probe result.
     */
    private fun promoteRssiAvailability(results: List<BleDevice>) {
        val address = _selectedDevice.value?.address ?: return
        val now = System.currentTimeMillis()
        val live = results.firstOrNull {
            it.address.equals(address, ignoreCase = true) &&
                it.rssi != null &&
                it.isConnected &&
                now - it.lastSeen <= RSSI_FRESHNESS_MS
        } ?: return

        if (!_isRssiAvailable.value) {
            Log.d("MainViewModel", "Live RSSI arrived for $address (${live.rssi} dBm), marking signal available")
            _isRssiAvailable.value = true
            _rssiProbeState.value = RssiProbeState.Available
            _rssiProbeDetails.value = "Live signal readings are arriving."
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
                        _selectedDevice.value = currentDev.copy(isConnected = false, rssi = null, distanceMeters = null, state = ProximityState.DISCONNECTED)
                        alertEngine.resetEngine()
                        refreshDisconnectHistory()
                    }
                }
                // Reset so the same device disconnecting again emits
                connectionMonitor.clearDisconnectEvent()
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
                distanceMeters = tracked.distanceMeters,
                rssi = tracked.rssi,
                timestampMs = System.currentTimeMillis(),
                isConnected = isConnected,
                isRssiAvailable = _isRssiAvailable.value
            )

            val updatedDevice = tracked.copy(
                isConnected = isConnected,
                state = evaluation.state,
                trendSlopeDbmPerSec = evaluation.trendSlopeDbmPerSec,
                rssiSourceLabel = _selectedDevice.value?.rssiSourceLabel ?: tracked.rssiSourceLabel
            )

            _selectedDevice.value = updatedDevice

            if (evaluation.shouldAlert && evaluation.voiceMessage != null && isConnected) {
                _alertDevice.value = updatedDevice
                // Voice alert is delivered by BleForegroundService so it works in background.
            }
        }
    }

    private fun startForegroundService() {
        try {
            val device = _selectedDevice.value
            val intent = Intent(getApplication(), BleForegroundService::class.java).apply {
                if (device != null) {
                    putExtra(BleForegroundService.EXTRA_DEVICE_ADDRESS, device.address)
                    putExtra(BleForegroundService.EXTRA_DEVICE_NAME, device.name)
                }
            }
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

    fun setVoiceEnabled(v: Boolean) {
        _voiceEnabled.value = v
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_VOICE_ENABLED, v) }
    }
    fun setVoiceSpeed(v: Float) {
        _voiceSpeed.value = v
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putFloat(KEY_VOICE_SPEED, v) }
    }
    fun setVoiceCooldownSeconds(v: Int) {
        _voiceCooldownSeconds.value = v
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putInt(KEY_VOICE_COOLDOWN, v) }
    }
    fun setPowerMode(v: String) {
        _powerMode.value = v
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(KEY_POWER_MODE, v) }
    }
    fun setBackgroundLocationEnabled(v: Boolean) {
        _backgroundLocationEnabled.value = v
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_BG_LOCATION, v) }
    }
    fun setSensitivity(v: Int) {
        _sensitivity.value = v
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putInt(KEY_SENSITIVITY, v) }
    }

    private fun loadSettings() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _voiceEnabled.value = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        _voiceSpeed.value = prefs.getFloat(KEY_VOICE_SPEED, 0.9f)
        _voiceCooldownSeconds.value = prefs.getInt(KEY_VOICE_COOLDOWN, 30)
        _powerMode.value = prefs.getString(KEY_POWER_MODE, "Balanced") ?: "Balanced"
        _backgroundLocationEnabled.value = prefs.getBoolean(KEY_BG_LOCATION, false)
        _autoMonitorOnConnect.value = prefs.getBoolean(KEY_AUTO_MONITOR, false)
        _sensitivity.value = prefs.getInt(KEY_SENSITIVITY, 2)
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

    private fun persistSelectedDevice(address: String, name: String) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_SELECTED_ADDRESS, address)
            putString(KEY_SELECTED_NAME, name)
        }
    }

    private fun clearPersistedSelectedDevice() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_SELECTED_ADDRESS)
            remove(KEY_SELECTED_NAME)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Keep foreground monitoring running in the background; do not stop the service here.
        scanJob?.cancel()
        disconnectObserverJob?.cancel()
        probeJob?.cancel()
        ttsHelper.shutdown()
    }

    companion object {
        const val PREFS_NAME = "ble_proximity_alert_prefs"
        const val KEY_WARNING_DISTANCE = "warning_distance_meters"
        const val KEY_SELECTED_ADDRESS = "selected_device_address"
        const val KEY_SELECTED_NAME = "selected_device_name"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_VOICE_SPEED = "voice_speed"
        const val KEY_VOICE_COOLDOWN = "voice_cooldown_seconds"
        const val KEY_POWER_MODE = "power_mode"
        const val KEY_BG_LOCATION = "background_location_enabled"
    const val KEY_AUTO_MONITOR = "auto_monitor_on_connect"
        const val KEY_SENSITIVITY = "sensitivity"
        private const val RSSI_FRESHNESS_MS = 10_000L
    }
}
