package com.example.bleproximityalert

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bleproximityalert.alert.AlertEngine
import com.example.bleproximityalert.alert.AlertEvaluation
import com.example.bleproximityalert.alert.AlertLevel
import com.example.bleproximityalert.alert.ThresholdConfig
import com.example.bleproximityalert.ble.BleScannerManager
import com.example.bleproximityalert.model.BleDevice
import com.example.bleproximityalert.service.BleForegroundService
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
    private var alertEngine = AlertEngine(loadThresholdConfig())

    private val _pairedAudioDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val pairedAudioDevices: StateFlow<List<BleDevice>> = _pairedAudioDevices.asStateFlow()

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

    private var scanJob: Job? = null

    init {
        checkPermissions()
        startObservingScanResults()
    }

    fun checkPermissions() {
        _hasPermissions.value = PermissionHelper.hasRequiredBluetoothPermissions(getApplication())
    }

    @SuppressLint("MissingPermission")
    fun loadPairedAudioDevices() {
        if (!PermissionHelper.hasRequiredBluetoothPermissions(getApplication())) {
            Log.e("MainViewModel", "Cannot load devices: missing permissions")
            return
        }

        val bluetoothManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: return

        val deviceMap = mutableMapOf<String, BleDevice>()

        // 1. Fetch bonded (paired) devices
        try {
            val bondedDevices = bluetoothAdapter.bondedDevices ?: emptySet()
            for (dev in bondedDevices) {
                val address = dev.address
                val name = try { dev.name } catch (e: Exception) { null } ?: "Bluetooth Device ($address)"
                deviceMap[address] = BleDevice(
                    address = address,
                    name = name,
                    isConnected = false
                )
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error reading bonded devices", e)
        }

        // 2. Fetch GATT connected devices
        try {
            val gattConnected = try { bluetoothManager.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList() } catch (e: Exception) { emptyList() }
            for (dev in gattConnected) {
                val address = dev.address
                val name = try { dev.name } catch (e: Exception) { null } ?: "Connected Device ($address)"
                val existing = deviceMap[address]
                deviceMap[address] = BleDevice(
                    address = address,
                    name = existing?.name ?: name,
                    isConnected = true
                )
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error fetching GATT connected devices", e)
        }

        _pairedAudioDevices.value = deviceMap.values.toList().sortedWith(
            compareByDescending<BleDevice> { it.isConnected }.thenBy { it.name }
        )

        // 3. Query A2DP profile for audio devices
        try {
            bluetoothAdapter.getProfileProxy(
                getApplication(),
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            val a2dp = proxy as? BluetoothA2dp
                            val connectedAudio = a2dp?.connectedDevices ?: emptyList()

                            val currentMap = _pairedAudioDevices.value.associateBy { it.address }.toMutableMap()
                            for (dev in connectedAudio) {
                                val address = dev.address
                                val name = try { dev.name } catch (e: Exception) { null } ?: "Earphones ($address)"
                                currentMap[address] = BleDevice(
                                    address = address,
                                    name = name,
                                    isConnected = true
                                )
                            }
                            _pairedAudioDevices.value = currentMap.values.toList().sortedWith(
                                compareByDescending<BleDevice> { it.isConnected }.thenBy { it.name }
                            )
                            try { bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) } catch (e: Exception) {}
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                },
                BluetoothProfile.A2DP
            )
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error fetching A2DP profile proxy", e)
        }

        // 4. Query Headset profile for voice audio devices
        try {
            bluetoothAdapter.getProfileProxy(
                getApplication(),
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.HEADSET) {
                            val headset = proxy as? BluetoothHeadset
                            val connectedHeadsets = headset?.connectedDevices ?: emptyList()

                            val currentMap = _pairedAudioDevices.value.associateBy { it.address }.toMutableMap()
                            for (dev in connectedHeadsets) {
                                val address = dev.address
                                val name = try { dev.name } catch (e: Exception) { null } ?: "Headset ($address)"
                                currentMap[address] = BleDevice(
                                    address = address,
                                    name = name,
                                    isConnected = true
                                )
                            }
                            _pairedAudioDevices.value = currentMap.values.toList().sortedWith(
                                compareByDescending<BleDevice> { it.isConnected }.thenBy { it.name }
                            )
                            try { bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) } catch (e: Exception) {}
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                },
                BluetoothProfile.HEADSET
            )
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error fetching Headset profile proxy", e)
        }
    }

    fun selectDevice(device: BleDevice) {
        _selectedDevice.value = device
        alertEngine.reset(device.address)
        scanner.selectDevice(device.address, device.name)
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

    private fun startObservingScanResults() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.scanResults.collect { results ->
                evaluateSelectedDevice(results)
            }
        }
    }

    private fun evaluateSelectedDevice(results: List<BleDevice>) {
        val selectedAddress = _selectedDevice.value?.address ?: return
        val selectedName = _selectedDevice.value?.name ?: ""

        val tracked = results.firstOrNull { it.address == selectedAddress || (selectedName.isNotEmpty() && it.name.contains(selectedName, ignoreCase = true)) }
        if (tracked != null) {
            _selectedDevice.value = tracked

            val evaluation = alertEngine.evaluate(
                address = selectedAddress,
                distanceMeters = tracked.distanceMeters,
                rssi = tracked.rssi,
                timestampMs = System.currentTimeMillis(),
                isConnected = tracked.isConnected
            )

            if (evaluation.shouldAlert && tracked.isConnected) {
                _alertDevice.value = tracked
                val message = buildAlertMessage(evaluation, tracked)
                ttsHelper.speak(message)
            }
        }
    }

    private fun buildAlertMessage(evaluation: AlertEvaluation, device: BleDevice): String {
        val distVal = device.distanceMeters
        val distanceText = if (distVal != null && distVal > 0) String.format("%.1f", distVal) else "unknown"

        return when (evaluation.level) {
            AlertLevel.IMMINENT_DISCONNECT -> {
                "Critical alert! Your Bluetooth device ${device.name} is at $distanceText meters. Disconnection is imminent!"
            }
            AlertLevel.CRITICAL -> {
                if (evaluation.trendDegrading) {
                    "Warning! Signal to ${device.name} is rapidly dropping at $distanceText meters. Disconnection is likely soon!"
                } else {
                    "Warning! Your Bluetooth device ${device.name} is at $distanceText meters. It may disconnect soon."
                }
            }
            AlertLevel.WARNING -> {
                "Warning! You are moving far away from your phone! Current distance is $distanceText meters."
            }
            AlertLevel.NORMAL -> ""
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
        ttsHelper.shutdown()
        scanJob?.cancel()
    }

    companion object {
        private const val PREFS_NAME = "ble_proximity_alert_prefs"
        private const val KEY_WARNING_DISTANCE = "warning_distance_meters"
    }
}
