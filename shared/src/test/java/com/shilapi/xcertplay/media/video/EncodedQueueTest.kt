package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedQueueTest {

    private val clock = FakeClock(1_000_000L)

    @Test
    fun capacityBoundaryEightFramesAcceptedNinthBackpressure() {
        val queue = EncodedQueue(maxFrames = 8, maxBytes = 1024 * 1024, clock = clock)

        val leases = mutableListOf<CountingBufferLease>()
        for (i in 1..8) {
            val lease = CountingBufferLease(16)
            leases.add(lease)
            val unit = testUnit(lease = lease, accessUnitId = i.toLong())
            assertEquals(OfferResult.ACCEPTED, queue.offer(unit))
        }

        val snap = queue.snapshot()
        assertEquals(8, snap.depth)
        assertEquals(8 * 16L, snap.bytes)

        // 9th frame exceeds frame capacity -> BACKPRESSURE
        val lease9 = CountingBufferLease(16)
        val unit9 = testUnit(lease = lease9, accessUnitId = 9L)
        assertEquals(OfferResult.BACKPRESSURE, queue.offer(unit9))

        // Poll one frame -> can accept again
        val polled = queue.poll()
        assertNotNull(polled)
        assertEquals(1L, polled?.accessUnitId)
        assertEquals(7, queue.snapshot().depth)

        assertEquals(OfferResult.ACCEPTED, queue.offer(unit9))
        assertEquals(8, queue.snapshot().depth)
    }

    @Test
    fun oversizedSingleFrameIsRejectedImmediately() {
        val queue = EncodedQueue(maxFrames = 8, maxBytes = 1024, clock = clock)
        val hugeLease = CountingBufferLease(2048) // 2048 > 1024 maxBytes
        val unit = testUnit(lease = hugeLease)

        assertEquals(OfferResult.REJECTED, queue.offer(unit))
        assertEquals(0, queue.snapshot().depth)
    }

    @Test
    fun outdatedConfigGenerationIsRejected() {
        val queue = EncodedQueue(maxFrames = 8, maxBytes = 1024, clock = clock)
        queue.resetForConfig(newConfigGeneration = 2L)

        // Unit with configGeneration 1L (< 2L) is rejected
        val unit = testUnit(configGeneration = 1L)
        assertEquals(OfferResult.REJECTED, queue.offer(unit))
    }

    @Test
    fun resetForConfigReturnsDiscardedUnitsForCallerCleanup() {
        val queue = EncodedQueue(maxFrames = 8, maxBytes = 1024, clock = clock)
        val lease1 = CountingBufferLease(16)
        val lease2 = CountingBufferLease(16)
        queue.offer(testUnit(lease = lease1, accessUnitId = 1L))
        queue.offer(testUnit(lease = lease2, accessUnitId = 2L))

        assertEquals(2, queue.snapshot().depth)

        val discarded = queue.resetForConfig(newConfigGeneration = 2L)
        assertEquals(2, discarded.size)
        assertEquals(0, queue.snapshot().depth)

        // Caller can safely close discarded leases
        discarded.forEach { it.lease.close() }
        assertEquals(1, lease1.closeCalls)
        assertEquals(1, lease2.closeCalls)
    }

    @Test
    fun closePreventsFurtherOffersAndDrainsPendingUnits() {
        val queue = EncodedQueue(maxFrames = 8, maxBytes = 1024, clock = clock)
        val lease = CountingBufferLease(16)
        queue.offer(testUnit(lease = lease))

        val drained = queue.close()
        assertEquals(1, drained.size)
        assertTrue(queue.isClosed())

        val leaseAfter = CountingBufferLease(16)
        assertEquals(OfferResult.REJECTED, queue.offer(testUnit(lease = leaseAfter)))
    }
}
