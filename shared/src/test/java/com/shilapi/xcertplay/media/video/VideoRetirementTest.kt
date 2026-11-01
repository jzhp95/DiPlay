package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRetirementTest {

    @Test
    fun returnsCompletedTicketWhenReleaseSucceedsImmediately() {
        val clock = FakeClock(100L)
        val retirement = VideoRetirement(clock)

        var stopped = false
        val ticket = retirement.begin(
            reason = "test_normal_stop",
            deadlineNs = 1_000L,
            onStop = { stopped = true },
            checkReleased = { true }
        )

        assertTrue(stopped)
        assertEquals(ReleaseState.RELEASED, ticket.state())
    }

    @Test
    fun unconfirmedReleaseRemainsIncompleteBlockingNextSession() {
        val clock = FakeClock(100L)
        val retirement = VideoRetirement(clock)

        var stopped = false
        // checkReleased always returns false -> release hangs
        val ticket = retirement.begin(
            reason = "test_hanging_stop",
            deadlineNs = 500L,
            onStop = { stopped = true },
            checkReleased = {
                clock.advance(600L) // Exceed deadline
                false
            }
        )

        assertTrue(stopped)
        assertEquals(ReleaseState.BLOCKED, ticket.state())
    }
}
