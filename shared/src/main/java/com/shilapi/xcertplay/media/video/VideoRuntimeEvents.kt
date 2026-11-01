package com.shilapi.xcertplay.media.video

/**
 * 视频管线核心生命周期与数据平面事件。
 * 所有事件严格携带原始代际与组件身份 RuntimeIdentity，便于追踪与调试。
 */
sealed interface RuntimeEvent {
    val identity: RuntimeIdentity

    data class ConfigChanged(
        override val identity: RuntimeIdentity,
        val config: VideoStreamConfig
    ) : RuntimeEvent

    data class FrameReady(
        override val identity: RuntimeIdentity,
        val frameKey: FrameKey
    ) : RuntimeEvent

    data class OutputChanged(
        override val identity: RuntimeIdentity,
        val target: OutputTarget
    ) : RuntimeEvent

    data class Failure(
        override val identity: RuntimeIdentity,
        val cause: Throwable,
        val canRecover: Boolean
    ) : RuntimeEvent

    data class StopRequested(
        override val identity: RuntimeIdentity,
        val reason: String
    ) : RuntimeEvent

    data class Released(
        override val identity: RuntimeIdentity,
        val finalState: ReleaseState
    ) : RuntimeEvent
}
