package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoContractsTest {

    @Test
    fun modifyingSourceByteArrayDoesNotMutateConfigCsd() {
        val originalBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42)
        val config = VideoStreamConfig(
            streamId = SessionVideoId("test-session"),
            codec = CodecType.H264,
            width = 1920,
            height = 1080,
            csdData = listOf(originalBytes)
        )

        // Mutate original array
        originalBytes[4] = 0xFF.toByte()

        // Config CSD should remain unchanged
        assertEquals(0x67.toByte(), config.csd[0][4])
        assertNotEquals(originalBytes[4], config.csd[0][4])
    }

    @Test
    fun sameCsdDataProducesSameHashAndEquality() {
        val bytes1 = byteArrayOf(1, 2, 3, 4)
        val bytes2 = byteArrayOf(1, 2, 3, 4)

        val config1 = VideoStreamConfig(
            streamId = SessionVideoId("test-session"),
            codec = CodecType.H264,
            width = 1280,
            height = 720,
            csdData = listOf(bytes1)
        )
        val config2 = VideoStreamConfig(
            streamId = SessionVideoId("test-session"),
            codec = CodecType.H264,
            width = 1280,
            height = 720,
            csdData = listOf(bytes2)
        )

        assertEquals(config1.csdHash, config2.csdHash)
        assertEquals(config1, config2)
    }

    @Test
    fun differentGenerationsAreNotEqual() {
        val stream = SessionVideoId("test-session")
        val identity1 = RuntimeIdentity(stream, configGeneration = 1L, decoderGeneration = 1L, surfaceGeneration = 1L, "backend-1")
        val identity2 = RuntimeIdentity(stream, configGeneration = 2L, decoderGeneration = 1L, surfaceGeneration = 1L, "backend-1")

        assertNotEquals(identity1, identity2)

        val frameKey1 = FrameKey(identity1, accessUnitId = 100L, codecPtsUs = 50_000L)
        val frameKey2 = FrameKey(identity2, accessUnitId = 100L, codecPtsUs = 50_000L)

        assertNotEquals(frameKey1, frameKey2)
    }

    @Test
    fun missingSourcePtsRemainsNull() {
        val unit = testUnit(sourcePtsUs = null, arrivalMonoNs = 999_999L)
        assertNull(unit.sourcePtsUs)
        assertEquals(999_999L, unit.arrivalMonoNs)
    }

    @Test
    fun countingBufferLeaseTracksCloseIdempotently() {
        val lease = CountingBufferLease(64)
        assertEquals(0, lease.closeCalls)

        lease.close()
        assertEquals(1, lease.closeCalls)

        lease.close()
        assertEquals(1, lease.closeCalls)
    }

    @Test
    fun releaseAttemptTracksCallsAndPropagatesFailure() {
        var attempts = 0
        val frame = testFrame {
            attempts++
            throw IllegalStateException("Injected release failure")
        }

        assertThrows(IllegalStateException::class.java) {
            frame.releaseOnce()
        }
        assertEquals(1, attempts)
        assertEquals(1, frame.releaseAttempts)

        // Subsequent releaseOnce attempts are counted but do not re-throw / re-invoke closed callback
        frame.releaseOnce()
        assertEquals(1, attempts)
        assertEquals(2, frame.releaseAttempts)
    }

    @Test
    fun fakeClockAdvancesMonotonically() {
        val clock = FakeClock(startNs = 1_000L)
        assertEquals(1_000L, clock.nowNs())

        clock.advanceNs(500L)
        assertEquals(1_500L, clock.nowNs())

        assertThrows(IllegalArgumentException::class.java) {
            clock.advanceNs(-10L)
        }
    }
}
