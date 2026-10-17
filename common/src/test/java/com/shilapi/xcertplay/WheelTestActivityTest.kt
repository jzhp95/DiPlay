package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelTestActivityTest {
    @Test fun aLiveCarPlaySessionRequiresAnExplicitDisconnectChoice() {
        var stops = 0
        val owner = CarPlayBackgroundSession
        val previous = ReflectionHelpers.getField<Any?>(owner, "stopAction")
        val stopAction: ((() -> Unit) -> Unit) = { stops++ }
        ReflectionHelpers.setField(owner, "stopAction", stopAction)
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            descendants(screen.window.decorView).filterIsInstance<Button>().single {
                it.text == screen.getString(R.string.wheel_test_acquire)
            }.performClick()
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
            assertEquals(0, stops)
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            assertFalse(probe.snapshot().running)
        } finally {
            activity.pause().stop().destroy()
            ReflectionHelpers.setField(owner, "stopAction", previous)
        }
    }

    @Test fun openingDoesNotTakeFocusAndLeavingStopsAnExplicitTest() {
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            fun button(id: Int) = descendants(screen.window.decorView).filterIsInstance<Button>().single { it.text == screen.getString(id) }
            assertFalse(probe.snapshot().running)
            button(R.string.wheel_test_next).performClick()
            assertEquals(1, probe.snapshot().simulated)
            assertEquals(0, probe.snapshot().received)
            button(R.string.wheel_test_acquire).performClick()
            assertTrue(probe.snapshot().running)
            button(R.string.wheel_test_release).performClick()
            assertFalse(probe.snapshot().running)
            button(R.string.wheel_test_acquire).performClick()
            activity.pause().stop()
            assertFalse(probe.snapshot().running)
            activity.restart().start().resume()
            assertFalse(probe.snapshot().running)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun appearanceFollowsAppSettingsAndLiveChangesKeepTheTestState() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("app_appearance", null)
        val host = Any()
        prefs.edit().putString("app_appearance", "auto").commit()
        AppAppearanceRuntime.publishHost(host, true)
        val activity = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        try {
            val screen = activity.get()
            val probe = ReflectionHelpers.getField<WheelTestController>(screen, "controller")
            assertTrue(screen.currentAppNight)
            probe.start()
            probe.simulate(4)
            val before = probe.snapshot()
            AppAppearanceRuntime.publishHost(host, false)
            assertFalse(screen.currentAppNight)
            assertSame(probe, ReflectionHelpers.getField<WheelTestController>(screen, "controller"))
            assertEquals(before.running, probe.snapshot().running)
            assertEquals(before.simulated, probe.snapshot().simulated)
            assertEquals(before.event, probe.snapshot().event)
            val views = ReflectionHelpers.getField<WheelTestLayout.Views>(screen, "views")
            assertEquals(DiPlayPalette.LIGHT.accent, views.event.currentTextColor)
            assertFalse(views.acquire.isEnabled)
            assertTrue(views.release.isEnabled)
            prefs.edit().putString("app_appearance", "dark").commit()
            ReflectionHelpers.getField<Runnable>(screen, "appearancePoll").run()
            assertTrue(screen.currentAppNight)
            assertTrue(probe.snapshot().running)
            assertEquals(before.event, probe.snapshot().event)
        } finally {
            activity.pause().stop().destroy()
            AppAppearanceRuntime.clearHost(host)
            if (previous == null) prefs.edit().remove("app_appearance").commit()
            else prefs.edit().putString("app_appearance", previous).commit()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
