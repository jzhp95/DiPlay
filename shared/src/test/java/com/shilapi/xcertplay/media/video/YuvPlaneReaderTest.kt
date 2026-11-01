package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class YuvPlaneReaderTest {

    private val identity = RuntimeIdentity(
        stream = SessionVideoId("s1", 110, 1L),
        configGeneration = 1L,
        decoderGeneration = 1L,
        surfaceGeneration = 1L,
        backendInstanceId = "yuv-reader-1"
    )

    private fun createFrame(
        width: Int,
        height: Int,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int = 1,
        vPixelStride: Int = 1,
        cropLeft: Int = 0,
        cropTop: Int = 0
    ): MemoryDecodedFrameLease {
        val yBuf = ByteBuffer.allocateDirect(yRowStride * (height + cropTop))
        val uBuf = ByteBuffer.allocateDirect(uRowStride * (height / 2 + cropTop / 2))
        val vBuf = ByteBuffer.allocateDirect(vRowStride * (height / 2 + cropTop / 2))

        val yPlane = PlaneLayout(yRowStride, 1, width, height, cropLeft, cropTop)
        val uPlane = PlaneLayout(uRowStride, uPixelStride, width / 2, height / 2, cropLeft / 2, cropTop / 2)
        val vPlane = PlaneLayout(vRowStride, vPixelStride, width / 2, height / 2, cropLeft / 2, cropTop / 2)

        // Fill with pattern
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = yPlane.offsetOf(x, y)
                yBuf.put(offset, ((x + y * 3) % 250).toByte())
            }
        }
        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                val uOffset = uPlane.offsetOf(x, y)
                uBuf.put(uOffset, ((x * 2 + y + 50) % 250).toByte())
                val vOffset = vPlane.offsetOf(x, y)
                vBuf.put(vOffset, ((x + y * 2 + 100) % 250).toByte())
            }
        }

        return MemoryDecodedFrameLease(
            key = FrameKey(identity, 1L, 10_000L),
            outputDescription = DecoderOutputDescription(actualComponent = "TEST_CODEC"),
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            yBuffer = yBuf,
            uBuffer = uBuf,
            vBuffer = vBuf
        )
    }

    @Test
    fun sampleExtractsCorrectYuvComponentsAtSpecifiedPoints() {
        val frame = createFrame(64, 48, 64, 32, 32)
        val points = listOf(SamplePoint(0, 0), SamplePoint(10, 20), SamplePoint(63, 47))

        val result = YuvPlaneReader.sample(frame, points)
        assertEquals(3, result.samples.size)
        assertEquals(frame.key, result.frameKey)

        assertEquals(0, result.samples[0].x)
        assertEquals(0, result.samples[0].y)
        assertEquals(0, result.samples[0].yVal) // (0 + 0*3) % 250 = 0
        assertEquals(50, result.samples[0].uVal) // (0 + 0 + 50) % 250 = 50
        assertEquals(100, result.samples[0].vVal) // (0 + 0 + 100) % 250 = 100
    }

    @Test
    fun sampleThrowsOutOfBoundsWhenCoordinatesInvalid() {
        val frame = createFrame(64, 48, 64, 32, 32)
        try {
            YuvPlaneReader.sample(frame, listOf(SamplePoint(64, 48))) // 64 is out of bounds [0, 63]
            fail("Expected IndexOutOfBoundsException")
        } catch (_: IndexOutOfBoundsException) {}
    }

    @Test
    fun fastPathUploadPlanUsedWhenTightlyPacked() {
        val frame = createFrame(64, 48, 64, 32, 32)
        val plan = YuvPlaneReader.prepareUpload(frame)

        assertFalse("Tightly packed frame should use zero-copy fast path", plan.isCopied)
        assertEquals(UploadTextureMode.THREE_PLANES, plan.mode)
        assertEquals(64, plan.width)
        assertEquals(48, plan.height)
        assertEquals(64 * 48 + 32 * 24 * 2L, plan.bytesCount)
    }

    @Test
    fun packedCopyUsedWhenStrideHasPaddingOrSemiplanar() {
        // Stride 80 > width 64 (padding)
        val framePadded = createFrame(64, 48, 80, 40, 40)
        val planPadded = YuvPlaneReader.prepareUpload(framePadded)

        assertTrue("Padded frame must be repacked into compact buffers", planPadded.isCopied)
        assertEquals(64 * 48, planPadded.yBuffer.remaining())
        assertEquals(32 * 24, planPadded.uBuffer?.remaining())
        assertEquals(32 * 24, planPadded.vBuffer?.remaining())

        // Point-by-point consistency check: verify repacked bytes match original plane view
        for (y in 0 until 48) {
            for (x in 0 until 64) {
                val origVal = framePadded.yView.sample8(x, y)
                val packedVal = planPadded.yBuffer.get(y * 64 + x).toInt() and 0xFF
                assertEquals("Pixel mismatch at ($x, $y)", origVal, packedVal)
            }
        }
    }

    @Test
    fun releasedLeaseRejectsSampleAndUpload() {
        val frame = createFrame(64, 48, 64, 32, 32)
        frame.releaseOnce()

        try {
            YuvPlaneReader.sample(frame, listOf(SamplePoint(0, 0)))
            fail("Expected IllegalStateException on sample after release")
        } catch (_: IllegalStateException) {}

        try {
            YuvPlaneReader.prepareUpload(frame)
            fail("Expected IllegalStateException on prepareUpload after release")
        } catch (_: IllegalStateException) {}
    }
}
