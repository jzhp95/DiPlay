package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class FpsDefaultPersistenceTest {
    @Test fun freshDefaultIsSixtyAndSavedFrameRateIsPreserved() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val saved = if (prefs.contains("display_fps")) prefs.getInt("display_fps", 60) else null
        try {
            prefs.edit().remove("display_fps").commit()
            assertEquals(60, AirPlayDisplaySettings.DEFAULT_FPS)
            assertEquals(60, AirPlayPersistence.loadFps(context))
            AirPlayPersistence.saveFps(context, 30)
            assertEquals(30, AirPlayPersistence.loadFps(context))
            AirPlayPersistence.saveFps(context, 60)
            assertEquals(60, AirPlayPersistence.loadFps(context))
        } finally {
            if (saved == null) prefs.edit().remove("display_fps").commit()
            else prefs.edit().putInt("display_fps", saved).commit()
        }
    }
}
