package com.shilapi.xcertplay

import android.os.Handler
import android.view.Surface
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SoftwareYuvHostTest {
    @Test fun hardwareYuvOverridesSoftwareForNewSessionsButExplicitTrialsStayIndependent() = withHost { activity ->
        AirPlayPersistence.saveHardwareYuvEnabled(activity, true)
        AirPlayPersistence.saveSoftwareYuvEnabled(activity, true)
        build(activity)
        assertTrue(field(activity, "hardwareYuvVideoOutput"))
        assertFalse(field(activity, "softwareDirectVideoOutput"))
        assertNull(field<SoftwareYuvConfig?>(activity, "softwareYuvConfig"))
        withSink(activity) { assertTrue(it.mainScreenHardwareYuv); assertFalse(it.mainScreenSoftwareDecoder) }
        val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "software-direct" }
        val request = ColorMainProbeRegistry.begin(index, VideoColorOverride())
        try {
            HostSessionFixture.setField(activity, "colorMainProbe", request)
            build(activity)
            withSink(activity) { assertFalse(it.mainScreenHardwareYuv) }
            assertTrue(AirPlayPersistence.loadHardwareYuvEnabled(activity))
        } finally {
            field<ColorMainProbeOverlay?>(activity, "colorMainOverlay")?.close()
            ColorMainProbeRegistry.finish(request.token); ColorMainProbeRegistry.teardownFinished(request.token)
        }
    }

    @Test fun experimentalYuvOverridesDirectWithoutOverwritingThePreviousMode() = withHost { activity ->
        assertFalse(AirPlayPersistence.loadSoftwareYuvEnabled(activity))
        AirPlayPersistence.saveSoftwareDirectVideoOutput(activity, true)
        AirPlayPersistence.saveSmoothVideo(activity, true)
        AirPlayPersistence.saveSoftwareYuvEnabled(activity, true)
        val color = SoftwareYuvConfig(1, 2)
        AirPlayPersistence.saveSoftwareYuvColor(activity, color)
        build(activity)
        assertEquals(color, field<SoftwareYuvConfig>(activity, "softwareYuvConfig"))
        assertTrue(field(activity, "directVideoOutput"))
        assertFalse(field(activity, "smoothVideo"))
        withSink(activity) { assertEquals(color, it.mainScreenSoftwareYuv); assertTrue(it.mainScreenSoftwareDecoder) }
        assertTrue(AirPlayPersistence.loadSmoothVideo(activity))
        assertTrue(AirPlayPersistence.loadSoftwareDirectVideoOutput(activity))
        AirPlayPersistence.saveSoftwareYuvEnabled(activity, false)
        build(activity)
        assertNull(field<SoftwareYuvConfig?>(activity, "softwareYuvConfig"))
        withSink(activity) { assertNull(it.mainScreenSoftwareYuv); assertTrue(it.mainScreenSoftwareDecoder) }
    }

    @Test fun explicitTrialsIgnoreTheDailyYuvModeWhileCurrentIncludesIt() = withHost { activity ->
        AirPlayPersistence.saveSoftwareYuvEnabled(activity, true)
        for (profile in listOf("texture", "software-direct", "current")) {
            val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == profile }
            val request = ColorMainProbeRegistry.begin(index, VideoColorOverride())
            try {
                HostSessionFixture.setField(activity, "colorMainProbe", request)
                build(activity)
                withSink(activity) { assertEquals(profile == "current", it.mainScreenSoftwareYuv != null) }
            } finally {
                field<ColorMainProbeOverlay?>(activity, "colorMainOverlay")?.close()
                ColorMainProbeRegistry.finish(request.token)
                ColorMainProbeRegistry.teardownFinished(request.token)
            }
        }
    }

    @Test fun explicitYuvTrialsOverrideOnlyTheTemporarySinkAndKeepDailyPreferences() = withHost { activity ->
        AirPlayPersistence.saveSoftwareYuvEnabled(activity, false)
        val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "yuv-limited601" }
        val request = ColorMainProbeRegistry.begin(index, VideoColorOverride())
        try {
            HostSessionFixture.setField(activity, "colorMainProbe", request)
            build(activity)
            withSink(activity) { assertEquals(SoftwareYuvConfig(2, 2), it.mainScreenSoftwareYuv) }
            assertFalse(AirPlayPersistence.loadSoftwareYuvEnabled(activity))
        } finally {
            field<ColorMainProbeOverlay?>(activity, "colorMainOverlay")?.close()
            ColorMainProbeRegistry.finish(request.token); ColorMainProbeRegistry.teardownFinished(request.token)
        }
    }

    @Test fun changingBetweenTwoSoftwareModesWaitsForReconnectEvenAfterReopeningTheHost() = withHost { activity ->
        val controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        val oldColor = SoftwareYuvConfig(2, 2)
        val old = AndroidMediaSink(mainScreenSoftwareYuv = oldColor)
        var stops = 0
        try {
            BackgroundSessionFixture.store(controller, old, 640, 360, Any(),
                CarPlaySessionDisplay(640, 360, Surface.ROTATION_0, false, false, 640, 360)) { stops++ }
            AirPlayPersistence.saveSoftwareDirectVideoOutput(activity, true)
            AirPlayPersistence.saveSoftwareYuvEnabled(activity, false)
            PendingReconnect.mark(controller)
            build(activity)
            assertEquals(oldColor, field<SoftwareYuvConfig>(activity, "softwareYuvConfig"))
            assertEquals(true, invoke(activity, "adoptBackgroundSession"))
            assertSame(old, field<AndroidMediaSink>(activity, "sink"))
            assertEquals(0, stops)
            assertTrue(PendingReconnect.isPending(controller))
            BackgroundSessionFixture.clear(); PendingReconnect.clear()
            build(activity)
            withSink(activity) { assertNull(it.mainScreenSoftwareYuv); assertTrue(it.mainScreenSoftwareDecoder) }
        } finally {
            CarPlayMediaKeys.detach(controller)
            old.close(); old.awaitVideoReleased(5000)
        }
    }

    private fun build(activity: CarPlayHostActivity) {
        HostSessionFixture.setField(activity, "airPlayIdentity", AirPlayPersistence.loadIdentity(activity))
        invoke(activity, "loadPersistedSettings"); invoke(activity, "buildContentView")
    }
    private fun withSink(activity: CarPlayHostActivity, verify: (AndroidMediaSink) -> Unit) {
        val connection=HostSessionFixture.mediaConnection(activity,640,360,0)
        val sink=connection.sink
        try { verify(sink) } finally { connection.close();sink.awaitVideoReleased(5000) }
    }
    private fun withHost(verify: (CarPlayHostActivity) -> Unit) {
        BackgroundSessionFixture.clear(); PendingReconnect.clear()
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try { verify(activity) } finally {
            field<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            BackgroundSessionFixture.clear(); PendingReconnect.clear()
            activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        }
    }
    private fun invoke(activity: CarPlayHostActivity, name: String) =
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun <T> field(activity: CarPlayHostActivity, name: String): T = HostSessionFixture.getField(activity, name)
}
