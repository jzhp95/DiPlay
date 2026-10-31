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
}
