package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRuntimeMetricsTest {

    private val identity = RuntimeIdentity(SessionVideoId("s1"), 1L, 1L, 1L, "metrics-test")

    @Test
    fun tracksCountersAndTimelineBoundedCapacity() {
        val timeline = VideoFrameTimeline(maxEntries = 10)
        val clock = FakeClock(1_000_000L)
        val metrics = VideoRuntimeMetrics("s1", 110, timeline, clock)

        // Record 15 frames
        for (i in 1..15) {
            val key = FrameKey(identity, i.toLong(), i * 16_000L)
            metrics.onFrameReceived(key)
            metrics.onFrameSubmitted(key)
            metrics.onFrameDecoded(key)
            if (i % 5 == 0) {
                metrics.onFrameDropped(key, "TEST_DROP")
            } else {
                metrics.onFrameRendered(key)
            }
        }

        // Timeline bounded at maxEntries (10)
        val entries = timeline.snapshotEntries()
        assertEquals(10, entries.size)

        val snap = metrics.snapshot(
            requestedImpl = VideoImplementation.INDEPENDENT,
            actualImpl = VideoImplementation.INDEPENDENT,
            actualBackend = BackendKind.PLATFORM_SURFACE
        )

        assertEquals("s1", snap.sessionId)
        assertEquals(110, snap.streamRole)
        assertEquals(VideoImplementation.INDEPENDENT, snap.requestedImplementation)
        assertEquals(VideoImplementation.INDEPENDENT, snap.actualImplementation)
        assertEquals(BackendKind.PLATFORM_SURFACE, snap.actualBackend)
    }
}
