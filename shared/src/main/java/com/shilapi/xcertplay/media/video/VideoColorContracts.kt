package com.shilapi.xcertplay.media.video

/**
 * 颜色范围。
 */
enum class ColorRange {
    UNKNOWN,
    FULL,
    LIMITED
}

/**
 * 色彩矩阵标准。
 */
enum class ColorMatrix {
    UNKNOWN,
    BT601,
    BT709,
    BT2020
}

/**
 * 色彩原色。
 */
enum class ColorPrimaries {
    UNKNOWN,
    BT709,
    BT601_625,
    BT601_525,
    BT2020
}

/**
 * 传递特性。
 */
enum class ColorTransfer {
    UNKNOWN,
    SRGB,
    BT709,
    LINEAR,
    GAMMA22,
    GAMMA28
}

/**
 * 置信度。
 */
enum class Confidence {
    UNVERIFIED,
    LOW,
    MEDIUM,
    HIGH,
    VERIFIED
}

/**
 * 1. 源端协议色彩声明（解析自 SPS VUI 或其他会话协商）。
 */
data class SourceColorDeclaration(
    val range: ColorRange = ColorRange.UNKNOWN,
    val matrix: ColorMatrix = ColorMatrix.UNKNOWN,
    val primaries: ColorPrimaries = ColorPrimaries.UNKNOWN,
    val transfer: ColorTransfer = ColorTransfer.UNKNOWN,
    val bitDepth: Int = 8,
    val chromaLocation: Int = 0,
    val origin: String = "SPS",
    val parseStatus: String = "SUCCESS"
)

/**
 * 2. 解码器输出实际描述（来自 MediaCodec MediaFormat 或原生解码器输出上下文）。
 */
data class DecoderOutputDescription(
    val reportedRange: ColorRange = ColorRange.UNKNOWN,
    val reportedStandard: ColorMatrix = ColorMatrix.UNKNOWN,
    val reportedTransfer: ColorTransfer = ColorTransfer.UNKNOWN,
    val planeLayout: String = "UNKNOWN",
    val cropRect: String = "",
    val bitDepth: Int = 8,
    val actualComponent: String = "UNKNOWN",
    val reportAvailability: Boolean = false
)

/**
 * 3. 决议后的像素契约（用于 Shader 上传与矩阵色彩转换）。
 */
data class ResolvedPixelContract(
    val effectiveRange: ColorRange = ColorRange.LIMITED,
    val effectiveMatrix: ColorMatrix = ColorMatrix.BT601,
    val effectiveTransfer: ColorTransfer = ColorTransfer.SRGB,
    val effectivePrimaries: ColorPrimaries = ColorPrimaries.BT709,
    val evidenceBasis: String = "DEFAULT_BASELINE",
    val confidence: Confidence = Confidence.UNVERIFIED,
    val applicabilityKey: String = "",
    val conflictReasons: List<String> = emptyList()
)

/**
 * 4. 渲染目标色彩空间描述。
 */
data class RenderTargetDescription(
    val pixelFormat: String = "RGBA_8888",
    val eglConfig: String = "EGL_CONFIG_RGBA8888",
    val requestedColorSpace: String = "SRGB",
    val actualAvailability: Boolean = true,
    val windowOrSurfaceDescription: String = "SurfaceView"
)
