package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DecoderTransitionDeadlineTest {
    @Test fun unconfirmedStopWritesAReportAndPreventsANewMainProbe() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controller = mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        val sink = com.shilapi.xcertplay.media.AndroidMediaSink()
        var completion: (() -> Unit)? = null
        BackgroundSessionFixture.store(controller, sink, 1920, 1080, Any(),
            CarPlaySessionDisplay(1920, 1080, 0, false, false, 1920, 1080)) { completion = it }
        val page = ColorTestPage(activity, DiPlayPalette.LIGHT)
        activity.setContentView(page.root)
        try {
            page.run.select(ColorTestRun.cases.indexOfFirst{it.mainProfile!=null})
            org.robolectric.util.ReflectionHelpers.setField(page,"visible",true)
            org.robolectric.util.ReflectionHelpers.setField(page,"active",true)
            page.javaClass.getDeclaredMethod("playCurrent").apply{isAccessible=true}.invoke(page)
            val deadline=System.nanoTime()+5_000_000_000L
            while(page.run.history.isEmpty() && System.nanoTime()<deadline){
                shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)
            }
            assertNotNull("the real transition must have requested session stop",completion)
            val observation=page.run.history.single()
            assertEquals("无法显示",observation.verdict)
            val evidence=JSONObject(observation.evidence)
            assertTrue(evidence.getBoolean("teardown_interrupted"))
            assertTrue(evidence.getJSONArray("missing").toString().contains("teardown_unconfirmed"))
            assertFalse(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.component?.className==CarPlayHostActivity::class.java.name)
            assertTrue(MainProbeTeardown.blocked())
        } finally {
            doReturn(true).`when`(controller).awaitClosed(anyLong())
            completion?.invoke(); BackgroundSessionFixture.clear(); MainProbeTeardown.blocked()
            CarPlayBackgroundSession.authenticationTeardownPending()
            page.close(); sink.close()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (VideoReportPublisher.busy() && System.nanoTime() < deadline) Thread.sleep(10)
            activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).edit().clear().commit()
            activity.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
        }
    }
}
