package com.example.bleproximityalert.util

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.example.bleproximityalert.BleApplication
import com.example.bleproximityalert.R
import com.example.bleproximityalert.model.DisconnectEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DisconnectRecorder(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    fun recordDisconnect(
        deviceName: String,
        deviceAddress: String,
        lastRssi: Int?,
        lastDistanceMeters: Double?
    ) {
        coroutineScope.launch {
            val location = fetchOneShotLocation()
            val event = DisconnectEvent(
                timestampMs = System.currentTimeMillis(),
                deviceName = deviceName,
                deviceAddress = deviceAddress,
                lastRssi = lastRssi,
                lastDistanceMeters = lastDistanceMeters,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracyMeters = location?.accuracy
            )

            saveEvent(event)
            showDisconnectNotification(event)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchOneShotLocation(): Location? {
        if (!PermissionHelper.hasRequiredBluetoothPermissions(context)) return null
        val lm = locationManager ?: return null

        var bestLocation: Location? = null

        try {
            val providers = lm.getProviders(true)
            for (provider in providers) {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.time > bestLocation.time) {
                    bestLocation = loc
                }
            }

            // If last location is recent (< 2 minutes), use it
            val twoMinutesAgo = System.currentTimeMillis() - 2 * 60 * 1000L
            if (bestLocation != null && bestLocation.time > twoMinutesAgo) {
                return bestLocation
            }

            // Otherwise request single location update with 5-second timeout
            var singleUpdateLoc: Location? = null
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    singleUpdateLoc = loc
                }
            }

            val providerToUse = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (providerToUse != null) {
                lm.requestSingleUpdate(providerToUse, listener, context.mainLooper)
                delay(5000L) // Wait up to 5s
                try { lm.removeUpdates(listener) } catch (e: Exception) {}
            }

            return singleUpdateLoc ?: bestLocation
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching location for disconnect event", e)
            return bestLocation
        }
    }

    private fun showDisconnectNotification(event: DisconnectEvent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val timeFormatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(event.timestampMs))
        val title = "${event.deviceName} Disconnected"
        val content = if (event.latitude != null && event.longitude != null) {
            "Last known location saved at $timeFormatted"
        } else {
            "Headphones disconnected at $timeFormatted"
        }

        val builder = NotificationCompat.Builder(context, BleApplication.CHANNEL_ID_ALERT)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (event.latitude != null && event.longitude != null) {
            val mapUri = Uri.parse("geo:${event.latitude},${event.longitude}?q=${event.latitude},${event.longitude}(${event.deviceName}+Last+Location)")
            val mapIntent = Intent(Intent.ACTION_VIEW, mapUri)
            val pendingMap = PendingIntent.getActivity(
                context,
                event.timestampMs.toInt(),
                mapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_launcher_foreground, "Open Last Location", pendingMap)
        }

        notificationManager.notify(event.timestampMs.toInt(), builder.build())
    }

    fun saveEvent(event: DisconnectEvent) {
        val currentEvents = getEvents().toMutableList()
        currentEvents.add(0, event)
        val trimmed = currentEvents.take(20) // Keep at most 20 recent events

        val jsonArray = JSONArray()
        for (ev in trimmed) {
            val obj = JSONObject().apply {
                put("timestampMs", ev.timestampMs)
                put("deviceName", ev.deviceName)
                put("deviceAddress", ev.deviceAddress)
                put("lastRssi", ev.lastRssi ?: JSONObject.NULL)
                put("lastDistanceMeters", ev.lastDistanceMeters ?: JSONObject.NULL)
                put("latitude", ev.latitude ?: JSONObject.NULL)
                put("longitude", ev.longitude ?: JSONObject.NULL)
                put("accuracyMeters", ev.accuracyMeters ?: JSONObject.NULL)
            }
            jsonArray.put(obj)
        }

        prefs.edit { putString(KEY_EVENTS, jsonArray.toString()) }
    }

    fun getEvents(): List<DisconnectEvent> {
        val jsonStr = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        val list = mutableListOf<DisconnectEvent>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    DisconnectEvent(
                        timestampMs = obj.optLong("timestampMs"),
                        deviceName = obj.optString("deviceName"),
                        deviceAddress = obj.optString("deviceAddress"),
                        lastRssi = if (obj.isNull("lastRssi")) null else obj.optInt("lastRssi"),
                        lastDistanceMeters = if (obj.isNull("lastDistanceMeters")) null else obj.optDouble("lastDistanceMeters"),
                        latitude = if (obj.isNull("latitude")) null else obj.optDouble("latitude"),
                        longitude = if (obj.isNull("longitude")) null else obj.optDouble("longitude"),
                        accuracyMeters = if (obj.isNull("accuracyMeters")) null else obj.optDouble("accuracyMeters").toFloat()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing disconnect history JSON", e)
        }
        return list
    }

    fun clearHistory() {
        prefs.edit { remove(KEY_EVENTS) }
    }

    fun deleteEvent(timestampMs: Long) {
        val remaining = getEvents().filter { it.timestampMs != timestampMs }
        if (remaining.isEmpty()) {
            prefs.edit { remove(KEY_EVENTS) }
            return
        }
        val jsonArray = JSONArray()
        for (ev in remaining) {
            val obj = JSONObject().apply {
                put("timestampMs", ev.timestampMs)
                put("deviceName", ev.deviceName)
                put("deviceAddress", ev.deviceAddress)
                put("lastRssi", ev.lastRssi ?: JSONObject.NULL)
                put("lastDistanceMeters", ev.lastDistanceMeters ?: JSONObject.NULL)
                put("latitude", ev.latitude ?: JSONObject.NULL)
                put("longitude", ev.longitude ?: JSONObject.NULL)
                put("accuracyMeters", ev.accuracyMeters ?: JSONObject.NULL)
            }
            jsonArray.put(obj)
        }
        prefs.edit { putString(KEY_EVENTS, jsonArray.toString()) }
    }

    companion object {
        const val TAG = "DisconnectRecorder"
        private const val PREFS_NAME = "ble_disconnect_history_prefs"
        private const val KEY_EVENTS = "disconnect_events_json"
    }
}
