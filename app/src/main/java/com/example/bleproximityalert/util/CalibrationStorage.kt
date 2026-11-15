package com.example.bleproximityalert.util

import android.content.Context
import androidx.core.content.edit
import com.example.bleproximityalert.model.CalibrationModel
import org.json.JSONObject

class CalibrationStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCalibration(address: String): CalibrationModel {
        val jsonStr = prefs.getString(KEY_PREFIX + address, null) ?: return CalibrationModel(deviceAddress = address)
        return try {
            val obj = JSONObject(jsonStr)
            CalibrationModel(
                deviceAddress = address,
                txPowerAtOneMeterDbm = obj.optDouble("txPowerAtOneMeterDbm", -59.0),
                pathLossExponent = obj.optDouble("pathLossExponent", 2.2),
                calibrated = obj.optBoolean("calibrated", false),
                learnedDisconnectRssiDbm = if (obj.isNull("learnedDisconnectRssiDbm")) null else obj.optDouble("learnedDisconnectRssiDbm")
            )
        } catch (e: Exception) {
            CalibrationModel(deviceAddress = address)
        }
    }

    fun saveCalibration(model: CalibrationModel) {
        val obj = JSONObject().apply {
            put("deviceAddress", model.deviceAddress)
            put("txPowerAtOneMeterDbm", model.txPowerAtOneMeterDbm)
            put("pathLossExponent", model.pathLossExponent)
            put("calibrated", model.calibrated)
            put("learnedDisconnectRssiDbm", model.learnedDisconnectRssiDbm ?: JSONObject.NULL)
        }
        prefs.edit { putString(KEY_PREFIX + model.deviceAddress, obj.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "ble_calibration_storage_prefs"
        private const val KEY_PREFIX = "calib_"
    }
}
