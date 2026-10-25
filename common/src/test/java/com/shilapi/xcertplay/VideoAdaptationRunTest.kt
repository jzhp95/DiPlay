package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.LiveVideoCounters
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoAdaptationRunTest {
    private fun point(seconds: Long, bytes: Long, frames: Long, key: String = "sink:decoder:1") =
        VideoAdaptationSample(key, 1_000_000_000L + seconds * 1_000_000_000L,
            LiveVideoCounters(frames, frames, frames * 2_000_000L, frames, bytes), seconds * 100,
            JSONObject().put("view", "1920x1080").put("dragMoveEvents", frames))
    @Test fun bitrateCpuAndLatencyUseIntervalDeltasNotLifetimeTotals() {
        val window = VideoAdaptationWindow("dynamic", point(0, 1_000_000, 100))
        assertTrue(window.add(point(20, 6_000_000, 700)))
        val data = window.result(); assertTrue(data.getBoolean("valid"))
        val summary = data.getJSONObject("summary")
        assertEquals(2000.0, summary.getDouble("payloadKbps"), 0.001)
        assertEquals(30.0, summary.getDouble("receivedFps"), 0.001)
        assertEquals(10.0, summary.getDouble("processCpuCorePercent"), 0.001)
        assertEquals(2.0, summary.getDouble("queueToOutputMillis"), 0.001)
    }
    @Test fun reconfigurationAndCounterResetInvalidateInsteadOfMixingSessions() {
        val changed = VideoAdaptationWindow("static", point(0, 1000, 10))
        assertFalse(changed.add(point(20, 2000, 20, "sink:decoder:2")))
        assertFalse(changed.result().getBoolean("valid"))
        val reset = VideoAdaptationWindow("dynamic", point(0, 1000, 10))
        assertFalse(reset.add(point(20, 500, 5)))
        assertTrue(reset.result().toString().contains("counter_or_clock_reset"))
    }
    @Test fun stillScreensCanHaveZeroBitrateButDynamicRequiresNewFrames() {
        val start = point(0, 1000, 10); val end = point(20, 1000, 10)
        val still = VideoAdaptationWindow("static", start); still.add(end)
        assertTrue(still.result().getBoolean("valid"))
        val moving = VideoAdaptationWindow("dynamic", start); moving.add(end)
        assertFalse(moving.result().getBoolean("valid"))
    }
    @Test fun backgroundAnimationsWithoutAMapDragDoNotCountAsDynamicEvidence() {
        val first = point(0, 1000, 10).copy(evidence = JSONObject().put("dragMoveEvents", 0))
        val last = point(20, 50000, 100).copy(evidence = JSONObject().put("dragMoveEvents", 0))
        val window = VideoAdaptationWindow("dynamic", first); window.add(last)
        assertFalse(window.result().getBoolean("valid"))
        assertTrue(window.result().toString().contains("no_drag_gesture_observed"))
    }
    @Test fun shortOrInterruptedIntervalsAreNeverComplete() {
        val window = VideoAdaptationWindow("static", point(0, 0, 0))
        window.add(point(10, 1000, 1))
        assertFalse(window.result().getBoolean("valid"))
        assertTrue(window.result("host_backgrounded").toString().contains("host_backgrounded"))
    }
    @Test fun persistedHistoryIsBoundedAndMissingDataDoesNotPass() {
        val c = RuntimeEnvironment.getApplication()
        VideoAdaptationStore.clear(c)
        try {
            repeat(12) { VideoAdaptationStore.append(c, JSONObject().put("phase", "static").put("valid", true).put("index", it)) }
            val value = VideoAdaptationStore.read(c)
            assertEquals(8, value.getJSONArray("samples").length())
            assertEquals(4, value.getJSONArray("samples").getJSONObject(0).getInt("index"))
            assertTrue(VideoAdaptationStore.gaps(value).contains("缺少有效动态采样"))
            assertTrue(VideoAdaptationStore.gaps(value).contains("设备能力未采集"))
        } finally { VideoAdaptationStore.clear(c) }
    }
    @Test fun forgedExpiredAndReplacedTokensAreRejected() {
        VideoAdaptationRegistry.current()?.let(VideoAdaptationRegistry::finish)
        val token = VideoAdaptationRegistry.begin(10)
        assertEquals(token, VideoAdaptationRegistry.current(20))
        assertNull(VideoAdaptationRegistry.current(900_011))
        VideoAdaptationRegistry.finish("forged")
        assertEquals(token, VideoAdaptationRegistry.current(30))
        VideoAdaptationRegistry.finish(token)
        assertNull(VideoAdaptationRegistry.current(30))
    }
}
