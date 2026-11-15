package com.example.bleproximityalert.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TextToSpeechHelper(private val context: Context) {
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var activeFocusRequest: AudioFocusRequest? = null

    init {
        initialize()
    }

    private fun initialize() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.getDefault())
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (isInitialized) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    textToSpeech?.setAudioAttributes(attrs)
                    textToSpeech?.setSpeechRate(0.9f)
                    textToSpeech?.setPitch(1.0f)

                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            abandonAudioFocus()
                        }
                        override fun onError(utteranceId: String?) {
                            abandonAudioFocus()
                        }
                    })
                }
            }
        }
    }

    fun hasBluetoothMediaSink(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { info ->
                info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothA2dpOn || am.isBluetoothScoOn
        }
    }

    fun speak(text: String, requireBtSink: Boolean = true) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized")
            return
        }

        if (requireBtSink && !hasBluetoothMediaSink()) {
            Log.w(TAG, "Speech suppressed: No active Bluetooth media sink connected")
            return
        }

        requestAudioFocus()

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "proximity_alert_tts_" + System.currentTimeMillis())
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                activeFocusRequest = focusRequest
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting audio focus", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activeFocusRequest != null) {
                audioManager?.abandonAudioFocusRequest(activeFocusRequest!!)
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }
    }

    fun stop() {
        textToSpeech?.stop()
        abandonAudioFocus()
    }

    fun shutdown() {
        textToSpeech?.shutdown()
        abandonAudioFocus()
        isInitialized = false
    }

    companion object {
        const val TAG = "TextToSpeechHelper"
    }
}
