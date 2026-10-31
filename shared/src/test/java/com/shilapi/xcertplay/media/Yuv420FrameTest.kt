package com.shilapi.xcertplay.media

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Yuv420FrameTest {
    private fun bytes(buffer: ByteBuffer) = ByteArray(buffer.remaining()).also { buffer.get(it) }.map { it.toInt() and 255 }
    @Test fun oddCropAndInterleavedChromaRespectPositionsAndStrides() {
        val y = ByteBuffer.allocate(26).apply {
            position(2)
            for (row in 0..3) for (column in 0..3) put(2 + row * 6 + column, (row * 10 + column).toByte())
        }
        val uv = ByteBuffer.wrap(byteArrayOf(90, 140.toByte(), 91, 141.toByte(), 92, 142.toByte(), 93, 143.toByte()))
        val u = uv.duplicate()
        val v = uv.duplicate().apply { position(1) }
        val frame = Yuv420Frame()
        frame.copy(4, 4, 1, 1, 4, 4, listOf(
            Yuv420Frame.Plane(y, 6, 1), Yuv420Frame.Plane(u, 4, 2), Yuv420Frame.Plane(v, 4, 2)))
        assertEquals(listOf(11, 12, 13, 21, 22, 23, 31, 32, 33), bytes(frame.plane(0)))
        assertEquals(listOf(90, 91, 92, 93), bytes(frame.plane(1)))
        assertEquals(listOf(140, 141, 142, 143), bytes(frame.plane(2)))
        assertArrayEquals(floatArrayOf(.25f, .25f), frame.chromaOffset, 0f)
        assertArrayEquals(floatArrayOf(.75f, .75f), frame.chromaScale, 0f)
        assertEquals(2, y.position()); assertEquals(1, v.position())
    }
    @Test fun theLastRowDoesNotNeedUnusedStridePadding() {
        val y = ByteBuffer.wrap(ByteArray(21) { it.toByte() })
        val uv = ByteBuffer.wrap(ByteArray(13) { (100 + it).toByte() })
        val frame = Yuv420Frame()
        frame.copy(5, 3, 0, 0, 5, 3, listOf(
            Yuv420Frame.Plane(y, 8, 1), Yuv420Frame.Plane(uv, 8, 2), Yuv420Frame.Plane(uv, 8, 2)))
        assertEquals(listOf(0, 1, 2, 3, 4, 8, 9, 10, 11, 12, 16, 17, 18, 19, 20), bytes(frame.plane(0)))
        assertEquals(listOf(100, 102, 104, 108, 110, 112), bytes(frame.plane(1)))
    }
    @Test fun smallerFramesDoNotPublishTrailingPixelsFromThePreviousFrame() {
        val frame = Yuv420Frame()
        val y = Yuv420Frame.Plane(ByteBuffer.wrap(ByteArray(16) { it.toByte() }), 4, 1)
        val uv = Yuv420Frame.Plane(ByteBuffer.wrap(byteArrayOf(90, 91, 92, 93)), 2, 1)
        frame.copy(4, 4, 0, 0, 4, 4, listOf(y, uv, uv))
        frame.copy(4, 4, 0, 0, 2, 2, listOf(y, uv, uv))
        assertEquals(listOf(0, 1, 4, 5), bytes(frame.plane(0)))
        assertEquals(listOf(90), bytes(frame.plane(1)))
    }
    @Test fun truncatedOrOverlappingPlanesFailBeforeReadingOutsideTheBuffer() {
        val frame = Yuv420Frame()
        val small = Yuv420Frame.Plane(ByteBuffer.allocate(1), 2, 1)
        assertThrows(IllegalArgumentException::class.java) { frame.copy(4, 4, 0, 0, 4, 4, listOf(small, small, small)) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(4, 4, -1, 0, 4, 4, listOf(small, small, small)) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(8192, 8192, 0, 0, 1, 1, listOf(small, small, small)) }
    }
    @Test fun samplingRawPointsExtractsBoundedValues() {
        val frame = Yuv420Frame()
        val y = Yuv420Frame.Plane(ByteBuffer.wrap(ByteArray(16) { (it * 10).toByte() }), 4, 1)
        val u = Yuv420Frame.Plane(ByteBuffer.wrap(ByteArray(4) { 100.toByte() }), 2, 1)
        val v = Yuv420Frame.Plane(ByteBuffer.wrap(ByteArray(4) { 150.toByte() }), 2, 1)
        frame.copy(4, 4, 0, 0, 4, 4, listOf(y, u, v))
        val samples = frame.sample(listOf(0 to 0, 1 to 1, 3 to 3, 10 to 10))
        assertEquals(3, samples.size)
        assertEquals(0, samples[0].x)
        assertEquals(0, samples[0].y)
        assertEquals(0, samples[0].yCode)
        assertEquals(100, samples[0].uCode)
        assertEquals(150, samples[0].vCode)
        assertEquals(50, samples[1].yCode)
        assertEquals(150, samples[2].yCode)
    }

    @Test fun oddCropOffsetsProduceCorrectChromaOffsetAndScale() {
        val frame = Yuv420Frame()
        val y = Yuv420Frame.Plane(ByteBuffer.wrap(ByteArray(64) { it.toByte() }), 8, 1)
        val uv = Yuv420Frame.Plane(ByteBuffer.wrap(ByteArray(32) { (100 + it).toByte() }), 8, 2)
        // odd crop: left = 1, top = 1, right = 6, bottom = 5 -> width = 5, height = 4
        frame.copy(8, 8, 1, 1, 6, 5, listOf(y, uv, uv))
        assertEquals(5, frame.width)
        assertEquals(4, frame.height)
        assertEquals(3, frame.chromaWidth)
        assertEquals(3, frame.chromaHeight)
        assertEquals(1f / 6f, frame.chromaOffset[0], 0.0001f)
        assertEquals(1f / 6f, frame.chromaOffset[1], 0.0001f)
        assertEquals(5f / 6f, frame.chromaScale[0], 0.0001f)
        assertEquals(2f / 3f, frame.chromaScale[1], 0.0001f)

        val samples = frame.sample(listOf(0 to 0))
        assertEquals(1, samples.size)
        assertEquals(9, samples[0].yCode)
    }

    @Test fun oddCropChromaMappingReadsCorrectSubsampledPixel() {
        val frame = Yuv420Frame()
        val y = Yuv420Frame.Plane(ByteBuffer.wrap(ByteArray(32) { (it + 1).toByte() }), 8, 1)
        val u = Yuv420Frame.Plane(ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)), 4, 1)
        val v = Yuv420Frame.Plane(ByteBuffer.wrap(byteArrayOf(11, 21, 31, 41, 51, 61, 71, 81)), 4, 1)

        // left = 1, width = 4 (right = 5), top = 0, height = 4 (bottom = 4)
        frame.copy(8, 4, 1, 0, 5, 4, listOf(y, u, v))
        assertEquals(4, frame.width)
        assertEquals(3, frame.chromaWidth)

        // px = 0 -> coded luma x = 1 -> coded chroma x = 0 (first sample: 10, 11)
        // px = 1 -> coded luma x = 2 -> coded chroma x = 1 (second sample: 20, 21)
        // px = 2 -> coded luma x = 3 -> coded chroma x = 1 (second sample: 20, 21)
        // px = 3 -> coded luma x = 4 -> coded chroma x = 2 (third sample: 30, 31)
        val samples = frame.sample(listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0))
        assertEquals(4, samples.size)
        assertEquals(10, samples[0].uCode)
        assertEquals(11, samples[0].vCode)
        assertEquals(20, samples[1].uCode)
        assertEquals(21, samples[1].vCode)
        assertEquals(20, samples[2].uCode)
        assertEquals(21, samples[2].vCode)
        assertEquals(30, samples[3].uCode)
        assertEquals(31, samples[3].vCode)
    }
}
