package com.shilapi.xcertplay.media

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/** Copies owned, tightly packed planes while respecting codec crop, buffer position and both strides. */
class Yuv420Frame {
    data class Plane(val bytes: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    data class RawSample(
        val x: Int,
        val y: Int,
        val yCode: Int,
        val uCode: Int,
        val vCode: Int,
        val cropLeft: Int = 0,
        val cropTop: Int = 0,
        val cropRight: Int = 0,
        val cropBottom: Int = 0,
        val rowStride: Int = 0,
        val pixelStride: Int = 0,
        val patchIndex: Int? = null,
        val nominal: Int? = null,
        val frameId: String? = null,
        val instanceId: String? = null,
    ) {
        fun toMap(): Map<String, Any?> = buildMap {
            put("x", x)
            put("y", y)
            put("yCode", yCode)
            put("uCode", uCode)
            put("vCode", vCode)
            put("crop", listOf(cropLeft, cropTop, cropRight, cropBottom))
            put("rowStride", rowStride)
            put("pixelStride", pixelStride)
            if (patchIndex != null) put("patchIndex", patchIndex)
            if (nominal != null) put("nominal", nominal)
            if (frameId != null) put("frameId", frameId)
            if (instanceId != null) put("instanceId", instanceId)
        }
    }

    private val storage = arrayOfNulls<ByteBuffer>(3)
    private var rowScratch = ByteArray(0)
    private var rowOut = ByteArray(0)

    var cropLeft = 0; private set
    var cropTop = 0; private set
    var cropRight = 0; private set
    var cropBottom = 0; private set
    var width = 0; private set
    var height = 0; private set
    var chromaWidth = 0; private set
    var chromaHeight = 0; private set
    var chromaOffset = floatArrayOf(0f, 0f); private set
    var chromaScale = floatArrayOf(1f, 1f); private set
    var rowStride = 0; private set
    var pixelStride = 0; private set
    fun plane(index: Int): ByteBuffer = checkNotNull(storage[index]).duplicate().apply { position(0) }

    fun sample(
        points: List<Pair<Int, Int>>,
        patchIndices: List<Int?> = emptyList(),
        nominals: List<Int?> = emptyList(),
        frameId: String? = null,
        instanceId: String? = null,
    ): List<RawSample> {
        val yPlane = plane(0)
        val uPlane = plane(1)
        val vPlane = plane(2)
        val samples = ArrayList<RawSample>(minOf(points.size, 384))
        for (i in 0 until minOf(points.size, 384)) {
            val (px, py) = points[i]
            if (px in 0 until width && py in 0 until height) {
                val yVal = yPlane.get(py * width + px).toInt() and 255
                val cx = ((cropLeft + px) / 2 - cropLeft / 2).coerceIn(0 until chromaWidth)
                val cy = ((cropTop + py) / 2 - cropTop / 2).coerceIn(0 until chromaHeight)
                val uVal = uPlane.get(cy * chromaWidth + cx).toInt() and 255
                val vVal = vPlane.get(cy * chromaWidth + cx).toInt() and 255
                val pIdx = patchIndices.getOrNull(i)
                val nom = nominals.getOrNull(i)
                samples.add(RawSample(
                    x = px, y = py, yCode = yVal, uCode = uVal, vCode = vVal,
                    cropLeft = cropLeft, cropTop = cropTop, cropRight = cropRight, cropBottom = cropBottom,
                    rowStride = rowStride, pixelStride = pixelStride,
                    patchIndex = pIdx, nominal = nom, frameId = frameId, instanceId = instanceId,
                ))
            }
        }
        return samples
    }

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
        cropLeft = left
        cropTop = top
        cropRight = right
        cropBottom = bottom
        width = right - left; height = bottom - top
        chromaWidth = (right + 1) / 2 - left / 2
        chromaHeight = (bottom + 1) / 2 - top / 2
        chromaOffset = floatArrayOf((left % 2) / (2f * chromaWidth), (top % 2) / (2f * chromaHeight))
        chromaScale = floatArrayOf(width / (2f * chromaWidth), height / (2f * chromaHeight))
        rowStride = planes.firstOrNull()?.rowStride ?: 0
        pixelStride = planes.firstOrNull()?.pixelStride ?: 1

        val maxRowLen = maxOf(width, chromaWidth * 2)
        if (rowScratch.size < maxRowLen) {
            rowScratch = ByteArray(maxRowLen)
            rowOut = ByteArray(maxRowLen)
        }

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

            val pStride = source.pixelStride
            for (row in 0 until h) {
                val startLong = origin + (y.toLong() + row) * source.rowStride + x.toLong() * pStride
                val start = startLong.toInt()
                if (pStride == 1) {
                    input.limit(start + w).position(start)
                    output.put(input)
                    input.limit(source.bytes.limit())
                } else if (pStride == 2) {
                    // Bulk row read fast path for interleaved semiplanar chroma (NV12/NV21)
                    val readSpan = (w - 1) * 2 + 1
                    input.position(start)
                    input.get(rowScratch, 0, readSpan)
                    for (col in 0 until w) {
                        rowOut[col] = rowScratch[col * 2]
                    }
                    output.put(rowOut, 0, w)
                } else {
                    for (column in 0 until w) output.put(input.get(start + column * pStride))
                }
            }
            output.flip()
        }
    }
}
