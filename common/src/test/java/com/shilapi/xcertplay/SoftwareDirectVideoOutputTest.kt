package com.shilapi.xcertplay

import android.os.Handler
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.VideoColorOverride
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
class SoftwareDirectVideoOutputTest {
    @Test fun dailyModeUsesTheVerifiedOutputWithoutOverwritingExistingPreferences() {
        withHost { activity ->
            AirPlayPersistence.saveSmoothVideo(activity, true)
            AirPlayPersistence.saveDirectVideoOutput(activity, false)
            AirPlayPersistence.saveLowLatencyDecoder(activity, true)
            AirPlayPersistence.saveMtkDecoderTuning(activity, true)
            assertFalse(AirPlayPersistence.loadSoftwareDirectVideoOutput(activity))
            AirPlayPersistence.saveSoftwareDirectVideoOutput(activity, true)
            buildHost(activity)
            assertFalse(ReflectionHelpers.getField(activity, "smoothVideo"))
            assertTrue(ReflectionHelpers.getField(activity, "directVideoOutput"))
            assertTrue(AirPlayPersistence.loadSmoothVideo(activity))
            assertFalse(AirPlayPersistence.loadDirectVideoOutput(activity))
            withSink(activity) { sink ->
                assertTrue(sink.mainScreenSoftwareDecoder)
                assertFalse(sink.videoPacingEnabled)
                assertTrue(ReflectionHelpers.getField(sink, "vendorLowLatencyDecoder"))
                assertTrue(ReflectionHelpers.getField(sink, "mtkDecoderTuningEnabled"))
                assertFalse(sink.mainVideoLowLatencyEnabled)
                assertFalse(sink.mainVideoMtkTuningEnabled)
            }
            AirPlayPersistence.saveSoftwareDirectVideoOutput(activity, false)
            buildHost(activity)
            assertTrue(ReflectionHelpers.getField(activity, "smoothVideo"))
            assertFalse(ReflectionHelpers.getField(activity, "directVideoOutput"))
            withSink(activity) { assertFalse(it.mainScreenSoftwareDecoder) }
        }
    }

    @Test fun explicitDiagnosticProfilesOverrideDailyCompatibilityButCurrentProfileIncludesIt() {
        withHost { activity ->
            AirPlayPersistence.saveSoftwareDirectVideoOutput(activity, true)
            for (profile in listOf("texture", "direct", "software-texture", "software-direct", "current")) {
                val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == profile }
                val request = ColorMainProbeRegistry.begin(index, VideoColorOverride())
                try {
                    ReflectionHelpers.setField(activity, "colorMainProbe", request)
                    buildHost(activity)
                    assertEquals(profile, profile in listOf("direct", "software-direct", "current"),
                        ReflectionHelpers.getField<Boolean>(activity, "directVideoOutput"))
                    withSink(activity) { sink ->
                        assertEquals(profile, profile == "current", sink.mainScreenSoftwareDecoder)
                        assertEquals(profile, profile.startsWith("software-"),
                            ReflectionHelpers.getField<Boolean>(sink, "forceSoftwareVideoDecoder"))
                    }
                } finally {
                    ReflectionHelpers.getField<ColorMainProbeOverlay?>(activity, "colorMainOverlay")?.close()
                    ColorMainProbeRegistry.finish(request.token)
                    ColorMainProbeRegistry.teardownFinished(request.token)
                }
            }
        }
    }


    @Test fun reopeningTheHostKeepsTheLiveModeUntilTheDriverReconnects() {
        for (oldSoftware in listOf(false, true)) withHost { activity ->
            val oldController = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
            org.mockito.Mockito.`when`(oldController.nowPlayingSnapshot())
                .thenReturn(com.shilapi.xcertplay.media.CarPlayNowPlaying())
            val oldSink = AndroidMediaSink(null, mainScreenSoftwareDecoder = oldSoftware)
            var stops = 0
            CarPlayBackgroundSession.clear()
            try {
                CarPlayBackgroundSession.store(oldController, oldSink, 640, 360, Any(),
                    CarPlaySessionDisplay(640, 360, android.view.Surface.ROTATION_0, false, false, 640, 360)) { stops++ }
                AirPlayPersistence.saveSoftwareDirectVideoOutput(activity, !oldSoftware)
                PendingReconnect.mark(oldController)
                buildHost(activity)
                assertEquals(oldSoftware, ReflectionHelpers.getField<Boolean>(activity, "softwareDirectVideoOutput"))
                assertEquals(oldSoftware, ReflectionHelpers.getField<Boolean>(activity, "directVideoOutput"))
                assertEquals(true, invoke(activity, "adoptBackgroundSession"))
                assertSame(oldSink, ReflectionHelpers.getField(activity, "sink"))
                assertTrue(PendingReconnect.isPending(oldController))
                assertEquals(0, stops)
                // Once the retained session is gone, a new host uses the saved desired mode.
                CarPlayBackgroundSession.clear()
                PendingReconnect.clear()
                buildHost(activity)
                assertEquals(!oldSoftware, ReflectionHelpers.getField<Boolean>(activity, "softwareDirectVideoOutput"))
                withSink(activity) { assertEquals(!oldSoftware, it.mainScreenSoftwareDecoder) }
            } finally {
                CarPlayBackgroundSession.clear()
                PendingReconnect.clear()
                CarPlayMediaKeys.detach(oldController)
                oldSink.close()
                oldSink.awaitVideoReleased(5_000)
            }
        }
    }

    private fun buildHost(activity: CarPlayHostActivity) {
        ReflectionHelpers.setField(activity, "airPlayIdentity", AirPlayPersistence.loadIdentity(activity))
        invoke(activity, "loadPersistedSettings")
        invoke(activity, "buildContentView")
    }

    private fun withSink(activity: CarPlayHostActivity, check: (AndroidMediaSink) -> Unit) {
        val method = CarPlayHostActivity::class.java.getDeclaredMethod("createMediaSink",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val sink = method.invoke(activity, 640, 360, 0) as AndroidMediaSink
        try { check(sink) } finally { sink.close(); sink.awaitVideoReleased(5_000) }
    }

    private fun withHost(check: (CarPlayHostActivity) -> Unit) {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try { check(activity) } finally {
            ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }

    private fun invoke(activity: CarPlayHostActivity, name: String) =
        CarPlayHostActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
}
