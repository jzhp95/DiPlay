package com.shilapi.xcertplay.media.video

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 访问单元规范化器与 NAL 流边界解析器。
 * 负责解析 Annex-B 码流中的 NAL 类型，识别关键帧 / IDR / CRA，并封装为带租约的 EncodedAccessUnit。
 */
object AccessUnitNormalizer {

    /**
     * NAL 单元解析概要。
     */
    data class NalAnalysis(
        val hasIdr: Boolean,
        val hasCra: Boolean,
        val hasSlice: Boolean,
        val nalTypes: List<Int>
    )

    /**
     * 分析一组 Annex-B 数据中的 NAL 类型。
     */
    fun analyzeNals(payload: ByteArray, codec: CodecType): NalAnalysis {
        var hasIdr = false
        var hasCra = false
        var hasSlice = false
        val types = mutableListOf<Int>()

        var i = 0
        while (i + 3 <= payload.size) {
            val startCodeLen = if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 1.toByte()) {
                3
            } else if (i + 4 <= payload.size && payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 0.toByte() && payload[i + 3] == 1.toByte()) {
                4
            } else {
                0
            }

            if (startCodeLen > 0) {
                val nalStart = i + startCodeLen
                if (nalStart < payload.size) {
                    val header = payload[nalStart].toInt() and 0xFF
                    when (codec) {
                        CodecType.H264 -> {
                            val type = header and 0x1F
                            types.add(type)
                            if (type == 5) hasIdr = true
                            if (type in 1..5) hasSlice = true
                        }
                        CodecType.HEVC -> {
                            val type = (header shr 1) and 0x3F
                            types.add(type)
                            if (type in 19..20) hasIdr = true
                            if (type == 21) hasCra = true
                            if (type in 0..31) hasSlice = true
                        }
                        CodecType.UNKNOWN -> {
                            types.add(header)
                        }
                    }
                }
                i += startCodeLen
            } else {
                i++
            }
        }

        return NalAnalysis(hasIdr = hasIdr, hasCra = hasCra, hasSlice = hasSlice, nalTypes = types)
    }

    /**
     * 将接收到的 Annex-B 字节数组规范化并封装为 EncodedAccessUnit。
     */
    fun normalize(
        payload: ByteArray,
        codec: CodecType,
        streamId: SessionVideoId,
        configGeneration: Long,
        accessUnitId: Long,
        arrivalMonoNs: Long,
        senderNanos: Long = 0L,
        bufferPool: VideoBufferPool? = null
    ): EncodedAccessUnit? {
        if (payload.isEmpty()) return null

        val analysis = analyzeNals(payload, codec)
        val isKeyFrame = analysis.hasIdr || analysis.hasCra
        val randomAccessType = when {
            analysis.hasIdr -> RandomAccessType.IDR
            analysis.hasCra -> RandomAccessType.CRA
            else -> RandomAccessType.NONE
        }

        val sourcePtsUs = if (senderNanos > 0L) senderNanos / 1_000L else null

        val lease = if (bufferPool != null) {
            val pooled = bufferPool.acquire(payload.size)
            if (pooled != null) {
                pooled.buffer.put(payload)
                pooled.buffer.flip()
                pooled
            } else {
                createHeapLease(payload)
            }
        } else {
            createHeapLease(payload)
        }

        return EncodedAccessUnit(
            accessUnitId = accessUnitId,
            streamId = streamId,
            configGeneration = configGeneration,
            lease = lease,
            sourcePtsUs = sourcePtsUs,
            arrivalMonoNs = arrivalMonoNs,
            isKeyFrame = isKeyFrame,
            randomAccessType = randomAccessType
        )
    }

    private fun createHeapLease(bytes: ByteArray): BufferLease {
        val buf = ByteBuffer.wrap(bytes.clone())
        return object : BufferLease {
            private val closed = AtomicBoolean(false)
            override val buffer: ByteBuffer = buf
            override val sizeBytes: Int = bytes.size
            override fun close() {
                closed.set(true)
            }
        }
    }
}
