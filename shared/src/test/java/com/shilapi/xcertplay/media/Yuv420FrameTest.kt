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
}
