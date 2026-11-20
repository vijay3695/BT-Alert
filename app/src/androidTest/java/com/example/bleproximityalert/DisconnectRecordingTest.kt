package com.example.bleproximityalert

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bleproximityalert.util.DisconnectRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisconnectRecordingTest {

    @Test
    fun saveAndRetrieveEvent() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val recorder = DisconnectRecorder(ctx)
        recorder.clearHistory()
        val before = recorder.getEvents().size
        // recordDisconnect is async; call saveEvent directly for deterministic test
        val event = com.example.bleproximityalert.model.DisconnectEvent(
            deviceName = "TestHP",
            deviceAddress = "00:11:22:33:44:55",
            lastRssi = -60,
            lastDistanceMeters = 1.5
        )
        recorder.saveEvent(event)
        val after = recorder.getEvents()
        assertEquals(before + 1, after.size)
        assertEquals("TestHP", after.first().deviceName)
        assertEquals(-60, after.first().lastRssi)
    }

    @Test
    fun clearHistoryWorks() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val recorder = DisconnectRecorder(ctx)
        recorder.clearHistory()
        assertTrue(recorder.getEvents().isEmpty())
    }
}
