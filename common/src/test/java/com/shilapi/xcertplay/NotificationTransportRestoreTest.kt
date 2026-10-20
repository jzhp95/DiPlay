package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class NotificationTransportRestoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun clearPreferences() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }
    @After fun cleanup() { CarPlayBackgroundSession.clear() }

    @Test fun grantedNotificationAfterRecreationKeepsUsbTransport() = exercise(true)
    @Test fun deniedNotificationAfterRecreationKeepsUsbTransport() = exercise(false)

    private fun exercise(granted: Boolean) {
        DiPlayPreferences.saveAutoConnect(app, false)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val first = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent().putExtra("page", "home")).setup()
        var replacement: org.robolectric.android.controller.ActivityController<DiPlayActivity>? = null
        try {
            ReflectionHelpers.setField(first.get(), "setupError", null)
            ReflectionHelpers.callInstanceMethod<Unit>(first.get(), "connect",
                ReflectionHelpers.ClassParameter(Boolean::class.javaPrimitiveType!!, false))
            val request = shadowOf(first.get()).lastRequestedPermission
            assertNotNull("USB should reach notification permission", request)
            assertEquals(listOf(android.Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
            assertFalse(ReflectionHelpers.getField(first.get(), "notificationTransport"))
            val state = android.os.Bundle()
            first.pause().saveInstanceState(state).stop().destroy()
            val second = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent().putExtra("page", "home")).create(state)
            replacement = second
            ReflectionHelpers.setField(second.get(), "setupError", null)
            second.start().resume()
            if (granted) shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            second.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(if (granted) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("A USB request must not become wireless after permission-result restoration",
                AirPlayPersistence.loadWirelessEnabled(app))
            assertEquals(CarPlayHostActivity::class.java.name,
                shadowOf(second.get()).nextStartedActivity?.component?.className)
        } finally {
            replacement?.pause()?.stop()?.destroy()
            if (replacement == null) first.pause().stop().destroy()
        }
    }

}
