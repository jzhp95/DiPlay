package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoOutputFreshnessTest {
    @Test
    fun standardFreshnessContract() {
        // age > 100ms, newer output available, not first frame, not diagnostic, not paced -> DISCARD
        assertTrue(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, true, false, false, false))

        // No newer output available -> KEEP (e.g. static tail frame)
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, false, false, false, false))

        // First frame -> KEEP
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, true, true, false, false))

        // Diagnostic measurement -> KEEP
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, true, false, true, false))

        // Explicitly paced video -> KEEP
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, true, false, false, true))

        // Fresh frame (under 100ms) -> KEEP
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(50_000_000L, true, false, false, false))
    }

    @Test
    fun backlogAwareFreshnessContract() {
        // Single frame in batch (newerOutputAvailable = false), but pending queue backlog exists:
        // age > 100ms, backlog present -> DISCARD to catch up to real-time stream
        assertTrue(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, false, false, false, false, hasPendingBacklog = true))

        // Fresh frame (under 100ms) with backlog -> KEEP
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(50_000_000L, false, false, false, false, hasPendingBacklog = true))

        // First frame even with backlog -> KEEP
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, false, true, false, false, hasPendingBacklog = true))

        // Diagnostic mode even with backlog -> KEEP
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, false, false, true, false, hasPendingBacklog = true))

        // No newer output and NO backlog (e.g. static tail frame at red light) -> KEEP
        assertFalse(VideoOutputFreshness.shouldDiscardDecodedOutput(150_000_000L, false, false, false, false, hasPendingBacklog = false))
    }
}
