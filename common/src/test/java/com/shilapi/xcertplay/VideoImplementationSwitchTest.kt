package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.video.VideoImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoImplementationSwitchTest {

    private lateinit var context: Context
    private lateinit var preferences: VideoImplementationPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = VideoImplementationPreferences(context)
    }

    @Test
    fun disconnectedSwitchEntersPendingReconnect() {
        val switcher = VideoImplementationSwitch(preferences, onReconfigureSession = null)

        val receipt = switcher.request(VideoImplementation.INDEPENDENT, currentRevision = 0L)
        assertTrue(receipt.accepted)
        assertEquals(SwitchPhase.PENDING_RECONNECT, receipt.phase)
        assertEquals(1L, receipt.newRevision)
        assertEquals(VideoImplementation.INDEPENDENT, preferences.load())
    }

    @Test
    fun connectedSwitchTriggersSessionReconfigure() {
        var reconfiguredWith: VideoImplementation? = null
        val switcher = VideoImplementationSwitch(preferences) { impl ->
            reconfiguredWith = impl
            true
        }

        val receipt = switcher.request(VideoImplementation.INDEPENDENT, currentRevision = 0L)
        assertTrue(receipt.accepted)
        assertEquals(SwitchPhase.RECONNECTING, receipt.phase)
        assertEquals(VideoImplementation.INDEPENDENT, reconfiguredWith)
    }

    @Test
    fun staleRevisionIsRejected() {
        val switcher = VideoImplementationSwitch(preferences, onReconfigureSession = null)

        // First transition to INDEPENDENT with rev 0 -> new rev 1
        val r1 = switcher.request(VideoImplementation.INDEPENDENT, currentRevision = 0L)
        assertTrue(r1.accepted)

        // Second transition with outdated rev 0 -> REJECTED
        val r2 = switcher.request(VideoImplementation.LEGACY, currentRevision = 0L)
        assertFalse(r2.accepted)
        assertEquals(SwitchPhase.REJECTED, r2.phase)
        assertTrue(r2.reason.contains("REVISION_CONFLICT"))
    }
}
