package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInputActivityTest {

    @Test
    fun becomesQuiescentOnlyWhenNoInputAndNoPendingWorkAfterThreshold() {
        val activity = VideoInputActivity(quiescentThresholdNs = 3_000_000_000L) // 3s

        var timeNs = 1_000_000_000L
        // Initial input
        assertEquals(StreamActivity.ACTIVE, activity.observe(timeNs, hasNewInput = true, hasPendingWork = false))

        // Advance 2 seconds -> still active
        timeNs += 2_000_000_000L
        assertEquals(StreamActivity.ACTIVE, activity.observe(timeNs, hasNewInput = false, hasPendingWork = false))

        // Advance 1.5 more seconds (total 3.5s elapsed since input), BUT pendingWork = true -> cannot enter QUIESCENT
        timeNs += 1_500_000_000L
        assertEquals(StreamActivity.ACTIVE, activity.observe(timeNs, hasNewInput = false, hasPendingWork = true))

        // When pendingWork clears -> enters QUIESCENT
        assertEquals(StreamActivity.QUIESCENT, activity.observe(timeNs, hasNewInput = false, hasPendingWork = false))
        assertTrue(activity.idleDurationMs() >= 3500L)
    }

    @Test
    fun wakingFromQuiescentEntersResumingAndResetsTrendBaseline() {
        val activity = VideoInputActivity(
            quiescentThresholdNs = 3_000_000_000L,
            resumingTrendWindowNs = 300_000_000L,
            hardAgeLimitNs = 500_000_000L
        )

        var timeNs = 10_000_000_000L
        activity.observe(timeNs, hasNewInput = true, hasPendingWork = false)

        // Advance 30 seconds into quiet
        timeNs += 30_000_000_000L
        activity.observe(timeNs, hasNewInput = false, hasPendingWork = false)
        assertEquals(StreamActivity.QUIESCENT, activity.currentState())
        assertEquals(0L, activity.currentResumeEpisodeId())

        // First frame wakes up
        timeNs += 1_000_000L
        val state1 = activity.observe(timeNs, hasNewInput = true, hasPendingWork = false)
        assertEquals(StreamActivity.RESUMING, state1)
        assertEquals(1L, activity.currentResumeEpisodeId())

        // Frame age is based on its OWN arrival time, not 30s idle time
        assertFalse(activity.isOverloaded(timeNs, oldestFrameArrivalNs = timeNs, currentDepth = 1, maxFrames = 8))

        // 100ms later with new frame -> still RESUMING
        timeNs += 100_000_000L
        assertEquals(StreamActivity.RESUMING, activity.observe(timeNs, hasNewInput = true, hasPendingWork = false))

        // 350ms after wake -> transitions to ACTIVE
        timeNs += 250_000_000L
        assertEquals(StreamActivity.ACTIVE, activity.observe(timeNs, hasNewInput = true, hasPendingWork = false))
    }

    @Test
    fun hardAgeAndCapacityOverloadAreDetectedStrictly() {
        val activity = VideoInputActivity(hardAgeLimitNs = 500_000_000L) // 500ms

        val nowNs = 2_000_000_000L
        // 1. Within limits
        assertFalse(activity.isOverloaded(nowNs, oldestFrameArrivalNs = nowNs - 200_000_000L, currentDepth = 4, maxFrames = 8))

        // 2. Capacity exceeded
        assertTrue(activity.isOverloaded(nowNs, oldestFrameArrivalNs = nowNs - 200_000_000L, currentDepth = 8, maxFrames = 8))

        // 3. Hard age exceeded (>= 500ms)
        assertTrue(activity.isOverloaded(nowNs, oldestFrameArrivalNs = nowNs - 501_000_000L, currentDepth = 2, maxFrames = 8))
    }
}
