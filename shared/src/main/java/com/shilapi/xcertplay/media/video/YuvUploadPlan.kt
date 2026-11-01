package com.shilapi.xcertplay.media.video

import java.nio.ByteBuffer

/**
 * GPU 纹理上传模式。
 */
enum class UploadTextureMode {
    /** 标准三平面（I420 / YV12），使用 GL_LUMINANCE 分别上传 Y、U、V */
    THREE_PLANES,
    /** 双平面 NV12，使用 GL_LUMINANCE 上传 Y，GL_LUMINANCE_ALPHA 上传 UV */
    TWO_PLANES_NV12,
    /** 双平面 NV21，使用 GL_LUMINANCE 上传 Y，GL_LUMINANCE_ALPHA 上传 VU */
    TWO_PLANES_NV21
}

/**
 * YUV 像素平面上传到 GLES2 纹理的执行计划。
 * 明确记录是否有内存拷贝、传输字节数以及直接缓冲区引用。
 */
data class UploadPlan(
    val frameKey: FrameKey,
    val mode: UploadTextureMode,
    val width: Int,
    val height: Int,
    val yBuffer: ByteBuffer,
    val uBuffer: ByteBuffer?,
    val vBuffer: ByteBuffer?,
    val uvBuffer: ByteBuffer? = null,
    val isCopied: Boolean,
    val bytesCount: Long
)
