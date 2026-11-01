package com.shilapi.xcertplay.media.video

/**
 * 后端能力描述。
 */
data class BackendCapabilities(
    val kind: BackendKind,
    val supportedCodecs: Set<CodecType>,
    val supportsCpuSampling: Boolean,
    val supportsDirectSurface: Boolean
)

/**
 * 后端配置结果。
 */
sealed interface ConfigureResult {
    data class Success(val instanceId: String, val actualCapabilities: BackendCapabilities) : ConfigureResult
    data class Rejected(val reason: String) : ConfigureResult
    data class Failed(val cause: Throwable) : ConfigureResult
}

/**
 * 后端轮询输出结果。
 */
sealed interface BackendOutput {
    data class Frame(val lease: DecodedFrameLease) : BackendOutput
    data class FormatChanged(val description: DecoderOutputDescription) : BackendOutput
    object NoOutput : BackendOutput
    data class Failure(val cause: Throwable) : BackendOutput
}

/**
 * 视频解码后端抽象接口（由硬解直出、硬解 YUV、原生软解等具体实现）。
 */
interface VideoBackend {
    fun capabilities(): BackendCapabilities
    fun configure(config: VideoStreamConfig, target: OutputTarget, options: BackendOptions): ConfigureResult
    fun trySubmit(unit: EncodedAccessUnit): OfferResult
    fun pollOutput(deadlineNs: Long): BackendOutput
    fun requestStop()
    fun awaitReleased(deadlineNs: Long): ReleaseState
}
