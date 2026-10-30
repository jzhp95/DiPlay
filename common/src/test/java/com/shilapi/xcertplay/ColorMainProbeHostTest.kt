package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.VideoColorOverride
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ColorMainProbeHostTest {
    @Test fun actualHostAppliesTheTemporaryOutputAndDecoderWithoutSavingThem() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        AirPlayPersistence.saveSmoothVideo(activity, true)
        AirPlayPersistence.saveDirectVideoOutput(activity, true)
        val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "software-texture" }
        val request = ColorMainProbeRegistry.begin(index, VideoColorOverride())
        var sink: AndroidMediaSink? = null
        var connection:com.shilapi.xcertplay.session.SessionConnection?=null
        try {
            HostSessionFixture.setField(activity, "colorMainProbe", request)
            HostSessionFixture.setField(activity, "airPlayIdentity", AirPlayPersistence.loadIdentity(activity))
            invoke(activity, "loadPersistedSettings")
            invoke(activity, "buildContentView")
            assertFalse(HostSessionFixture.getField(activity, "smoothVideo"))
            assertFalse(HostSessionFixture.getField(activity, "directVideoOutput"))
            assertTrue(AirPlayPersistence.loadSmoothVideo(activity))
            assertTrue(AirPlayPersistence.loadDirectVideoOutput(activity))
            connection=HostSessionFixture.mediaConnection(activity,640,360,0)
            sink=connection.sink
            assertTrue(HostSessionFixture.getField(sink, "forceSoftwareVideoDecoder"))
        } finally {
            connection?.close();sink?.awaitVideoReleased(5000)
            HostSessionFixture.getField<ColorMainProbeOverlay?>(activity, "colorMainOverlay")?.close()
            HostSessionFixture.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
            ColorMainProbeRegistry.finish(request.token)
            ColorMainProbeRegistry.teardownFinished(request.token,
                sink?.awaitVideoReleased(0)!=false && sink?.audioReleased()!=false){
                sink?.awaitVideoReleased(0)!=false && sink?.audioReleased()!=false
            }
            AirPlayPersistence.saveSmoothVideo(activity, false)
            AirPlayPersistence.saveDirectVideoOutput(activity, false)
        }
    }
    @Test fun bootstrapFailureStillFinishesTheProbeAndLeavesAFailureResult() {
        BackgroundSessionFixture.clear()
        val app = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.LOCAL)
        HostSessionFixture.setField(DiPlayBootstrap, "ready", false)
        val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "current" }
        val request = ColorMainProbeRegistry.begin(index, VideoColorOverride())
        val prefs = app.getSharedPreferences(ColorMainProbeRegistry.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("pending_token", request.token).putInt("pending_index", index).commit()
        val intent = Intent(app, CarPlayHostActivity::class.java).putExtra(ColorMainProbeRegistry.EXTRA_TOKEN, request.token)
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java, intent).create()
        try {
            assertNull(ColorMainProbeRegistry.lookup(request.token))
            val result = org.json.JSONObject(prefs.getString("pending_result", "{}"))
            assertEquals("无法显示", result.getString("verdict"))
            assertEquals("bootstrap_failed", result.getJSONObject("evidence").getString("startup_error"))
        } finally {
            controller.destroy()
            prefs.edit().clear().commit()
            AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
            HostSessionFixture.setField(DiPlayBootstrap, "ready", false)
        }
    }
    private fun invoke(activity: CarPlayHostActivity, name: String): Any? =
        CarPlayHostActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
}
