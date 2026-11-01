package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VideoIngressAdapterTest {

    private val streamId = SessionVideoId("session-01", 110, 1L)

    private class MockVideoRuntime : VideoRuntime {
        var offeredUnits = mutableListOf<EncodedAccessUnit>()
        var configs = mutableListOf<VideoStreamConfig>()
        var nextOfferResult = OfferResult.ACCEPTED

        override fun attachSession(identity: SessionVideoId) {}
        override fun onConfig(config: VideoStreamConfig) {
            configs.add(config)
        }
        override fun tryOfferAccessUnit(unit: EncodedAccessUnit): OfferResult {
            offeredUnits.add(unit)
            return nextOfferResult
        }
        override fun attachOutput(target: OutputTarget) {}
        override fun detachOutput(surfaceGeneration: Long): ReleaseTicket =
            SettableReleaseTicket.completed(ReleaseState.RELEASED)
        override fun setRuntimePolicy(policy: VideoRuntimePolicy) {}
        override fun stop(reason: String): ReleaseTicket =
            SettableReleaseTicket.completed(ReleaseState.RELEASED)
        override fun snapshot(): VideoRuntimeSnapshot =
            VideoRuntimeSnapshot(sessionId = "mock")
    }

    @Test
    fun onConfigIncrementsGenerationOnlyWhenChanged() {
        val mockRuntime = MockVideoRuntime()
        val adapter = VideoIngressAdapter(streamId, mockRuntime)

        val configBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f)
        adapter.onCodec(110, CodecType.H264)

        // First config
        adapter.onConfig(110, configBytes)
        assertEquals(1L, adapter.currentConfigGeneration())
        assertEquals(1, mockRuntime.configs.size)

        // Same config duplicate -> no increment
        adapter.onConfig(110, configBytes)
        assertEquals(1L, adapter.currentConfigGeneration())
        assertEquals(1, mockRuntime.configs.size)

        // Different config -> increments generation
        val newConfigBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x28)
        adapter.onConfig(110, newConfigBytes)
        assertEquals(2L, adapter.currentConfigGeneration())
        assertEquals(2, mockRuntime.configs.size)
    }

    @Test
    fun onFrameNormalizesIdrSliceAndConvertsSenderNanosToUs() {
        val mockRuntime = MockVideoRuntime()
        val adapter = VideoIngressAdapter(streamId, mockRuntime)

        adapter.onCodec(110, CodecType.H264)
        val idrFrame = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x65, // NAL type 5 (IDR)
            0x88.toByte(), 0x84.toByte(), 0x00
        )

        val senderNanos = 1_500_000_000L // 1.5 seconds in ns
        val arrivalNanos = 2_000_000_000L

        val result = adapter.onFrame(110, idrFrame, senderNanos, arrivalNanos)
        assertEquals(OfferResult.ACCEPTED, result)
        assertEquals(1, mockRuntime.offeredUnits.size)

        val unit = mockRuntime.offeredUnits[0]
        assertTrue(unit.isKeyFrame)
        assertEquals(RandomAccessType.IDR, unit.randomAccessType)
        assertEquals(1_500_000L, unit.sourcePtsUs) // converted to us
        assertEquals(arrivalNanos, unit.arrivalMonoNs)
    }

    @Test
    fun onFrameNonIdrSliceIsNotKeyFrame() {
        val mockRuntime = MockVideoRuntime()
        val adapter = VideoIngressAdapter(streamId, mockRuntime)

        adapter.onCodec(110, CodecType.H264)
        val pFrame = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x41, // NAL type 1 (non-IDR slice)
            0x9a.toByte(), 0x00
        )

        val result = adapter.onFrame(110, pFrame, senderNanos = 0L, arrivalNanos = 1_000L)
        assertEquals(OfferResult.ACCEPTED, result)

        val unit = mockRuntime.offeredUnits[0]
        assertFalse(unit.isKeyFrame)
        assertEquals(RandomAccessType.NONE, unit.randomAccessType)
        assertNull(unit.sourcePtsUs) // senderNanos was 0 -> remains null
    }

    @Test
    fun rejectedOfferCleansUpLeaseImmediately() {
        val mockRuntime = MockVideoRuntime()
        mockRuntime.nextOfferResult = OfferResult.REJECTED

        val budget = VideoResourceBudget()
        val pool = VideoBufferPool(budget)
        val adapter = VideoIngressAdapter(streamId, mockRuntime, bufferPool = pool)

        val frame = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x00)
        val result = adapter.onFrame(110, frame)
        assertEquals(OfferResult.REJECTED, result)

        // After rejection, the lease should be closed and recycled
        assertEquals(1, pool.pooledCount())
    }

    @Test
    fun framesForOtherStreamRoleAreRejected() {
        val mockRuntime = MockVideoRuntime()
        val adapter = VideoIngressAdapter(streamId, mockRuntime)

        val frame = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41)
        val result = adapter.onFrame(111, frame) // 111 != 110
        assertEquals(OfferResult.REJECTED, result)
        assertEquals(0, mockRuntime.offeredUnits.size)
    }
}
