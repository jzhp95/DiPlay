package com.shilapi.xcertplay

import android.media.AudioManager
import android.os.Looper
import android.os.Build
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 33], manifest = Config.NONE)
class WheelTestControllerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = WheelTestController(app) {}

    @After fun close() { controller.close(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun focusStartsOnlyOnRequestAndStopCannotBeUndoneByQueuedCallbacks() {
        shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertFalse(controller.snapshot().running)
        controller.start()
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        val sent = audio.lastAudioFocusRequest
        assertTrue(controller.snapshot().running)
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, controller.snapshot().requestResult)
        controller.stop()
        if (Build.VERSION.SDK_INT >= 26) {
            assertSame(sent.audioFocusRequest, audio.lastAbandonedAudioFocusRequest)
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(
                sent.audioFocusRequest, "getOnAudioFocusChangeListener").onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        } else {
            assertSame(sent.listener, audio.lastAbandonedAudioFocusListener)
            sent.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.snapshot().running)
        assertFalse(controller.snapshot().easOwned)
        assertNull(controller.snapshot().requestResult)
        assertNull(controller.snapshot().focusChange)
        assertTrue(controller.snapshot().lines.any { it.contains("abandon result=") })
        assertFalse(controller.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT), "window"))
        assertEquals(0, controller.snapshot().received)
    }

    @Test fun realKeysSimulatedKeysAndHeldKeyRepeatsRemainDistinct() {
        controller.simulate(4)
        assertEquals(1, controller.snapshot().simulated)
        assertEquals(0, controller.snapshot().received)
        assertTrue(controller.snapshot().event!!.simulated)
        controller.start()
        assertTrue(controller.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0), "window"))
        controller.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1), "window")
        controller.key(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS), "window")
        assertEquals(1, controller.snapshot().received)
        assertEquals(5, controller.snapshot().event!!.media)
        assertFalse(controller.snapshot().event!!.simulated)
    }

    @Test fun volumeIsObservedWithoutBeingConsumedAndTypingIsIgnored() {
        controller.start()
        assertFalse(controller.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP), "window"))
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, controller.snapshot().event!!.raw)
        val before = controller.snapshot().received
        assertFalse(controller.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A), "window"))
        assertEquals(before, controller.snapshot().received)
    }

    @Test fun standardAndFlymeVoiceKeysAreCapturedOncePerPressAndStopWithTheProbe() {
        for (code in listOf(231, 200231)) {
            val before = controller.snapshot().received
            assertFalse(controller.key(KeyEvent(KeyEvent.ACTION_DOWN, code), "window"))
            assertEquals(before, controller.snapshot().received)
            controller.start()
            assertTrue("voice key $code must be captured", controller.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0), "window"))
            assertTrue(controller.key(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 1), "window"))
            assertTrue(controller.key(KeyEvent(KeyEvent.ACTION_UP, code), "window"))
            assertEquals(before + 1, controller.snapshot().received)
            assertEquals(6, controller.snapshot().event?.media)
            assertEquals(code, controller.snapshot().event?.raw)
            assertFalse(controller.snapshot().event!!.simulated)
            controller.stop()
            assertFalse(controller.key(KeyEvent(KeyEvent.ACTION_DOWN, code), "window"))
            assertEquals(before + 1, controller.snapshot().received)
        }
    }

    @Test fun owningEasFocusKeepsVoiceInputAndPairsToggleWithEasPlayback() {
        controller.start()
        val bridge = org.robolectric.util.ReflectionHelpers.getField<Any>(controller, "bridge")
        val gate = org.robolectric.util.ReflectionHelpers.getField<Any>(bridge, "gate")
        org.robolectric.util.ReflectionHelpers.setField(gate, "active", true)
        org.robolectric.util.ReflectionHelpers.setField(gate, "registered", true)
        org.robolectric.util.ReflectionHelpers.setField(bridge, "owned", true)
        assertTrue(controller.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST), "window"))
        assertEquals(6, controller.snapshot().event?.media)
        assertEquals(1, controller.snapshot().received)
        controller.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), "window")
        assertEquals(1, controller.snapshot().received)
        val host = org.robolectric.util.ReflectionHelpers.getField<com.shilapi.flymeeas.FlymeWheelBridge.Host>(bridge, "host")
        host.mediaButton(1) // The matching EAS callback supplies the explicit play/pause intent.
        assertEquals(1, controller.snapshot().event?.media)
        assertEquals(2, controller.snapshot().received)
        // EAS forwards next/previous separately, so their window duplicates remain suppressed.
        controller.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT), "window")
        assertEquals(2, controller.snapshot().received)
    }
}
