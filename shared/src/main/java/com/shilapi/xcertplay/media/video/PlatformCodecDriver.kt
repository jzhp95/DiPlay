package com.shilapi.xcertplay.media.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 图像平面像素描述接口。
 */
interface CodecImagePlane {
    val buffer: ByteBuffer
    val rowStride: Int
    val pixelStride: Int
}

/**
 * 解码器输出图像抽象接口（API 28 Image 封装）。
 */
interface CodecOutputImage : AutoCloseable {
    val width: Int
    val height: Int
    val format: Int
    val cropLeft: Int
    val cropTop: Int
    val cropWidth: Int
    val cropHeight: Int
    val planes: Array<CodecImagePlane>
    override fun close()
}

/**
 * Android 平台 Image 真实包装。
 */
class AndroidCodecOutputImage(private val image: Image) : CodecOutputImage {
    override val width: Int get() = image.width
    override val height: Int get() = image.height
    override val format: Int get() = image.format
    override val cropLeft: Int get() = image.cropRect?.left ?: 0
    override val cropTop: Int get() = image.cropRect?.top ?: 0
    override val cropWidth: Int get() = image.cropRect?.width() ?: image.width
    override val cropHeight: Int get() = image.cropRect?.height() ?: image.height
    override val planes: Array<CodecImagePlane> by lazy {
        image.planes.map { AndroidCodecImagePlane(it) }.toTypedArray()
    }
    override fun close() {
        image.close()
    }
}

class AndroidCodecImagePlane(private val plane: Image.Plane) : CodecImagePlane {
    override val buffer: ByteBuffer get() = plane.buffer
    override val rowStride: Int get() = plane.rowStride
    override val pixelStride: Int get() = plane.pixelStride
}

/**
 * 解码器输出轮询结果。
 */
sealed interface CodecDriverOutput {
    data class OutputBuffer(
        val index: Int,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int
    ) : CodecDriverOutput

    data class OutputFormatChanged(val format: MediaFormat) : CodecDriverOutput
    object TryAgainLater : CodecDriverOutput
    object EndOfStream : CodecDriverOutput
    data class DriverError(val cause: Throwable) : CodecDriverOutput
}

/**
 * 平台解码驱动抽象层（严格限定在 Android 9 / API 28 平台能力）。
 * 剥离 MediaCodec 物理依赖，便于注入测试与错误模拟。
 */
interface PlatformCodecDriver : AutoCloseable {
    val componentName: String
    val isHardwareAccelerated: Boolean

    fun configure(format: MediaFormat, surface: Surface?, flags: Int)
    fun start()
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun dequeueOutputBuffer(bufferInfo: MediaCodec.BufferInfo, timeoutUs: Long): CodecDriverOutput
    fun getOutputImage(index: Int): CodecOutputImage? = null
    fun releaseOutputBuffer(index: Int, render: Boolean)
    fun releaseOutputBufferAtTime(index: Int, renderTimestampNs: Long)
    fun setOutputSurface(surface: Surface)
    fun flush()
    fun stop()
    override fun close()
}

/**
 * Android 平台 MediaCodec 真实驱动封装（API 28 兼容）。
 */
class AndroidMediaCodecDriver(
    private val codec: MediaCodec,
    override val componentName: String
) : PlatformCodecDriver {

    // API 28 兼容判断：非 Google / AOSP 默认软解前缀即视为平台硬件组件
    override val isHardwareAccelerated: Boolean =
        !componentName.startsWith("OMX.google.", ignoreCase = true) &&
        !componentName.startsWith("c2.android.", ignoreCase = true)

    override fun configure(format: MediaFormat, surface: Surface?, flags: Int) {
        codec.configure(format, surface, null, flags)
    }

    override fun start() {
        codec.start()
    }

    override fun dequeueInputBuffer(timeoutUs: Long): Int =
        codec.dequeueInputBuffer(timeoutUs)

    override fun getInputBuffer(index: Int): ByteBuffer? =
        codec.getInputBuffer(index)

    override fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int
    ) {
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun dequeueOutputBuffer(
        bufferInfo: MediaCodec.BufferInfo,
        timeoutUs: Long
    ): CodecDriverOutput {
        return try {
            val res = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                res >= 0 -> {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        CodecDriverOutput.EndOfStream
                    } else {
                        CodecDriverOutput.OutputBuffer(
                            index = res,
                            offset = bufferInfo.offset,
                            size = bufferInfo.size,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            flags = bufferInfo.flags
                        )
                    }
                }
                res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    CodecDriverOutput.OutputFormatChanged(codec.outputFormat)
                }
                res == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    CodecDriverOutput.TryAgainLater
                }
                else -> CodecDriverOutput.TryAgainLater
            }
        } catch (t: Throwable) {
            CodecDriverOutput.DriverError(t)
        }
    }

    override fun getOutputImage(index: Int): CodecOutputImage? {
        return try {
            val img = codec.getOutputImage(index) ?: return null
            AndroidCodecOutputImage(img)
        } catch (_: Throwable) {
            null
        }
    }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        codec.releaseOutputBuffer(index, render)
    }

    override fun releaseOutputBufferAtTime(index: Int, renderTimestampNs: Long) {
        codec.releaseOutputBuffer(index, renderTimestampNs)
    }

    override fun setOutputSurface(surface: Surface) {
        codec.setOutputSurface(surface)
    }

    override fun flush() {
        codec.flush()
    }

    override fun stop() {
        codec.stop()
    }

    override fun close() {
        codec.release()
    }

    companion object {
        fun createByCodecName(name: String): AndroidMediaCodecDriver =
            AndroidMediaCodecDriver(MediaCodec.createByCodecName(name), name)

        fun createDecoderByType(mimeType: String): AndroidMediaCodecDriver {
            val codec = MediaCodec.createDecoderByType(mimeType)
            return AndroidMediaCodecDriver(codec, codec.name)
        }

        fun createDecoder(
            mimeType: String,
            preference: DecoderPreference = DecoderPreference.AUTO
        ): AndroidMediaCodecDriver {
            if (preference != DecoderPreference.AUTO) {
                val candidate = findCodec(mimeType, preference == DecoderPreference.SOFTWARE)
                if (candidate != null) {
                    return AndroidMediaCodecDriver(MediaCodec.createByCodecName(candidate), candidate)
                }
            }
            val codec = MediaCodec.createDecoderByType(mimeType)
            return AndroidMediaCodecDriver(codec, codec.name)
        }

        private fun findCodec(mimeType: String, wantSoftware: Boolean): String? {
            return try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in list.codecInfos) {
                    if (info.isEncoder) continue
                    val types = info.supportedTypes ?: continue
                    if (types.any { it.equals(mimeType, ignoreCase = true) }) {
                        val isSoft = info.name.startsWith("OMX.google.", ignoreCase = true) ||
                                     info.name.startsWith("c2.android.", ignoreCase = true)
                        if (wantSoftware == isSoft) {
                            return info.name
                        }
                    }
                }
                null
            } catch (_: Throwable) {
                null
            }
        }
    }
}
