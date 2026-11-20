package com.example.bleproximityalert.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.bleproximityalert.model.ProximityState
import com.example.bleproximityalert.model.BleDevice
import com.example.bleproximityalert.model.CalibrationModel
import com.example.bleproximityalert.model.ConfidenceLevel
import com.example.bleproximityalert.util.DistanceEstimator
import com.example.bleproximityalert.util.KalmanFilter
import com.example.bleproximityalert.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BleScannerManager private constructor(private val context: Context) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults: StateFlow<List<BleDevice>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val kalmanFilters = mutableMapOf<String, KalmanFilter>()
    private var selectedAddress: String? = null
    private var selectedName: String? = null

    private var activeGattBredr: BluetoothGatt? = null
    private var activeGattLe: BluetoothGatt? = null
    private var gattRssiJob: Job? = null
    private var discoveryLoopJob: Job? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var pollingJob: Job? = null
    private var isReceiverRegistered = false
    private var proximityState: ProximityState = ProximityState.SAFE
    private var classicDiscoveryActive = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = getDeviceFromIntent(intent)

            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (device == null) return
                    val address = device.address
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val name = try { device.name } catch (e: Exception) { null } ?: "Bluetooth Device"

                    // Only process results from the target device
                    if (!isTargetDevice(address, name)) return

                    if (rssi != Short.MIN_VALUE.toInt() && rssi < 0) {
                        Log.d(TAG, "ACTION_FOUND RSSI for $address ($name): $rssi dBm")
                        processRssiMeasurement(
                            address = address,
                            name = name,
                            rawRssi = rssi,
                            isConnected = true
                        )
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Only keep inquiring while the bounded classic-discovery
                    // window is open. Previously this restarted discovery forever
                    // every second, draining the battery while a connected
                    // headset (which stops answering inquiry) never appeared.
                    if (classicDiscoveryActive && _isScanning.value && bluetoothAdapter?.isEnabled == true) {
                        coroutineScope.launch {
                            delay(1000L)
                            if (classicDiscoveryActive && _isScanning.value && bluetoothAdapter?.isEnabled == true) {
                                try {
                                    bluetoothAdapter.startDiscovery()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error restarting discovery", e)
                                }
                            }
                        }
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> {
                    if (device != null && isTargetDevice(device.address, device.name)) {
                        handleDeviceDisconnected(device)
                    }
                }

                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
                        if (device != null && isTargetDevice(device.address, device.name)) {
                            handleDeviceDisconnected(device)
                        }
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "GATT state change for ${gatt.device.address}: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Only start polling after GATT connection is established
                startGattRssiPolling()
                // Also try reading RSSI immediately
                try { gatt.readRemoteRssi() } catch (e: Exception) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stopGattRssiPolling()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && rssi < 0) {
                val address = gatt.device.address
                val name = try { gatt.device.name } catch (e: Exception) { null } ?: "Connected Device"
                Log.d(TAG, "GATT RSSI read success for $address: $rssi dBm")
                processRssiMeasurement(
                    address = selectedAddress ?: address,
                    name = selectedName ?: name,
                    rawRssi = rssi,
                    isConnected = true
                )
            }
        }
    }

    fun selectDevice(address: String?, name: String? = null) {
        selectedAddress = address
        selectedName = name
        if (address == null) {
            clearResults()
        }
    }

    fun getSelectedDevice(): BleDevice? {
        return _scanResults.value.find { it.address == selectedAddress }
    }

    @SuppressLint("MissingPermission")
    fun startScan(activeMonitoring: Boolean = true) {
        if (_isScanning.value) return

        if (!PermissionHelper.hasRequiredBluetoothPermissions(context)) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is disabled")
            return
        }

        scanner = bluetoothAdapter?.bluetoothLeScanner

        registerBroadcastReceiver()
        _isScanning.value = true

        // 1. Connect GATT to selected device for RSSI readings (no fake seed data)
        selectedAddress?.let { addr ->
            val devName = selectedName ?: try { bluetoothAdapter.getRemoteDevice(addr)?.name } catch (e: Exception) { null } ?: "Selected Device"
            selectedName = devName

            // Attempt GATT connection over BR/EDR & LE
            connectGattToDevice(addr)
        }

        // 2. Start Classic Discovery loop only if we don't already have a live result
        //    for the selected device from the BLE scan. This avoids running both
        //    radios when one is sufficient.
        val hasTargetResult = _scanResults.value.any { it.address == selectedAddress }
        if (!hasTargetResult) {
            startClassicDiscoveryLoop(activeMonitoring)
        }

        // 3. Start BLE advertisement scan with a ScanFilter so the radio only wakes
        //    for the target device instead of every nearby advertiser.
        val scanMode = if (activeMonitoring) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()

        val filters = buildScanFilters()

        try {
            scanner?.startScan(filters, settings, scanCallback)
            Log.d(TAG, "BLE Scan started (mode=${if (activeMonitoring) "LOW_LATENCY" else "BALANCED"}) for target address: $selectedAddress, name: $selectedName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
        }

        // 4. Periodic polling loop
        startPeriodicPolling()
    }

    @SuppressLint("MissingPermission")
    private fun buildScanFilters(): List<ScanFilter>? {
        val filters = mutableListOf<ScanFilter>()
        try {
            selectedAddress?.let { addr ->
                filters.add(ScanFilter.Builder().setDeviceAddress(addr).build())
            }
            if (filters.isEmpty()) {
                selectedName?.let { name ->
                    filters.add(ScanFilter.Builder().setDeviceName(name).build())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build scan filters", e)
        }
        return if (filters.isEmpty()) null else filters
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        discoveryLoopJob?.cancel()
        classicDiscoveryActive = false
        pollingJob?.cancel()
        stopGattRssiPolling()
        closeGatt()

        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling discovery", e)
        }

        unregisterBroadcastReceiver()

        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        _isScanning.value = false
    }

    fun clearResults() {
        _scanResults.value = emptyList()
        kalmanFilters.clear()
    }

    private fun isTargetDevice(address: String?, name: String?): Boolean {
        if (selectedAddress != null && address?.equals(selectedAddress, ignoreCase = true) == true) {
            return true
        }
        if (selectedName != null && name != null && name.contains(selectedName!!, ignoreCase = true)) {
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscoveryLoop(activeMonitoring: Boolean = true) {
        discoveryLoopJob?.cancel()
        val loopDelay = if (activeMonitoring) 3000L else 6000L
        // Classic inquiry is the only RSSI source for classic-only devices, but a
        // connected headset usually stops answering inquiry — so bound the window.
        // Probing forever only drains the battery.
        val maxCycles = if (activeMonitoring) 7 else 12
        classicDiscoveryActive = true
        discoveryLoopJob = coroutineScope.launch {
            var cycle = 0
            while (_isScanning.value && cycle < maxCycles) {
                // If the BLE scan already produces a live reading for the target,
                // classic inquiry is redundant — stop it.
                val hasBleResult = _scanResults.value.any {
                    it.address.equals(selectedAddress, ignoreCase = true) && it.rssi != null
                }
                if (hasBleResult) {
                    Log.d(TAG, "BLE scan already provides live RSSI — stopping classic discovery")
                    break
                }
                cycle++
                if (bluetoothAdapter?.isEnabled == true) {
                    try {
                        if (bluetoothAdapter.isDiscovering) {
                            bluetoothAdapter.cancelDiscovery()
                        }
                        bluetoothAdapter.startDiscovery()
                        Log.d(TAG, "Classic Bluetooth discovery triggered (cycle $cycle/$maxCycles)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error triggering discovery", e)
                    }
                }
                delay(loopDelay)
            }
            classicDiscoveryActive = false
            Log.d(TAG, "Classic discovery window closed after $cycle cycles")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattToDevice(address: String) {
        try {
            closeGatt()
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
            Log.d(TAG, "Connecting GATT to $address...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activeGattBredr = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_BREDR)
                activeGattLe = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                activeGattBredr = device.connectGatt(context, false, gattCallback)
            }
            // GATT RSSI polling will start in onConnectionStateChange when connected
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting GATT to $address", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattRssiPolling() {
        gattRssiJob?.cancel()
        gattRssiJob = coroutineScope.launch {
            while (_isScanning.value) {
                // Stop polling when disconnected or RSSI unavailable — don't waste radio cycles.
                if (proximityState == ProximityState.DISCONNECTED || proximityState == ProximityState.UNAVAILABLE) {
                    delay(2000L)
                    continue
                }
                try {
                    activeGattBredr?.readRemoteRssi()
                    activeGattLe?.readRemoteRssi()
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading GATT RSSI", e)
                }
                delay(gattPollingIntervalMs())
            }
        }
    }

    private fun gattPollingIntervalMs(): Long = when (proximityState) {
        ProximityState.AT_LIMIT, ProximityState.LINK_RISK, ProximityState.IMMINENT -> 1000L
        ProximityState.MOVING_AWAY -> 3000L
        ProximityState.SAFE -> 5000L
        else -> 5000L
    }

    private fun stopGattRssiPolling() {
        gattRssiJob?.cancel()
        gattRssiJob = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            activeGattBredr?.disconnect()
            activeGattBredr?.close()
            activeGattBredr = null

            activeGattLe?.disconnect()
            activeGattLe?.close()
            activeGattLe = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            val rssi = result.rssi
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown Device"

            // Only process results from the target device
            if (!isTargetDevice(address, name)) return

            if (rssi < 0) {
                processRssiMeasurement(
                    address = address,
                    name = name,
                    rawRssi = rssi,
                    isConnected = true
                )
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with error code: $errorCode")
        }
    }

    private fun processRssiMeasurement(address: String, name: String, rawRssi: Int, isConnected: Boolean) {
        if (rawRssi >= 0 || rawRssi <= -100) return

        val targetAddr = selectedAddress ?: address
        val targetName = selectedName ?: name

        val filter = kalmanFilters.getOrPut(targetAddr) { KalmanFilter() }
        val filteredRssi = filter.applyFilter(rawRssi.toDouble())
        val distance = DistanceEstimator.estimateDistance(filteredRssi.toInt())

        // Confidence from Kalman filter variance/sample count.
        val confidence = filter.computeConfidence(filter.sampleCount(), false)

        // Wire min/max range only when confidence is at least MEDIUM.
        val range = if (confidence == ConfidenceLevel.MEDIUM || confidence == ConfidenceLevel.HIGH) {
            DistanceEstimator.estimateRange(filteredRssi, filter.computeVariance())
        } else {
            null
        }

        val bleDevice = BleDevice(
            address = targetAddr,
            name = targetName,
            rssi = filteredRssi.toInt(),
            distanceMeters = distance,
            minDistanceMeters = range?.first,
            maxDistanceMeters = range?.second,
            confidence = confidence,
            lastSeen = System.currentTimeMillis(),
            isConnected = isConnected
        )

        updateDeviceInList(bleDevice)
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceDisconnected(device: BluetoothDevice) {
        val address = device.address
        val name = device.name ?: "Device ($address)"

        val disconnectedDevice = BleDevice(
            address = address,
            name = name,
            rssi = null,
            distanceMeters = null,
            lastSeen = System.currentTimeMillis(),
            isConnected = false
        )

        updateDeviceInList(disconnectedDevice, forceUpdateDisconnected = true)
    }

    @SuppressLint("MissingPermission")
    private fun updateDeviceInList(newDevice: BleDevice, forceUpdateDisconnected: Boolean = false) {
        val current = _scanResults.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.address == newDevice.address }

        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            val updated = if (forceUpdateDisconnected) {
                newDevice.copy(
                    isConnected = false,
                    rssi = null,
                    distanceMeters = null
                )
            } else {
                newDevice.copy(
                    isConnected = existing.isConnected || newDevice.isConnected,
                    rssi = newDevice.rssi ?: existing.rssi,
                    distanceMeters = newDevice.distanceMeters ?: existing.distanceMeters
                )
            }
            current[existingIndex] = updated
        } else {
            current.add(newDevice)
        }

        _scanResults.value = current.sortedWith(
            compareByDescending<BleDevice> { it.address == selectedAddress }
                .thenByDescending { it.isConnected }
                .thenByDescending { it.lastSeen }
        )
    }

    @SuppressLint("MissingPermission")
    private fun startPeriodicPolling() {
        pollingJob?.cancel()
        pollingJob = coroutineScope.launch {
            while (_isScanning.value) {
                delay(periodicPollingIntervalMs())
                fetchConnectedDevices()
            }
        }
    }

    private fun periodicPollingIntervalMs(): Long = when (proximityState) {
        ProximityState.AT_LIMIT, ProximityState.LINK_RISK, ProximityState.IMMINENT -> 1000L
        ProximityState.MOVING_AWAY -> 3000L
        ProximityState.SAFE -> 5000L
        else -> 5000L
    }

    @SuppressLint("MissingPermission")
    private fun fetchConnectedDevices() {
        if (!PermissionHelper.hasRequiredBluetoothPermissions(context)) return

        try {
            val gattConnectedDevices = try {
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            for (device in gattConnectedDevices) {
                val address = device.address
                val name = device.name ?: "Connected Device ($address)"
                val existing = _scanResults.value.find { it.address == address }

                if (existing == null) {
                    val bleDevice = BleDevice(
                        address = address,
                        name = name,
                        rssi = null,
                        distanceMeters = null,
                        lastSeen = System.currentTimeMillis(),
                        isConnected = true
                    )
                    updateDeviceInList(bleDevice)
                } else {
                    val updated = existing.copy(
                        isConnected = true,
                        lastSeen = System.currentTimeMillis()
                    )
                    updateDeviceInList(updated)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching connected devices", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceConnected(address: String): Boolean {
        return try {
            // Check the SPECIFIC selected device, not any A2DP/Headset profile.
            val gattConnected = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                ?.any { it.address.equals(address, ignoreCase = true) } == true
            if (gattConnected) return true

            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
            // device.isConnected (no-arg) is not part of the public SDK; connectivity
            // is already covered by the GATT connected-devices check above and the
            // profile checks below.

            // Fall back to profile proxies: only count if THIS device is in the connected list.
            val a2dpConnected = try {
                // We don't hold an A2DP proxy here; treat global A2DP state as connected only
                // if the device is bonded and currently reported as an audio output sink.
                bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED &&
                    bluetoothAdapter.bondedDevices?.any { it.address.equals(address, ignoreCase = true) } == true
            } catch (e: Exception) { false }
            a2dpConnected
        } catch (e: Exception) {
            false
        }
    }

    private fun registerBroadcastReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            context.registerReceiver(bluetoothReceiver, filter)
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register broadcast receiver", e)
        }
    }

    private fun unregisterBroadcastReceiver() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(bluetoothReceiver)
            isReceiverRegistered = false
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
    }

    private fun getDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun isDeviceConnected(): Boolean {
        val addr = selectedAddress ?: return false
        return isDeviceConnected(addr)
    }

    fun setProximityState(state: ProximityState) {
        proximityState = state
    }

    companion object {
        @Volatile
        private var instance: BleScannerManager? = null

        fun getInstance(context: Context): BleScannerManager =
            instance ?: synchronized(this) {
                instance ?: BleScannerManager(context.applicationContext).also { instance = it }
            }

        const val TAG = "BleScannerManager"
    }
}
