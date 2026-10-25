package com.shilapi.xcertplay

import android.os.Handler
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.ExperimentalAudioConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ExperimentalAudioHostTest {
    @Test fun settingsClampPersistAndNewSinksTakeAnIndependentSnapshot() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        var old: AndroidMediaSink? = null
        var next: AndroidMediaSink? = null
        try {
            assertEquals(ExperimentalAudioConfig(), AirPlayPersistence.loadExperimentalAudio(activity))
            AirPlayPersistence.saveExperimentalAudio(activity, ExperimentalAudioConfig(true, 999, true, -999, true, 6, true))
            val expected = ExperimentalAudioConfig(true, 12, true, -12, true, 6, true)
            assertEquals(expected, AirPlayPersistence.loadExperimentalAudio(activity))
            ReflectionHelpers.setField(activity, "airPlayIdentity", AirPlayPersistence.loadIdentity(activity))
            for (name in listOf("loadPersistedSettings", "buildContentView"))
                activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
            val method = activity.javaClass.getDeclaredMethod("createMediaSink", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            old = method.invoke(activity, 640, 360, 0) as AndroidMediaSink
            assertEquals(expected, old.experimentalAudio)
            AirPlayPersistence.saveExperimentalAudio(activity, expected.copy(microphoneEnabled = false, outputRecovery = false))
            assertEquals(expected, old.experimentalAudio)
            next = method.invoke(activity, 640, 360, 0) as AndroidMediaSink
            assertEquals(expected.copy(microphoneEnabled = false, outputRecovery = false), next.experimentalAudio)
        } finally {
            old?.close(); next?.close()
            old?.awaitVideoReleased(5000); next?.awaitVideoReleased(5000)
            ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }
}
