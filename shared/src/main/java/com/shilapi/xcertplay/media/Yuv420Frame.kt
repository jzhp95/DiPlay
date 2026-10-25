package com.shilapi.xcertplay.media

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/** Copies owned, tightly packed planes while respecting codec crop, buffer position and both strides. */
internal class Yuv420Frame {
    data class Plane(val bytes: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    private val storage = arrayOfNulls<ByteBuffer>(3)
    var width = 0; private set
    var height = 0; private set
    var chromaWidth = 0; private set
    var chromaHeight = 0; private set
    var chromaOffset = floatArrayOf(0f, 0f); private set
    var chromaScale = floatArrayOf(1f, 1f); private set
    fun plane(index: Int): ByteBuffer = checkNotNull(storage[index]).duplicate().apply { position(0) }

    fun copy(image: Image) {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "decoder did not output 8-bit YUV420" }
        val crop = image.cropRect
        copy(image.width, image.height, crop.left, crop.top, crop.right, crop.bottom,
            image.planes.map { Plane(it.buffer, it.rowStride, it.pixelStride) })
    }

    fun copy(codedWidth: Int, codedHeight: Int, left: Int, top: Int, right: Int, bottom: Int, planes: List<Plane>) {
        require(codedWidth in 1..8192 && codedHeight in 1..8192 &&
            codedWidth.toLong() * codedHeight <= 16L * 1024 * 1024) { "YUV dimensions out of bounds" }
        require(left >= 0 && top >= 0 && right in (left + 1)..codedWidth && bottom in (top + 1)..codedHeight)
        require(planes.size == 3)
        width = right - left; height = bottom - top
        chromaWidth = (right + 1) / 2 - left / 2
        chromaHeight = (bottom + 1) / 2 - top / 2
        chromaOffset = floatArrayOf((left % 2) / (2f * chromaWidth), (top % 2) / (2f * chromaHeight))
        chromaScale = floatArrayOf(width / (2f * chromaWidth), height / (2f * chromaHeight))
        for (index in 0..2) {
            val w = if (index == 0) width else chromaWidth
            val h = if (index == 0) height else chromaHeight
            val x = if (index == 0) left else left / 2
            val y = if (index == 0) top else top / 2
            val source = planes[index]
            require(source.rowStride > 0 && source.pixelStride > 0)
            require((x.toLong() + w - 1) * source.pixelStride < source.rowStride) { "YUV row overlaps its stride" }
            val input = source.bytes.duplicate()
            val origin = input.position().toLong()
            val end = origin + (y.toLong() + h - 1) * source.rowStride + (x.toLong() + w - 1) * source.pixelStride
            require(end < input.limit()) { "truncated YUV plane" }
            val count = w * h
            val output = storage[index]?.takeIf { it.capacity() >= count }
                ?: ByteBuffer.allocateDirect(count).also { storage[index] = it }
            output.clear()
            for (row in 0 until h) {
                val start = (origin + (y.toLong() + row) * source.rowStride + x.toLong() * source.pixelStride).toInt()
                if (source.pixelStride == 1) {
                    input.limit(start + w).position(start)
                    output.put(input)
                    input.limit(source.bytes.limit())
                } else for (column in 0 until w) output.put(input.get(start + column * source.pixelStride))
            }
            output.flip()
        }
    }
}
