package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPresentationSchedulerTest {

    @Test
    fun tracksCountersAccuratelyOverSequence() {
        val clock = FakeClock(1_000_000_000L)
        val scheduler = VideoPresentationScheduler(clock)

        val frame1 = testFrame()
        val candidate1 = FrameCandidate(frame1, arrivalMonoNs = 1_000_000_000L, isFirstFrame = true)
        val d1 = scheduler.evaluate(candidate1)
        assertTrue(d1 is PresentationDecision.Display)
        assertEquals(1L, scheduler.displayedCount)
        assertEquals(0L, scheduler.droppedCount)

        // Advance 200ms with a stale second frame while newer exists
        clock.advance(200_000_000L)
        val frame2 = testFrame()
        val candidate2 = FrameCandidate(frame2, arrivalMonoNs = 1_000_000_000L)
        val d2 = scheduler.evaluate(candidate2, hasPendingBacklog = true, hasNewerReadyCandidate = true)
        assertTrue(d2 is PresentationDecision.DropStale)
        assertEquals(1L, scheduler.displayedCount)
        assertEquals(1L, scheduler.droppedCount)

        // Reset
        scheduler.reset()
        assertEquals(0L, scheduler.displayedCount)
        assertEquals(0L, scheduler.droppedCount)
    }
}
