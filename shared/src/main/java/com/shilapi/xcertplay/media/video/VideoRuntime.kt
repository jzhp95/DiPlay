package com.shilapi.xcertplay.media.video

/**
 * 视频运行时模块顶层接口。
 */
interface VideoRuntime {
    fun attachSession(identity: SessionVideoId)
    fun onConfig(config: VideoStreamConfig)
    fun tryOfferAccessUnit(unit: EncodedAccessUnit): OfferResult
    fun attachOutput(target: OutputTarget)
    fun detachOutput(surfaceGeneration: Long): ReleaseTicket
    fun setRuntimePolicy(policy: VideoRuntimePolicy)
    fun stop(reason: String): ReleaseTicket
    fun snapshot(): VideoRuntimeSnapshot
}

/**
 * 后端工厂接口。
 */
fun interface BackendFactory {
    fun createBackend(kind: BackendKind, identity: RuntimeIdentity): VideoBackend
}

/**
 * 运行时可注入依赖。
 */
data class RuntimeDependencies(
    val clock: MonotonicClock,
    val backendFactory: BackendFactory,
    val recoveryRequest: ((String) -> Unit)? = null,
    val metricsSink: ((VideoRuntimeSnapshot) -> Unit)? = null
)
