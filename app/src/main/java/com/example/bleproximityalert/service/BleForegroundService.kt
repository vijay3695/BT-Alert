package com.example.bleproximityalert.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.bleproximityalert.BleApplication
import com.example.bleproximityalert.MainActivity
import com.example.bleproximityalert.MainViewModel
import com.example.bleproximityalert.R
import com.example.bleproximityalert.alert.AlertEngine
import com.example.bleproximityalert.model.ProximityState
import com.example.bleproximityalert.alert.ThresholdConfig
import com.example.bleproximityalert.ble.BleScannerManager
import com.example.bleproximityalert.model.BleDevice
import com.example.bleproximityalert.util.ConnectionMonitor
import com.example.bleproximityalert.util.PermissionHelper
import com.example.bleproximityalert.util.TextToSpeechHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BleForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var scanner: BleScannerManager
    private lateinit var connectionMonitor: ConnectionMonitor
    private lateinit var ttsHelper: TextToSpeechHelper
    private var alertEngine: AlertEngine = AlertEngine()

    private var selectedAddress: String? = null
    private var selectedName: String? = null

    private var scanObserverJob: Job? = null
    private var disconnectObserverJob: Job? = null
    private var scanningStarted = false

    override fun onCreate() {
        super.onCreate()
        scanner = BleScannerManager.getInstance(this)
        connectionMonitor = ConnectionMonitor.getInstance(this)
        ttsHelper = TextToSpeechHelper(this)
        loadSelectedDeviceFromPrefs()
        loadThresholdFromPrefs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scanningStarted = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // Handle threshold updates propagated from the UI.
        if (intent?.action == ACTION_UPDATE_THRESHOLD) {
            val t = intent.getFloatExtra(EXTRA_THRESHOLD, -1f)
            if (t > 0f) {
                alertEngine = AlertEngine(ThresholdConfig(warningDistanceMeters = t.toDouble()))
            }
        }

        val addrExtra = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val nameExtra = intent?.getStringExtra(EXTRA_DEVICE_NAME)
        if (addrExtra != null) {
            selectedAddress = addrExtra
            selectedName = nameExtra
            scanner.selectDevice(selectedAddress, selectedName)
            persistSelectedDevice(selectedAddress, selectedName)
        } else if (selectedAddress == null) {
            loadSelectedDeviceFromPrefs()
            selectedAddress?.let { scanner.selectDevice(it, selectedName) }
        }

        // Promote to foreground immediately.
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!scanningStarted) {
            tryStartScanning()
            scanningStarted = true
        }
        startObservers()

        return START_STICKY
    }

    override fun onDestroy() {
        scanningStarted = false
        scanObserverJob?.cancel()
        disconnectObserverJob?.cancel()
        scanner.stopScan()
        ttsHelper.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tryStartScanning() {
        if (!PermissionHelper.hasRequiredBluetoothPermissions(this)) {
            updateNotificationText("Missing Bluetooth permissions")
            return
        }
        if (!scanner.isBluetoothEnabled()) {
            updateNotificationText("Bluetooth is disabled")
            return
        }
        if (selectedAddress == null) {
            updateNotificationText("No device selected")
            return
        }
        scanner.startScan(activeMonitoring = true)
    }

    private fun startObservers() {
        scanObserverJob?.cancel()
        scanObserverJob = serviceScope.launch {
            scanner.scanResults.collectLatest { results ->
                val tracked = selectedAddress?.let { addr ->
                    results.firstOrNull { it.address.equals(addr, ignoreCase = true) }
                }
                updateNotification(tracked)
                evaluateAndAlert(tracked)
            }
        }

        disconnectObserverJob?.cancel()
        disconnectObserverJob = serviceScope.launch {
            connectionMonitor.onDeviceDisconnected.collectLatest { disconnectedAddr ->
                if (disconnectedAddr != null && disconnectedAddr.equals(selectedAddress, ignoreCase = true)) {
                    ttsHelper.speak("Your Bluetooth device has disconnected.")
                    updateNotificationText("Disconnected: ${selectedName ?: selectedAddress}")
                }
                connectionMonitor.clearDisconnectEvent()
            }
        }
    }

    private fun evaluateAndAlert(tracked: BleDevice?) {
        val addr = selectedAddress ?: return
        val device = tracked ?: return
        val isConnected = connectionMonitor.isDeviceConnected(device.address) || device.isConnected
        val evaluation = alertEngine.evaluate(
            address = addr,
            deviceName = device.name,
            distanceMeters = device.distanceMeters,
            rssi = device.rssi,
            timestampMs = System.currentTimeMillis(),
            isConnected = isConnected,
            isRssiAvailable = true
        )
        scanner.setProximityState(evaluation.state)
        if (evaluation.shouldAlert && evaluation.voiceMessage != null && isConnected) {
            ttsHelper.speak(evaluation.voiceMessage)
        }
    }

    private fun updateNotification(tracked: BleDevice?) {
        val text = if (tracked == null) {
            "Monitoring ${selectedName ?: selectedAddress ?: "device"}"
        } else {
            val dist = tracked.distanceMeters?.let { String.format("%.1f m", it) } ?: "—"
            val rssi = tracked.rssi?.let { "$it dBm" } ?: "—"
            val state = tracked.state.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
            "${selectedName ?: selectedAddress}: $dist ($rssi) • $state"
        }
        updateNotificationText(text)
    }

    private fun updateNotificationText(text: String) {
        val notification = buildNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(statusText: String?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BleForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, BleApplication.CHANNEL_ID_SERVICE)
            .setContentTitle("BLE Proximity Alert Active")
            .setContentText(statusText ?: "Monitoring Bluetooth connection and distance in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopIntent)

        return builder.build()
    }

    private fun loadSelectedDeviceFromPrefs() {
        val prefs = getSharedPreferences(MainViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        selectedAddress = prefs.getString(MainViewModel.KEY_SELECTED_ADDRESS, null)
        selectedName = prefs.getString(MainViewModel.KEY_SELECTED_NAME, null)
    }

    private fun persistSelectedDevice(address: String?, name: String?) {
        val prefs = getSharedPreferences(MainViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (address != null) putString(MainViewModel.KEY_SELECTED_ADDRESS, address) else remove(MainViewModel.KEY_SELECTED_ADDRESS)
            if (name != null) putString(MainViewModel.KEY_SELECTED_NAME, name) else remove(MainViewModel.KEY_SELECTED_NAME)
            apply()
        }
    }

    private fun loadThresholdFromPrefs() {
        val prefs = getSharedPreferences(MainViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getFloat(MainViewModel.KEY_WARNING_DISTANCE, 2.0f)
        alertEngine = AlertEngine(ThresholdConfig(warningDistanceMeters = saved.toDouble()))
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_THRESHOLD = "extra_threshold"
        const val ACTION_UPDATE_THRESHOLD = "com.example.bleproximityalert.action.UPDATE_THRESHOLD"
        const val ACTION_STOP = "com.example.bleproximityalert.action.STOP"

        // Expose prefs keys for sharing with MainViewModel.
        const val PREFS_NAME = MainViewModel.PREFS_NAME
        const val KEY_SELECTED_ADDRESS = MainViewModel.KEY_SELECTED_ADDRESS
        const val KEY_SELECTED_NAME = MainViewModel.KEY_SELECTED_NAME
        const val KEY_WARNING_DISTANCE = MainViewModel.KEY_WARNING_DISTANCE
    }
}