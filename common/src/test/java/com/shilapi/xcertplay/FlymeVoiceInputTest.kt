package com.shilapi.xcertplay

import com.ecarx.xui.adaptapi.input.Input
import com.ecarx.xui.adaptapi.input.KeyCode
import com.shilapi.flymeeas.FlymeVoiceInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class FlymeVoiceInputTest {
    @Test fun vendorAndStandardKeysPairPressReleaseAndStopAfterClose() {
        val input = Input().also { Input.instance = it }
        val received = mutableListOf<Int>()
        val voice = FlymeVoiceInput(RuntimeEnvironment.getApplication(), { received += it; true }, {})
        try {
            assertTrue(input.registered.await(3, TimeUnit.SECONDS))
            assertArrayEquals(intArrayOf(231, 200231), input.codes)
            val callback = input.callback!!
            assertFalse(callback.onKeyPressed(87))
            assertTrue(callback.onKeyPressed(231))
            assertTrue(callback.onKeyPressed(231))
            assertTrue(callback.onKeyReleased(231))
            assertFalse(callback.onKeyReleased(231))
            assertTrue(callback.onKeyShortPressed(200231))
            assertTrue(callback.onKeyLongPressed(200231))
            assertTrue(callback.onKeyLongReleased(200231))
            assertEquals(listOf(231, 200231), received)
            voice.close()
            assertFalse(callback.onKeyPressed(231))
            assertFalse(callback.onKeyReleased(200231))
            assertTrue(input.abandoned.await(3, TimeUnit.SECONDS))
            assertSame(callback, input.released)
            assertEquals(2, received.size)
        } finally { voice.close() }
    }
    @Test fun bridgeCloseCleansUpAnInputAssignedBeforeQueuedCleanupRuns() {
        val input = Input().also { Input.instance = it }
        val bridge = com.shilapi.flymeeas.FlymeWheelBridge(RuntimeEnvironment.getApplication(), object : com.shilapi.flymeeas.FlymeWheelBridge.Host {
            override fun ready() = true
            override fun mediaButton(index: Int) {}
            override fun playback() = com.shilapi.flymeeas.PlaybackSnapshot.EMPTY
        })
        bridge.close()
        // Simulate connect completing its field assignment after an off-main close observed null.
        val late = FlymeVoiceInput(RuntimeEnvironment.getApplication(), { true }, {})
        try {
            assertTrue(input.registered.await(3, TimeUnit.SECONDS))
            org.robolectric.util.ReflectionHelpers.setField(bridge, "voiceInput", late)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue("closed bridge must release the late input", input.abandoned.await(3, TimeUnit.SECONDS))
            assertFalse(input.callback!!.onKeyPressed(231))
        } finally { late.close(); bridge.close() }
    }

    @Test fun unavailableSessionPassesVoiceKeysThroughAndUsesDeviceConstant() {
        KeyCode.KEYCODE_R_VOICE_ASSIST = 210231
        val input = Input().also { Input.instance = it }
        val voice = FlymeVoiceInput(RuntimeEnvironment.getApplication(), { false }, {})
        try {
            assertTrue(input.registered.await(3, TimeUnit.SECONDS))
            assertArrayEquals(intArrayOf(231, 210231), input.codes)
            assertFalse(input.callback!!.onKeyPressed(210231))
            assertFalse(input.callback!!.onKeyReleased(210231))
            assertFalse(input.callback!!.onKeyPressed(200231))
        } finally {
            voice.close(); assertTrue(input.abandoned.await(3, TimeUnit.SECONDS))
            KeyCode.KEYCODE_R_VOICE_ASSIST = 200231
        }
    }
}
