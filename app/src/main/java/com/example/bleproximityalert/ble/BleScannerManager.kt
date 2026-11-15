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
import com.example.bleproximityalert.model.BleDevice
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

class BleScannerManager(private val context: Context) {
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

                    if (rssi != Short.MIN_VALUE.toInt() && rssi < 0) {
                        Log.d(TAG, "ACTION_FOUND RSSI for $address ($name): $rssi dBm")
                        processRssiMeasurement(
                            address = selectedAddress ?: address,
                            name = selectedName ?: name,
                            rawRssi = rssi,
                            isConnected = true
                        )
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_isScanning.value && bluetoothAdapter?.isEnabled == true) {
                        coroutineScope.launch {
                            delay(1000L)
                            if (_isScanning.value && bluetoothAdapter?.isEnabled == true) {
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
                startGattRssiPolling()
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
    fun startScan() {
        if (_isScanning.value) return

        if (!PermissionHelper.hasRequiredBluetoothPermissions(context)) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return
        }

        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is disabled")
            return
        }

        registerBroadcastReceiver()
        _isScanning.value = true

        // 1. Seed initial active state for selected device
        selectedAddress?.let { addr ->
            val devName = selectedName ?: try { bluetoothAdapter.getRemoteDevice(addr)?.name } catch (e: Exception) { null } ?: "Selected Device"
            selectedName = devName

            val initialRssi = -45 // High proximity starting RSSI (0.3m)
            val initialDist = DistanceEstimator.estimateDistance(initialRssi)
            val initialDevice = BleDevice(
                address = addr,
                name = devName,
                rssi = initialRssi,
                distanceMeters = initialDist,
                lastSeen = System.currentTimeMillis(),
                isConnected = isDeviceConnected(addr)
            )
            updateDeviceInList(initialDevice)

            // Attempt GATT connection over BR/EDR & LE
            connectGattToDevice(addr)
        }

        // 2. Start Classic Discovery loop
        startClassicDiscoveryLoop()

        // 3. Start BLE advertisement scan
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(null, settings, scanCallback)
            Log.d(TAG, "BLE Scan started for target address: $selectedAddress, name: $selectedName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
        }

        // 4. Periodic polling loop
        startPeriodicPolling()
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        discoveryLoopJob?.cancel()
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
    private fun startClassicDiscoveryLoop() {
        discoveryLoopJob?.cancel()
        discoveryLoopJob = coroutineScope.launch {
            while (_isScanning.value) {
                if (bluetoothAdapter?.isEnabled == true) {
                    try {
                        if (bluetoothAdapter.isDiscovering) {
                            bluetoothAdapter.cancelDiscovery()
                        }
                        bluetoothAdapter.startDiscovery()
                        Log.d(TAG, "Classic Bluetooth discovery triggered")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error triggering discovery", e)
                    }
                }
                delay(2500L)
            }
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
            startGattRssiPolling()
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting GATT to $address", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattRssiPolling() {
        gattRssiJob?.cancel()
        gattRssiJob = coroutineScope.launch {
            while (_isScanning.value) {
                try {
                    activeGattBredr?.readRemoteRssi()
                    activeGattLe?.readRemoteRssi()
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading GATT RSSI", e)
                }
                delay(1000L)
            }
        }
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

            if (rssi < 0) {
                processRssiMeasurement(
                    address = selectedAddress ?: address,
                    name = selectedName ?: name,
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

        val bleDevice = BleDevice(
            address = targetAddr,
            name = targetName,
            rssi = filteredRssi.toInt(),
            distanceMeters = distance,
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
            val updated = newDevice.copy(
                isConnected = if (forceUpdateDisconnected) false else (existing.isConnected || newDevice.isConnected),
                rssi = newDevice.rssi ?: existing.rssi,
                distanceMeters = newDevice.distanceMeters ?: existing.distanceMeters
            )
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
                delay(2000L)
                fetchConnectedDevices()
            }
        }
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
                    val defaultRssi = -45
                    val defaultDistance = DistanceEstimator.estimateDistance(defaultRssi)
                    val bleDevice = BleDevice(
                        address = address,
                        name = name,
                        rssi = defaultRssi,
                        distanceMeters = defaultDistance,
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
            val isA2dpConnected = bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
            val isHeadsetConnected = bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
            val isGattConnected = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                ?.any { it.address == address } == true
            isA2dpConnected || isHeadsetConnected || isGattConnected
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

    companion object {
        const val TAG = "BleScannerManager"
    }
}
