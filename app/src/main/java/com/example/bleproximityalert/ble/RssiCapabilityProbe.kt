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
            return@withContext ProbeResult(RssiSourceType.UNAVAILABLE, null, "Bluetooth or permissions unavailable")
        }

        Log.d(TAG, "Starting RSSI Capability Probe for $targetName ($deviceAddress)...")

        // 1. Probe GATT readRemoteRssi
        val gattResult = probeGattRssi(deviceAddress)
        if (gattResult.sourceType == RssiSourceType.GATT_RSSI) {
            Log.d(TAG, "Probe Result: GATT RSSI supported for $targetName!")
            return@withContext gattResult
        }

        // 2. Probe BLE advertisement scan
        val bleResult = probeBleScanRssi(deviceAddress, targetName)
        if (bleResult.sourceType == RssiSourceType.BLE_SCAN_RSSI) {
            Log.d(TAG, "Probe Result: BLE advertisement scan supported for $targetName!")
            return@withContext bleResult
        }

        Log.w(TAG, "Probe Result: No RSSI source available for $targetName ($deviceAddress)")
        return@withContext ProbeResult(
            sourceType = RssiSourceType.UNAVAILABLE,
            details = "This headset does not expose live signal strength to Android. Disconnect warnings & last location are active."
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun probeGattRssi(deviceAddress: String): ProbeResult {
        var probedRssi: Int? = null
        var gattSuccess = false
        var gattInstance: BluetoothGatt? = null

        val device = try { bluetoothAdapter?.getRemoteDevice(deviceAddress) } catch (e: Exception) { null }
            ?: return ProbeResult(RssiSourceType.UNAVAILABLE, null, "Invalid MAC address")

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
            gattInstance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_BREDR)
            } else {
                device.connectGatt(context, false, callback)
            }

            // Wait up to 4 seconds for GATT probe
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 4000L && !gattSuccess) {
                delay(200L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GATT probe exception", e)
        } finally {
            try {
                gattInstance?.disconnect()
                gattInstance?.close()
            } catch (e: Exception) {}
        }

        return if (gattSuccess && probedRssi != null) {
            ProbeResult(RssiSourceType.GATT_RSSI, probedRssi, "GATT RSSI active")
        } else {
            ProbeResult(RssiSourceType.UNAVAILABLE, null, "GATT RSSI unsupported")
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
            while (System.currentTimeMillis() - startTime < 3000L && scannedRssi == null) {
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
