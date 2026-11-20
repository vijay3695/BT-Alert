package com.example.bleproximityalert

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bleproximityalert.util.PermissionHelper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {

    @Test
    fun requiredPermissionsArrayNonEmpty() {
        val perms = PermissionHelper.getRequiredPermissions()
        assertTrue(perms.isNotEmpty())
        assertTrue(perms.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test
    fun hasRequiredBluetoothPermissionsCallable() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Should not throw; result depends on grant state
        PermissionHelper.hasRequiredBluetoothPermissions(ctx)
        PermissionHelper.hasPostNotificationPermission(ctx)
    }
}
