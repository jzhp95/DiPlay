package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class VideoLeaseTest {

    private val testKey = FrameKey(
        RuntimeIdentity(SessionVideoId("s1"), 1L, 1L, 1L, "backend-1"),
        100L,
        200_000L
    )

    @Test
    fun releaseOnceIsIdempotentAndPropagatesFirstException() {
        var callCount = 0
        val lease = object : AbstractDecodedFrameLease(
            testKey,
            FrameStorageKind.MEDIA_CODEC_BUFFER,
            DecoderOutputDescription()
        ) {
            override fun doRelease() {
                callCount++
                throw IllegalStateException("Hardware buffer return failure")
            }
        }

        assertFalse(lease.isReleased())
        assertEquals(0, lease.releaseAttempts)

        // First attempt throws
        assertThrows(IllegalStateException::class.java) {
            lease.releaseOnce()
        }
        assertTrue(lease.isReleased())
        assertEquals(1, callCount)
        assertEquals(1, lease.releaseAttempts)

        // Subsequent attempt does NOT re-throw or call doRelease again
        lease.releaseOnce()
        assertEquals(1, callCount)
        assertEquals(2, lease.releaseAttempts)
    }

    @Test
    fun planeViewSamplesCorrectByteAndThrowsOnOutOfBounds() {
        val width = 4
        val height = 4
        val buffer = ByteBuffer.allocateDirect(16)
        // Fill buffer with sequential values 0..15
        for (i in 0 until 16) {
            buffer.put(i.toByte())
        }
        buffer.flip()

        val layout = PlaneLayout(rowStride = 4, pixelStride = 1, width = width, height = height)
        var active = true
        val planeView = PlaneView(buffer, layout) { active }

        // Test valid samples
        assertEquals(0, planeView.sample8(0, 0))
        assertEquals(5, planeView.sample8(1, 1))
        assertEquals(15, planeView.sample8(3, 3))

        // Out of bounds coordinate
        assertThrows(IndexOutOfBoundsException::class.java) {
            planeView.sample8(4, 0)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            planeView.sample8(0, -1)
        }

        // Access after lease closed
        active = false
        assertThrows(IllegalStateException::class.java) {
            planeView.sample8(0, 0)
        }
    }

    @Test
    fun releaseTicketNotifiesListenersAndClearsReference() {
        val ticket = SettableReleaseTicket()
        assertEquals(ReleaseState.PENDING, ticket.state())

        val notifiedState = AtomicInteger(0)
        val subscription = ticket.onComplete { state ->
            if (state == ReleaseState.RELEASED) {
                notifiedState.incrementAndGet()
            }
        }

        ticket.complete(ReleaseState.RELEASED)
        assertEquals(ReleaseState.RELEASED, ticket.state())
        assertEquals(1, notifiedState.get())

        // Subscribing after complete triggers immediately
        var lateState: ReleaseState? = null
        ticket.onComplete { lateState = it }
        assertEquals(ReleaseState.RELEASED, lateState)
    }

    @Test
    fun releaseTicketCanUnsubscribeBeforeComplete() {
        val ticket = SettableReleaseTicket()
        var called = false
        val sub = ticket.onComplete { called = true }

        sub.close()
        ticket.complete(ReleaseState.RELEASED)
        assertFalse(called)
    }

    @Test
    fun releaseTicketBlockedStateCannotBeFaked() {
        val ticket = SettableReleaseTicket()
        ticket.complete(ReleaseState.BLOCKED)

        assertEquals(ReleaseState.BLOCKED, ticket.state())
        // awaitUntil immediately returns BLOCKED
        val result = ticket.awaitUntil(System.nanoTime() + 10_000_000L)
        assertEquals(ReleaseState.BLOCKED, result)
    }
}
