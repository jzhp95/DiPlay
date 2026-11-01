package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.video.VideoImplementation
import java.util.concurrent.atomic.AtomicLong

/**
 * 管线切换状态阶段。
 */
enum class SwitchPhase {
    ACCEPTED,
    PENDING_RECONNECT,
    RECONNECTING,
    BLOCKED,
    REJECTED
}

/**
 * 管线切换操作回执。
 */
data class SwitchReceipt(
    val accepted: Boolean,
    val phase: SwitchPhase,
    val reason: String,
    val requestId: Long,
    val newRevision: Long
)

/**
 * 正式管线切换事务控制器 (VideoImplementationSwitch)。
 * 驱动设置持久化并在活跃会话存在时触发受控的会话重连与重新配置事务。
 */
class VideoImplementationSwitch(
    private val preferences: VideoImplementationPreferences,
    private val onReconfigureSession: ((VideoImplementation) -> Boolean)? = null
) {
    private val requestCounter = AtomicLong(0L)

    fun request(selection: VideoImplementation, currentRevision: Long): SwitchReceipt {
        val reqId = requestCounter.incrementAndGet()
        val saveRes = preferences.save(selection, currentRevision)

        return when (saveRes) {
            is SaveResult.Conflict -> {
                SwitchReceipt(
                    accepted = false,
                    phase = SwitchPhase.REJECTED,
                    reason = "REVISION_CONFLICT: current is ${saveRes.currentRevision}",
                    requestId = reqId,
                    newRevision = saveRes.currentRevision
                )
            }
            is SaveResult.Error -> {
                SwitchReceipt(
                    accepted = false,
                    phase = SwitchPhase.REJECTED,
                    reason = saveRes.reason,
                    requestId = reqId,
                    newRevision = currentRevision
                )
            }
            is SaveResult.Success -> {
                val reconfigured = onReconfigureSession?.invoke(selection) ?: false
                val phase = if (reconfigured) SwitchPhase.RECONNECTING else SwitchPhase.PENDING_RECONNECT
                SwitchReceipt(
                    accepted = true,
                    phase = phase,
                    reason = "SAVED_SUCCESSFULLY",
                    requestId = reqId,
                    newRevision = saveRes.newRevision
                )
            }
        }
    }
}
