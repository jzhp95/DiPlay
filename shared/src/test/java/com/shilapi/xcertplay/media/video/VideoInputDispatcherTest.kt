package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoInputDispatcherTest {

    @Test
    fun coalescesConsecutiveMoveEventsWithSamePointers() {
        val dispatcher = VideoInputDispatcher(maxCapacity = 10)

        val down = TouchCommand(TouchMapResult(TouchAction.DOWN, listOf(TouchPoint(0, 10f, 10f)), true), 100L)
        val move1 = TouchCommand(TouchMapResult(TouchAction.MOVE, listOf(TouchPoint(0, 11f, 11f)), true), 200L)
        val move2 = TouchCommand(TouchMapResult(TouchAction.MOVE, listOf(TouchPoint(0, 12f, 12f)), true), 300L)
        val up = TouchCommand(TouchMapResult(TouchAction.UP, listOf(TouchPoint(0, 12f, 12f)), true), 400L)

        assertEquals(InputOfferResult.ACCEPTED, dispatcher.offer(down))
        assertEquals(InputOfferResult.ACCEPTED, dispatcher.offer(move1))
        assertEquals(InputOfferResult.DROPPED_COALESCED, dispatcher.offer(move2))
        assertEquals(InputOfferResult.ACCEPTED, dispatcher.offer(up))

        dispatcher.close()
    }

    @Test
    fun rejectsWhenQueueFullOrClosed() {
        val dispatcher = VideoInputDispatcher(maxCapacity = 2)

        val cmd1 = TouchCommand(TouchMapResult(TouchAction.DOWN, listOf(TouchPoint(0, 10f, 10f)), true), 100L)
        val cmd2 = TouchCommand(TouchMapResult(TouchAction.DOWN, listOf(TouchPoint(1, 20f, 20f)), true), 200L)
        val cmd3 = TouchCommand(TouchMapResult(TouchAction.DOWN, listOf(TouchPoint(2, 30f, 30f)), true), 300L)

        assertEquals(InputOfferResult.ACCEPTED, dispatcher.offer(cmd1))
        assertEquals(InputOfferResult.ACCEPTED, dispatcher.offer(cmd2))
        assertEquals(InputOfferResult.REJECTED_QUEUE_FULL, dispatcher.offer(cmd3))

        dispatcher.close()
        val cmdAfter = TouchCommand(TouchMapResult(TouchAction.DOWN, listOf(TouchPoint(0, 10f, 10f)), true), 400L)
        assertEquals(InputOfferResult.REJECTED_SESSION_CLOSED, dispatcher.offer(cmdAfter))
    }
}
