package com.example.bleproximityalert.model

data class BleDevice(
    val address: String,
    val name: String,
    val rssi: Int = -100,
    val distanceMeters: Double? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val isConnected: Boolean = false
)
