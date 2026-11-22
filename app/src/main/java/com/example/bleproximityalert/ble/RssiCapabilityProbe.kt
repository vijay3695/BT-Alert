package com.example.bleproximityalert.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.bleproximityalert.util.PermissionHelper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class RssiSourceType {
    GATT_RSSI,
    BLE_SCAN_RSSI,
    CLASSIC_INQUIRY_RSSI,
    UNAVAILABLE
}

data class ProbeResult(
    val sourceType: RssiSourceType,
    val initialRssi: Int? = null,
    val details: String
)

class RssiCapabilityProbe(
    private val context: Context,
    private val scannerManager: BleScannerManager? = null
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    @SuppressLint("MissingPermission")
    suspend fun probeDeviceCapabilities(deviceAddress: String, targetName: String): ProbeResult = withContext(Dispatchers.IO) {
        if (!PermissionHelper.hasRequiredBluetoothPermissions(context) || bluetoothAdapter?.isEnabled != true) {
            return@withContext ProbeResult(RssiSourceType.UNAVAILABLE, null, "Bluetooth is off or permissions are missing")
        }

        val device = try { bluetoothAdapter?.getRemoteDevice(deviceAddress) } catch (e: Exception) { null }
            ?: return@withContext ProbeResult(RssiSourceType.UNAVAILABLE, null, "Device not found on this phone")

        Log.d(TAG, "Starting RSSI Capability Probe for $targetName ($deviceAddress)...")

        // GATT and the BLE advertisement scan share the LE radio and run together.
        // Classic inquiry is started only after both fail, because inquiry and an
        // LE scan on one controller fight each other and one of them drops.
        val gattDeferred = async { probeGattRssi(device) }
        val bleDeferred = async { probeBleScanRssi(deviceAddress, targetName) }

        val gattResult = gattDeferred.await()
        if (gattResult.sourceType == RssiSourceType.GATT_RSSI) {
            bleDeferred.cancel()
            Log.d(TAG, "Probe Result: GATT RSSI supported for $targetName!")
            return@withContext gattResult
        }

        val bleResult = bleDeferred.await()
        if (bleResult.sourceType == RssiSourceType.BLE_SCAN_RSSI) {
            Log.d(TAG, "Probe Result: BLE advertisement scan supported for $targetName!")
            return@withContext bleResult
        }

        val classicResult = probeClassicInquiryRssi(deviceAddress, targetName)
        if (classicResult.sourceType == RssiSourceType.CLASSIC_INQUIRY_RSSI) {
            Log.d(TAG, "Probe Result: classic inquiry RSSI supported for $targetName!")
            return@withContext classicResult
        }

        Log.w(TAG, "Probe Result: No RSSI source available for $targetName ($deviceAddress)")
        ProbeResult(
            sourceType = RssiSourceType.UNAVAILABLE,
            details = describeDeviceLimit(device, targetName)
        )
    }

    private fun describeDeviceLimit(device: BluetoothDevice, targetName: String): String {
        val deviceType = try { device.type } catch (e: Exception) { -1 }
        return when (deviceType) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC ->
                "$targetName is a classic-Bluetooth (A2DP) device. The app asked it directly with a classic inquiry and got no signal reading. A headset that is already connected often stops answering. Make it discoverable or reconnect it, then tap 'Check signal again'. Connection-loss alerts and last-location saving still work."
            BluetoothDevice.DEVICE_TYPE_DUAL ->
                "$targetName supports Bluetooth LE, but a live signal reading couldn't be obtained. Tap 'Check signal again' or open Headset Diagnostics."
            BluetoothDevice.DEVICE_TYPE_LE ->
                "$targetName is a Bluetooth LE device, but a live signal reading couldn't be obtained. Tap 'Check signal again' or open Headset Diagnostics."
            else ->
                "No live signal source was found for $targetName. Connection-loss alerts and last-location saving still work."
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun probeGattRssi(device: BluetoothDevice): ProbeResult {
        // Dual-mode headsets such as Sony WF-C500 usually expose GATT over LE while
        // they are also connected as an A2DP audio sink. Try LE first, then fall back
        // to BR/EDR and AUTO so a device that only answers on one transport is still
        // detected. Each transport gets a couple of attempts with service discovery,
        // because readRemoteRssi can fail on the first connection on some OEM stacks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val transports = listOf(
                BluetoothDevice.TRANSPORT_LE to 2000L,
                BluetoothDevice.TRANSPORT_BREDR to 2000L,
                BluetoothDevice.TRANSPORT_AUTO to 2000L
            )
            for ((transport, timeout) in transports) {
                val result = tryGattTransport(device, transport, timeout)
                if (result.sourceType == RssiSourceType.GATT_RSSI) return result
                delay(300L)
            }
            return ProbeResult(RssiSourceType.UNAVAILABLE, null, "GATT RSSI unsupported over all transports")
        }
        for (attempt in 1..2) {
            val result = tryGattTransport(device, BluetoothDevice.TRANSPORT_AUTO, 4000L)
            if (result.sourceType == RssiSourceType.GATT_RSSI) return result
            delay(500L)
        }
        return ProbeResult(RssiSourceType.UNAVAILABLE, null, "GATT RSSI unsupported")
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryGattTransport(device: BluetoothDevice, transport: Int, timeoutMs: Long): ProbeResult {
        val probedRssi = AtomicReference<Int?>(null)
        val gattSuccess = AtomicBoolean(false)
        var gattInstance: BluetoothGatt? = null

        val connected = AtomicBoolean(false)
        val servicesDiscovered = AtomicBoolean(false)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "GATT connect status=$status (transport=$transport, newState=$newState)")
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected.set(true)
                    // Some stacks require service discovery before RSSI reads succeed.
                    try { gatt.discoverServices() } catch (e: Exception) {
                        Log.w(TAG, "discoverServices failed (transport=$transport)", e)
                    }
                    // Also issue an immediate read in case discovery is slow/unsupported.
                    try { gatt.readRemoteRssi() } catch (e: Exception) {}
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected.set(false)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    servicesDiscovered.set(true)
                    try { gatt.readRemoteRssi() } catch (e: Exception) {}
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && rssi in -110..-20) {
                    probedRssi.set(rssi)
                    gattSuccess.set(true)
                } else {
                    Log.d(TAG, "RSSI read failed: status=$status rssi=$rssi (transport=$transport, servicesDiscovered=${servicesDiscovered.get()})")
                    // Retry the read shortly after discovery; helps on stacks that
                    // reject the very first readRemoteRssi call.
                    if (servicesDiscovered.get() && !gattSuccess.get()) {
                        try { gatt.readRemoteRssi() } catch (e: Exception) {}
                    }
                }
            }
        }

        try {
            gattInstance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, transport)
            } else {
                device.connectGatt(context, false, callback)
            }

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs && !gattSuccess.get()) {
                delay(200L)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT for GATT probe (transport=$transport)", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "GATT unsupported for this device (transport=$transport)", e)
        } catch (e: Exception) {
            Log.e(TAG, "GATT probe exception (transport=$transport)", e)
        } finally {
            try {
                gattInstance?.disconnect()
                gattInstance?.close()
            } catch (e: Exception) { }
        }

        return if (gattSuccess.get() && probedRssi.get() != null) {
            ProbeResult(RssiSourceType.GATT_RSSI, probedRssi.get(), "GATT RSSI active (transport=$transport)")
        } else {
            ProbeResult(RssiSourceType.UNAVAILABLE, null, "GATT RSSI unsupported over transport=$transport")
        }
    }

    /**
     * Classic (BR/EDR) inquiry is the only public Android source of live RSSI for a
     * classic-only A2DP headset: ACTION_FOUND carries BluetoothDevice.EXTRA_RSSI.
     * A connected headset may stop answering inquiry, so this window is bounded and
     * reported honestly when it yields nothing.
     */
    @SuppressLint("MissingPermission")
    private suspend fun probeClassicInquiryRssi(deviceAddress: String, targetName: String): ProbeResult {
        val foundRssi = AtomicReference<Int?>(null)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return

                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                if (rssi == Short.MIN_VALUE.toInt() || rssi >= 0) return

                val address = try { device.address } catch (e: Exception) { null }
                val name = try { device.name } catch (e: Exception) { null }
                val matches = address?.equals(deviceAddress, ignoreCase = true) == true ||
                    bleNamesMatch(name, targetName)

                if (matches) {
                    Log.d(TAG, "Classic inquiry RSSI for $address ($name): $rssi dBm")
                    foundRssi.compareAndSet(null, rssi)
                }
            }
        }

        var registered = false
        var startedDiscovery = false
        try {
            // Bluetooth is a highly privileged app, not the system UID. Android only
            // delivers its broadcasts to a receiver registered with RECEIVER_EXPORTED;
            // RECEIVER_NOT_EXPORTED silently receives nothing.
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_FOUND),
                ContextCompat.RECEIVER_EXPORTED
            )
            registered = true

            if (bluetoothAdapter?.isDiscovering == true) {
                // Someone else (the scanner) already owns discovery. Listen to its
                // results, but never cancel or restart it.
                Log.d(TAG, "Classic inquiry already running; listening without taking it over")
            } else {
                startedDiscovery = bluetoothAdapter?.startDiscovery() == true
                Log.d(TAG, "Classic inquiry started=$startedDiscovery")
            }

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < CLASSIC_INQUIRY_TIMEOUT_MS && foundRssi.get() == null) {
                delay(200L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classic inquiry probe exception", e)
        } finally {
            if (startedDiscovery) {
                try { bluetoothAdapter?.cancelDiscovery() } catch (e: Exception) {}
            }
            if (registered) {
                try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
            }
        }

        return if (foundRssi.get() != null) {
            ProbeResult(
                RssiSourceType.CLASSIC_INQUIRY_RSSI,
                foundRssi.get(),
                "Classic inquiry RSSI active"
            )
        } else {
            ProbeResult(RssiSourceType.UNAVAILABLE, null, "Classic inquiry returned no reading")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun probeBleScanRssi(deviceAddress: String, targetName: String): ProbeResult {
        val scannedRssi = AtomicReference<Int?>(null)
        val startTime = System.currentTimeMillis()

        // The main scanner already runs a filtered BLE scan for this exact device
        // during monitoring. Android rejects a second simultaneous scan from the
        // same app ("app is scanning too frequently"), so read its results instead
        // of opening a competing scanner. Only fall back to a private scan when no
        // monitoring scan is active (e.g. the onboarding compatibility check).
        if (scannerManager?.isScanning?.value == true) {
            Log.d(TAG, "Reusing active BLE scan from BleScannerManager for probe")
            while (System.currentTimeMillis() - startTime < BLE_SCAN_TIMEOUT_MS && scannedRssi.get() == null) {
                val match = scannerManager.scanResults.value.firstOrNull { device ->
                    device.rssi != null && device.rssi!! < 0 &&
                        (device.address.equals(deviceAddress, ignoreCase = true) ||
                            bleNamesMatch(device.name, targetName))
                }
                if (match != null) {
                    scannedRssi.set(match.rssi)
                } else {
                    delay(200L)
                }
            }
            return if (scannedRssi.get() != null) {
                ProbeResult(RssiSourceType.BLE_SCAN_RSSI, scannedRssi.get(), "BLE advertisement scan active")
            } else {
                ProbeResult(RssiSourceType.UNAVAILABLE, null, "BLE scan RSSI unsupported")
            }
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return ProbeResult(RssiSourceType.UNAVAILABLE, null, "BLE Scanner unavailable")

        val scanCallback = object : ScanCallback() {
            private var logged = 0
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                val name = result.scanRecord?.deviceName ?: try { result.device.name } catch (e: Exception) { null }
                if (logged < 80) {
                    logged++
                    Log.d(TAG, "BLE advertiser #$logged: $addr name=$name rssi=${result.rssi} connectable=${result.isConnectable}")
                }
                // Match the classic address or the LE-advertised name (which may
                // carry an "LE_" prefix and a different, randomised address).
                if (addr.equals(deviceAddress, ignoreCase = true) || bleNamesMatch(name, targetName)) {
                    scannedRssi.set(result.rssi)
                }
            }
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            scanner.startScan(null, settings, scanCallback)
            while (System.currentTimeMillis() - startTime < BLE_SCAN_TIMEOUT_MS && scannedRssi.get() == null) {
                delay(200L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan probe exception", e)
        } finally {
            try { scanner.stopScan(scanCallback) } catch (e: Exception) {}
        }

        return if (scannedRssi.get() != null) {
            ProbeResult(RssiSourceType.BLE_SCAN_RSSI, scannedRssi.get(), "BLE advertisement scan active")
        } else {
            ProbeResult(RssiSourceType.UNAVAILABLE, null, "BLE scan RSSI unsupported")
        }
    }

    companion object {
        const val TAG = "RssiCapabilityProbe"
        private const val CLASSIC_INQUIRY_TIMEOUT_MS = 8_000L
        private const val BLE_SCAN_TIMEOUT_MS = 8_000L
    }
}