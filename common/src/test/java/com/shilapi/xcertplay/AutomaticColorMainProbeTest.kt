package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.LiveVideoCounters
import com.shilapi.xcertplay.media.VideoColorOverride
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AutomaticColorMainProbeTest {
    private lateinit var activity: Activity
    private var probe: AutomaticColorMainProbe? = null
    private var request: ColorMainProbeRegistry.Request? = null
    private var callbacks = 0
    private var result: JSONObject? = null
    private var verdict = ""
    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
        val id = AutomaticVideoSuite.begin(activity)
        repeat(16) { AutomaticVideoSuite.record(activity, id, it, JSONObject(), "fixture") }
        request = ColorMainProbeRegistry.begin(16, VideoColorOverride(), id)
    }
    @After fun cleanup() {
        probe?.close(); request?.let { ColorMainProbeRegistry.finish(it.token); ColorMainProbeRegistry.teardownFinished(it.token) }
        AutomaticVideoSuite.stop(activity, "cleanup")
        activity.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
    }
    private fun create(sample: () -> VideoAdaptationSample?) {
        probe = AutomaticColorMainProbe(activity, request!!, sample, { null to null },
            { JSONObject().put("missing", JSONArray()).put("rendered", true) }) { v, e, _ ->
            callbacks++; verdict = v; result = e
        }
        val view = probe!!.root
        activity.setContentView(FrameLayout(activity).apply { addView(view) })
    }
    private fun point(): VideoAdaptationSample {
        val t = SystemClock.elapsedRealtimeNanos()
        return VideoAdaptationSample("same", t, LiveVideoCounters(t / 1_000_000_000, t / 1_000_000_000, 0, 0, t / 1000),
            0, JSONObject())
    }
    @Test fun stableMainScreenSamplesAutomaticallyAndNeverClaimsVisualColorIsNormal() {
        create { point() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(23))
        assertEquals(1, callbacks)
        assertEquals("自动采集（未人工判色）", verdict)
        assertEquals("not_automatically_measured", result!!.getString("visual_verdict"))
        assertTrue(result!!.getJSONObject("automatic_window").getBoolean("valid"))
    }
    @Test fun unavailableMainScreenHasABoundedDeadlineAndRecordsFailure() {
        create { null }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(80))
        assertEquals(1, callbacks); assertEquals("无法显示", verdict)
        assertEquals("main_screen_timeout_75s", result!!.getString("automatic_error"))
    }
    @Test @Config(qualifiers = "w320dp-h900dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun compactAutomaticProgressScreenshot() = progressScreenshot("compact")
    @Test @Config(qualifiers = "w1000dp-h900dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun expandedAutomaticProgressScreenshot() = progressScreenshot("expanded")
    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h900dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun rtlAutomaticProgressScreenshot() = progressScreenshot("compact-rtl")
    private fun progressScreenshot(name: String) {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        create { point() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val view = probe!!.root
        val width = activity.resources.displayMetrics.widthPixels
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        for (i in 0 until view.childCount) {
            val text = view.getChildAt(i) as? android.widget.TextView ?: continue
            val layout = text.layout ?: continue
            assertTrue(layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        }
        val bitmap = android.graphics.Bitmap.createBitmap(width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            val dir = java.io.File("build/reports/automatic-video-ui").apply { mkdirs() }
            java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle(); org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun cancelStopsTheSuiteAndNoLateTimerCanCompleteItAgain() {
        create { point() }
        (0 until probe!!.root.childCount).map { probe!!.root.getChildAt(it) }.filterIsInstance<Button>().single().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(80))
        assertEquals(1, callbacks); assertFalse(AutomaticVideoSuite.active(activity))
    }
}
