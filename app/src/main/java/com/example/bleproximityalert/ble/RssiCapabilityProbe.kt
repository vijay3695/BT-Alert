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
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.bleproximityalert.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class RssiSourceType {
    GATT_RSSI,
    BLE_SCAN_RSSI,
    UNAVAILABLE
}

data class ProbeResult(
    val sourceType: RssiSourceType,
    val initialRssi: Int? = null,
    val details: String
)

class RssiCapabilityProbe(private val context: Context) {
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

        // 1. Probe GATT readRemoteRssi and 2. BLE advertisement scan run at the same
        // time, so whichever source answers first wins.
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

        Log.w(TAG, "Probe Result: No RSSI source available for $targetName ($deviceAddress)")
        ProbeResult(
            sourceType = RssiSourceType.UNAVAILABLE,
            details = describeDeviceLimit(device, targetName)
        )
    }

    /**
     * Honest, device-specific explanation for why no live signal source exists.
     * A classic-Bluetooth-only device (A2DP headphones without LE) genuinely cannot
     * report signal strength to Android; a dual-mode device that failed the probe
     * is worth retrying.
     */
    private fun describeDeviceLimit(device: BluetoothDevice, targetName: String): String {
        val deviceType = try { device.type } catch (e: Exception) { -1 }
        return when (deviceType) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC ->
                "$targetName is a classic-Bluetooth (A2DP) device. Android doesn't share live signal strength for classic-only devices — the app watches connect/disconnect and saves your last location instead."
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
        // Most modern TWS earbuds expose their GATT server over Bluetooth LE, not
        // over BR/EDR. Trying BR/EDR first (the old behaviour) made dual-mode
        // earbuds wrongly report "no live signal". Try LE first, then BR/EDR for
        // older headsets.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val leResult = tryGattTransport(device, BluetoothDevice.TRANSPORT_LE, 4000L)
            if (leResult.sourceType != RssiSourceType.UNAVAILABLE) return leResult
            return tryGattTransport(device, BluetoothDevice.TRANSPORT_BREDR, 3000L)
        }
        return tryGattTransport(device, BluetoothDevice.TRANSPORT_AUTO, 4000L)
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryGattTransport(device: BluetoothDevice, transport: Int, timeoutMs: Long): ProbeResult {
        var probedRssi: Int? = null
        var gattSuccess = false
        var gattInstance: BluetoothGatt? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.readRemoteRssi()
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && rssi in -110..-20) {
                    probedRssi = rssi
                    gattSuccess = true
                }
            }
        }

        try {
            gattInstance = device.connectGatt(context, false, callback, transport)

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs && !gattSuccess) {
                delay(200L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GATT probe exception (transport=$transport)", e)
        } finally {
            try {
                gattInstance?.disconnect()
                gattInstance?.close()
            } catch (e: Exception) {}
        }

        return if (gattSuccess && probedRssi != null) {
            ProbeResult(RssiSourceType.GATT_RSSI, probedRssi, "GATT RSSI active")
        } else {
            ProbeResult(RssiSourceType.UNAVAILABLE, null, "GATT RSSI unsupported over transport=$transport")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun probeBleScanRssi(deviceAddress: String, targetName: String): ProbeResult {
        var scannedRssi: Int? = null
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return ProbeResult(RssiSourceType.UNAVAILABLE, null, "BLE Scanner unavailable")

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (addr.equals(deviceAddress, ignoreCase = true) || (name != null && name.contains(targetName, ignoreCase = true))) {
                    scannedRssi = result.rssi
                }
            }
        }

        try {
            scanner.startScan(scanCallback)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 5000L && scannedRssi == null) {
                delay(200L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan probe exception", e)
        } finally {
            try { scanner.stopScan(scanCallback) } catch (e: Exception) {}
        }

        return if (scannedRssi != null) {
            ProbeResult(RssiSourceType.BLE_SCAN_RSSI, scannedRssi, "BLE advertisement scan active")
        } else {
            ProbeResult(RssiSourceType.UNAVAILABLE, null, "BLE scan RSSI unsupported")
        }
    }

    companion object {
        const val TAG = "RssiCapabilityProbe"
    }
}
