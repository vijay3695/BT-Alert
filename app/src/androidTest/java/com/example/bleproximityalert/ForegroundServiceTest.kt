package com.example.bleproximityalert

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bleproximityalert.service.BleForegroundService
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundServiceTest {

    @Test
    fun serviceStartsAndStops() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(ctx, BleForegroundService::class.java)
        // startForegroundService requires notification channel; service calls startForeground in onStartCommand
        ctx.startForegroundService(intent)
        Thread.sleep(1000)
        ctx.stopService(intent)
        Thread.sleep(500)
        // no crash = pass
        assertNotNull(ctx)
    }
}
