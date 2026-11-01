package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class ColorEvidenceSamplerTest {

    private fun createFrame(): MemoryDecodedFrameLease {
        val yBuf = ByteBuffer.allocateDirect(64 * 48)
        val uBuf = ByteBuffer.allocateDirect(32 * 24)
        val vBuf = ByteBuffer.allocateDirect(32 * 24)
        return MemoryDecodedFrameLease(
            key = FrameKey(RuntimeIdentity(SessionVideoId("s1"), 1L, 1L, 1L, "sample-test"), 1L, 10_000L),
            outputDescription = DecoderOutputDescription(actualComponent = "TEST_CODEC"),
            yPlane = PlaneLayout(64, 1, 64, 48),
            uPlane = PlaneLayout(32, 1, 32, 24),
            vPlane = PlaneLayout(32, 1, 32, 24),
            yBuffer = yBuf,
            uBuffer = uBuf,
            vBuffer = vBuf
        )
    }

    @Test
    fun samplesBeforeUploadWithinBounds() {
        val frame = createFrame()
        val decision = ColorDecision.Accepted(
            ResolvedPixelContract(),
            VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601)
        )
        val request = SampleRequest(
            points = listOf(SamplePoint(10, 10), SamplePoint(20, 20)),
            maxSamples = 64
        )

        val result = ColorEvidenceSampler.sampleBeforeUpload(frame, decision, request)
        assertEquals(2, result.samples.size)
        assertEquals(frame.key, result.frameKey)
    }

    @Test
    fun rejectsSamplingAfterLeaseReleased() {
        val frame = createFrame()
        frame.releaseOnce()

        val decision = ColorDecision.Accepted(
            ResolvedPixelContract(),
            VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601)
        )
        val request = SampleRequest(listOf(SamplePoint(0, 0)))

        try {
            ColorEvidenceSampler.sampleBeforeUpload(frame, decision, request)
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun boundsSampleCountToMaxAllowed() {
        val frame = createFrame()
        val decision = ColorDecision.Accepted(
            ResolvedPixelContract(),
            VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601)
        )
        // 80 points requested with limit of 64
        val points = (0 until 80).map { SamplePoint(it % 64, it % 48) }
        val request = SampleRequest(points, maxSamples = 64)

        val result = ColorEvidenceSampler.sampleBeforeUpload(frame, decision, request)
        assertEquals(64, result.samples.size)
    }
}
