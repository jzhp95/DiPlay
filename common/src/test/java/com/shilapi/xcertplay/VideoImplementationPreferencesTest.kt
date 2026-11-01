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
class VideoImplementationPreferencesTest {

    private lateinit var context: Context
    private lateinit var preferences: VideoImplementationPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = VideoImplementationPreferences(context)
    }

    @Test
    fun defaultCleanInstallReturnsLegacyAndZeroRevision() {
        assertEquals(VideoImplementation.LEGACY, preferences.load())
        assertEquals(0L, preferences.loadRevision())
    }

    @Test
    fun saveIncrementsRevisionAndPersistsSelection() {
        val result = preferences.save(VideoImplementation.INDEPENDENT, expectedRevision = 0L)
        assertTrue(result is SaveResult.Success)
        assertEquals(1L, (result as SaveResult.Success).newRevision)

        assertEquals(VideoImplementation.INDEPENDENT, preferences.load())
        assertEquals(1L, preferences.loadRevision())
    }

    @Test
    fun saveRejectsConcurrentRevisionConflict() {
        // Save rev 1
        preferences.save(VideoImplementation.INDEPENDENT, expectedRevision = 0L)

        // Try to save again expecting rev 0 -> conflict
        val result = preferences.save(VideoImplementation.LEGACY, expectedRevision = 0L)
        assertTrue(result is SaveResult.Conflict)
        assertEquals(1L, (result as SaveResult.Conflict).currentRevision)
        assertEquals(VideoImplementation.INDEPENDENT, preferences.load())
    }

    @Test
    fun corruptedOrUnknownValueFallsBackToLegacy() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putString(AirPlayPersistence.KEY_VIDEO_IMPLEMENTATION, "UNKNOWN_BOGUS_ENUM")
            .commit()

        assertEquals(VideoImplementation.LEGACY, preferences.load())
    }
}
