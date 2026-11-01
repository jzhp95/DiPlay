package com.shilapi.xcertplay.media.video

import java.nio.ByteBuffer

/**
 * 采样坐标点。
 */
data class SamplePoint(val x: Int, val y: Int)

/**
 * 单个采样点提取的 YUV 8-bit 分量值。
 */
data class YuvSample(
    val x: Int,
    val y: Int,
    val yVal: Int,
    val uVal: Int,
    val vVal: Int
)

/**
 * 采样操作结果。
 */
data class SampleResult(
    val samples: List<YuvSample>,
    val frameKey: FrameKey
)

/**
 * 独立 Plane 读取、采样与纹理上传准备器 (YuvPlaneReader)。
 * 严禁使用旧版 Yuv420Frame，负责安全的边界偏移计算与行对齐拷贝。
 */
object YuvPlaneReader {

    /**
     * 在 CPU 端直接采样指定像素点分量，用于取证和颜色核验。
     */
    fun sample(frame: DecodedFrameLease, points: List<SamplePoint>): SampleResult {
        val memoryFrame = frame as? MemoryDecodedFrameLease
            ?: throw IllegalArgumentException("Frame lease must be MemoryDecodedFrameLease, got ${frame::class.java.simpleName}")

        val samples = points.map { pt ->
            if (pt.x < 0 || pt.x >= memoryFrame.yPlane.width || pt.y < 0 || pt.y >= memoryFrame.yPlane.height) {
                throw IndexOutOfBoundsException(
                    "Sample point ($pt.x, $pt.y) out of frame bounds (${memoryFrame.yPlane.width}x${memoryFrame.yPlane.height})"
                )
            }

            val y = memoryFrame.yView.sample8(pt.x, pt.y)
            // 色度分量降采样 2x2
            val chromaX = pt.x / 2
            val chromaY = pt.y / 2
            val u = memoryFrame.uView.sample8(chromaX, chromaY)
            val v = memoryFrame.vView.sample8(chromaX, chromaY)

            YuvSample(pt.x, pt.y, y, u, v)
        }

        return SampleResult(samples, frame.key)
    }

    /**
     * 为 GLES2 纹理上传准备紧凑平铺的直接 ByteBuffer。
     * 若平面包含行 stride padding 或 pixelStride > 1，则打包复制至紧凑连续的平铺缓冲区。
     */
    fun prepareUpload(frame: DecodedFrameLease): UploadPlan {
        val mem = frame as? MemoryDecodedFrameLease
            ?: throw IllegalArgumentException("Frame lease must be MemoryDecodedFrameLease")

        if (mem.isReleased()) {
            throw IllegalStateException("Cannot prepare upload for released frame lease")
        }

        val width = mem.yPlane.width
        val height = mem.yPlane.height
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        val yTightlyPacked = mem.yPlane.pixelStride == 1 &&
                mem.yPlane.rowStride == width &&
                mem.yPlane.cropLeft == 0 &&
                mem.yPlane.cropTop == 0

        val uTightlyPacked = mem.uPlane.pixelStride == 1 &&
                mem.uPlane.rowStride == chromaWidth &&
                mem.uPlane.cropLeft == 0 &&
                mem.uPlane.cropTop == 0

        val vTightlyPacked = mem.vPlane.pixelStride == 1 &&
                mem.vPlane.rowStride == chromaWidth &&
                mem.vPlane.cropLeft == 0 &&
                mem.vPlane.cropTop == 0

        // 快路径：三平面原生紧凑且连续
        if (yTightlyPacked && uTightlyPacked && vTightlyPacked) {
            val totalBytes = (width * height + chromaWidth * chromaHeight * 2).toLong()
            return UploadPlan(
                frameKey = frame.key,
                mode = UploadTextureMode.THREE_PLANES,
                width = width,
                height = height,
                yBuffer = mem.yView.buffer.asReadOnlyBuffer().apply { rewind() },
                uBuffer = mem.uView.buffer.asReadOnlyBuffer().apply { rewind() },
                vBuffer = mem.vView.buffer.asReadOnlyBuffer().apply { rewind() },
                uvBuffer = null,
                isCopied = false,
                bytesCount = totalBytes
            )
        }

        // 通用安全路径：存在 stride padding 或非平铺布局，复制到紧凑连续 DirectBuffer
        val packedY = ByteBuffer.allocateDirect(width * height)
        val packedU = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)
        val packedV = ByteBuffer.allocateDirect(chromaWidth * chromaHeight)

        // 打包 Y 平面
        for (row in 0 until height) {
            for (col in 0 until width) {
                val byteVal = mem.yView.sample8(col, row).toByte()
                packedY.put(byteVal)
            }
        }
        packedY.flip()

        // 打包 U 平面
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val byteVal = mem.uView.sample8(col, row).toByte()
                packedU.put(byteVal)
            }
        }
        packedU.flip()

        // 打包 V 平面
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val byteVal = mem.vView.sample8(col, row).toByte()
                packedV.put(byteVal)
            }
        }
        packedV.flip()

        val totalBytes = (packedY.capacity() + packedU.capacity() + packedV.capacity()).toLong()

        return UploadPlan(
            frameKey = frame.key,
            mode = UploadTextureMode.THREE_PLANES,
            width = width,
            height = height,
            yBuffer = packedY,
            uBuffer = packedU,
            vBuffer = packedV,
            uvBuffer = null,
            isCopied = true,
            bytesCount = totalBytes
        )
    }
}
