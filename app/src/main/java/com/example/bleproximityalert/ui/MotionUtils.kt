package com.example.bleproximityalert.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ) == 0.0f
        } catch (e: Exception) {
            false
        }
    }
}

fun <T> motionSpec(reduceMotion: Boolean): AnimationSpec<T> {
    return if (reduceMotion) {
        tween(durationMillis = 0)
    } else {
        tween(durationMillis = 300)
    }
}
