package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AutomaticColorRebuildTest {
    @Test fun rebuildingThePageWaitsForTheOldPlayersAcknowledgementBeforeResuming() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
        activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).edit().clear().commit()
        var ack: (() -> Unit)? = null
        var old: ColorTestPage? = null
        var next: ColorTestPage? = null
        try {
            val id = AutomaticVideoSuite.begin(activity); AutomaticVideoSuite.ready(activity, id)
            old = ColorTestPage(activity, DiPlayPalette.LIGHT)
            val player = mock(ColorTestPlayer::class.java)
            doAnswer { call -> ack = call.getArgument(0); null }.`when`(player).afterCleanup(org.mockito.ArgumentMatchers.any<() -> Unit>() ?: {})
            ReflectionHelpers.setField(old, "player", player)
            old.close(keepAutomation = true)
            assertTrue(AutomaticVideoCleanup.blocked())
            assertTrue(AutomaticVideoSuite.read(activity).getBoolean("cleanupPending"))
            next = ColorTestPage(activity, DiPlayPalette.DARK)
            next.setVisible(true)
            assertFalse(ReflectionHelpers.getField<Boolean>(next, "active"))
            ack!!.invoke(); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(AutomaticVideoCleanup.blocked())
            assertTrue(AutomaticVideoSuite.read(activity).getBoolean("cleanupConfirmed"))
        } finally {
            AutomaticVideoSuite.stop(activity, "cleanup")
            ack?.invoke(); shadowOf(Looper.getMainLooper()).idle()
            next?.close(keepAutomation = true); old?.close(keepAutomation = true)
            activity.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
            activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).edit().clear().commit()
        }
    }
}
