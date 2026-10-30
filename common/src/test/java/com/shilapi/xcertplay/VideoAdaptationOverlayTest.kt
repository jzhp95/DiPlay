package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.FrameLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.LiveVideoCounters
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
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
class VideoAdaptationOverlayTest {
    private lateinit var activity: Activity
    private var overlay: VideoAdaptationOverlay? = null
    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        VideoAdaptationRegistry.current()?.let(VideoAdaptationRegistry::finish)
        VideoAdaptationStore.clear(activity)
    }
    @After fun cleanup() {
        overlay?.close()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (VideoReportPublisher.busy() && System.nanoTime() < deadline) Thread.sleep(10)
        VideoAdaptationStore.clear(activity)
    }
    private fun build(sample: () -> VideoAdaptationSample?) {
        val token = VideoAdaptationRegistry.begin()
        overlay = VideoAdaptationOverlay(activity, token, sample, { null to null }, {})
        val probe = requireNotNull(overlay)
        activity.setContentView(FrameLayout(activity).apply { addView(probe.root) })
    }
    private fun start() = (0 until overlay!!.root.childCount).map { overlay!!.root.getChildAt(it) }
        .filterIsInstance<Button>().single { it.text == activity.getString(R.string.video_adapt_static) }.performClick()
    private fun point(key: String = "original"): VideoAdaptationSample {
        val now = SystemClock.elapsedRealtimeNanos()
        return VideoAdaptationSample(key, now, LiveVideoCounters(now / 1_000_000_000, now / 1_000_000_000,
            0, 0, now / 1000), 10, JSONObject().put("viewport", "1920x1080").put("source_kind", "video_adaptation_current_main"))
    }
    @Test fun twentySecondsAutomaticallyCreatesAPublicZipEvenAfterTheOverlayCloses() {
        org.robolectric.Shadows.shadowOf(activity.application).grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        build { point() }; start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        overlay!!.close()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (VideoReportPublisher.busy() && System.nanoTime() < deadline) Thread.sleep(10)
        val state = VideoReportPublisher.latest(activity)
        assertEquals("public", state.getString("status"))
        val file = java.io.File(state.getString("path"))
        try {
            java.util.zip.ZipFile(file).use { zip ->
                val data = JSONObject(zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText())
                assertEquals(20.0, data.getJSONArray("samples").getJSONObject(0).getJSONObject("summary").getDouble("durationSeconds"), 0.001)
            }
        } finally { file.delete() }
    }
    @Test fun unavailableMainScreenDoesNotStartOrManufactureAResult() {
        build { null }; start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25))
        assertFalse(VideoAdaptationStore.read(activity).has("samples"))
    }
    @Test fun closingStopsTimersAndRetainsAnExplicitlyInterruptedInterval() {
        build { point() }; start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        overlay!!.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        val rows = VideoAdaptationStore.read(activity).getJSONArray("samples")
        assertEquals(1, rows.length())
        assertFalse(rows.getJSONObject(0).getBoolean("valid"))
        assertTrue(rows.getJSONObject(0).toString().contains("host_backgrounded_or_closed"))
        assertNull(VideoAdaptationRegistry.current())
    }
    @Test fun aNewDecoderStopsTheIntervalInsteadOfBlendingResults() {
        var key = "first"; build { point(key) }; start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        key = "replacement"
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        val row = VideoAdaptationStore.read(activity).getJSONArray("samples").getJSONObject(0)
        assertFalse(row.getBoolean("valid"))
        assertTrue(row.toString().contains("session_or_output_changed"))
    }
    @Test fun completedTimeWindowStillReportsUnavailableCapture() {
        build { point() }; start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        val value = VideoAdaptationStore.read(activity)
        val row = value.getJSONArray("samples").getJSONObject(0)
        assertTrue(row.getBoolean("valid"))
        assertEquals(20.0, row.getJSONObject("summary").getDouble("durationSeconds"), 0.001)
        assertTrue(row.getJSONArray("missing").toString().contains("capture_unavailable"))
        assertTrue(VideoAdaptationStore.gaps(value).contains("部分采样缺少原尺寸截图"))
    }
}
