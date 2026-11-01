package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.video.VideoImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoPipelineSwitchIntegrationTest {

    private lateinit var context: Context
    private lateinit var preferences: VideoImplementationPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = VideoImplementationPreferences(context)
    }

    @Test
    fun completeSwitchCycleFromLegacyToIndependentAndBack() {
        var reconfigureCallCount = 0
        var currentActiveImpl = VideoImplementation.LEGACY

        val switcher = VideoImplementationSwitch(preferences) { nextImpl ->
            reconfigureCallCount++
            currentActiveImpl = nextImpl
            true
        }

        // 1. Initial State: LEGACY
        assertEquals(VideoImplementation.LEGACY, preferences.load())
        assertEquals(0L, preferences.loadRevision())

        // 2. Switch to INDEPENDENT
        val receipt1 = switcher.request(VideoImplementation.INDEPENDENT, currentRevision = 0L)
        assertTrue(receipt1.accepted)
        assertEquals(SwitchPhase.RECONNECTING, receipt1.phase)
        assertEquals(1L, receipt1.newRevision)
        assertEquals(VideoImplementation.INDEPENDENT, preferences.load())
        assertEquals(VideoImplementation.INDEPENDENT, currentActiveImpl)

        // 3. Switch back to LEGACY
        val receipt2 = switcher.request(VideoImplementation.LEGACY, currentRevision = 1L)
        assertTrue(receipt2.accepted)
        assertEquals(SwitchPhase.RECONNECTING, receipt2.phase)
        assertEquals(2L, receipt2.newRevision)
        assertEquals(VideoImplementation.LEGACY, preferences.load())
        assertEquals(VideoImplementation.LEGACY, currentActiveImpl)

        assertEquals(2, reconfigureCallCount)
    }
}
