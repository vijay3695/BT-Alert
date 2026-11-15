package com.example.bleproximityalert

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class BleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val scanChannel = NotificationChannel(
                CHANNEL_ID_SCAN,
                "BLE Scan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when BLE scanning is active"
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "Proximity Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when tracked device is out of range"
                enableVibration(true)
            }

            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "BLE Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when BLE foreground service is running"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(scanChannel)
            manager.createNotificationChannel(alertChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_ID_SCAN = "ble_scan_channel"
        const val CHANNEL_ID_ALERT = "ble_alert_channel"
        const val CHANNEL_ID_SERVICE = "ble_service_channel"
    }
}
