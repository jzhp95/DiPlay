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

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
