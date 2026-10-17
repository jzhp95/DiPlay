package com.shilapi.xcertplay

import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HomeConnectionChoiceTest {
    @Test fun wirelessCardDuringUsbSessionUsesWirelessSetupRatherThanOpeningUsb() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        // A session exists, but the last selected transport is wired.
        var stops = 0
        val stop: ((() -> Unit) -> Unit) = { stops++; it() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        AirPlayPersistence.saveWirelessEnabled(app, false)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            // This first wireless prerequisite makes the selected route observable without
            // starting another connection, requesting permissions, or touching the live session.
            ReflectionHelpers.setField(activity, "pendingCarHotspotSetup", true)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "home",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, LinearLayout(activity)))
            ReflectionHelpers.getField<Button>(activity, "connectButton").performClick()
            assertEquals("connection", ReflectionHelpers.getField<String>(activity, "page"))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(CarPlayBackgroundSession.hasSession())
            assertEquals(0, stops)
        } finally {
            activity.finish()
            CarPlayBackgroundSession.clear()
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }
}
