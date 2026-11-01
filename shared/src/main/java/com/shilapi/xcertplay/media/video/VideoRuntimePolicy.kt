package com.shilapi.xcertplay.media.video

/**
 * 色彩处理策略偏好。
 */
enum class ColorPolicy {
    /** 遵循经过核验的像素契约（默认推荐基线） */
    FOLLOW_CONTRACT,
    /** 受控对照组：强制扩展为有限范围 Limited (16~235) */
    FORCE_LIMITED,
    /** 强制保持全范围 Full (0~255) */
    FORCE_FULL,
    /** 原生直通，不做额外范围映射 */
    NATIVE_PASSTHROUGH
}

/**
 * 视频运行模块策略配置。
 * 严禁复用或包装旧版 VideoPipelinePlan。
 */
data class VideoRuntimePolicy(
    val requestedImplementation: VideoImplementation = VideoImplementation.LEGACY,
    val decoderPreference: DecoderPreference = DecoderPreference.AUTO,
    val preferredBackend: BackendKind? = null,
    val colorPolicy: ColorPolicy = ColorPolicy.FOLLOW_CONTRACT,
    val pacingDelayMs: Int = 0,
    val maxBufferBudgetBytes: Long = 64L * 1024 * 1024,
    val targetFps: Int = 0
)

/**
 * 传递给具体解码后端的配置选项。
 */
data class BackendOptions(
    val allowHardware: Boolean = true,
    val lowLatencyMode: Boolean = true,
    val eglSwapIntervalZero: Boolean = true,
    val maxQueuedFrames: Int = 8,
    val decoderPreference: DecoderPreference = DecoderPreference.AUTO
)
