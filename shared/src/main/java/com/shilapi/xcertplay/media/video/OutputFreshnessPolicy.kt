package com.shilapi.xcertplay.media.video

/**
 * 待呈现候选帧描述。
 */
data class FrameCandidate(
    val frame: DecodedFrameLease,
    val arrivalMonoNs: Long,
    val isResumingFrame: Boolean = false,
    val isFirstFrame: Boolean = false
)

/**
 * 呈现决策上下文。
 */
data class PresentationContext(
    val currentMonoNs: Long,
    val hasPendingBacklog: Boolean = false,
    val hasNewerReadyCandidate: Boolean = false,
    val isDiagnosticHold: Boolean = false,
    val maxAllowedAgeMs: Long = 100L,
    val pacingDelayMs: Long = 0L
)

/**
 * 呈现决策结果。
 */
sealed interface PresentationDecision {
    data class Display(val targetTimeNs: Long = 0L) : PresentationDecision
    data class DropStale(val reason: String, val ageMs: Long) : PresentationDecision
    data class Hold(val reason: String) : PresentationDecision
    data class RejectGeneration(val reason: String) : PresentationDecision
}

/**
 * 独立呈现新鲜度与丢帧决策策略 (OutputFreshnessPolicy)。
 * 严禁依赖旧版 VideoOutputFreshness。
 * 保护首帧、静止唤醒帧和唯一有效画面，避免在无更新画面时无限丢弃超龄帧。
 */
object OutputFreshnessPolicy {

    fun decide(candidate: FrameCandidate, context: PresentationContext): PresentationDecision {
        // 1. 诊断定格显式处理
        if (context.isDiagnosticHold) {
            return PresentationDecision.Hold("DIAGNOSTIC_HOLD_ACTIVE")
        }

        // 2. 保护首次有效画面与静止恢复首帧，坚决推进显示
        if (candidate.isFirstFrame) {
            return PresentationDecision.Display(calculateTargetTime(candidate, context))
        }
        if (candidate.isResumingFrame) {
            return PresentationDecision.Display(calculateTargetTime(candidate, context))
        }

        // 3. 计算以本机单调时间为基准的实际候选帧年龄
        val ageNs = Math.max(0L, context.currentMonoNs - candidate.arrivalMonoNs)
        val ageMs = ageNs / 1_000_000L

        // 4. 只有当存在更新的安全就绪候选帧且当前帧超龄时，才执行丢弃；
        // 若当前帧是唯一可用画面，即便超龄仍必须推进显示，防止关键帧饥饿或黑屏
        if (context.hasNewerReadyCandidate && ageMs > context.maxAllowedAgeMs) {
            return PresentationDecision.DropStale(
                reason = "STALE_FRAME_WITH_NEWER_READY_CANDIDATE",
                ageMs = ageMs
            )
        }

        return PresentationDecision.Display(calculateTargetTime(candidate, context))
    }

    private fun calculateTargetTime(candidate: FrameCandidate, context: PresentationContext): Long {
        return if (context.pacingDelayMs > 0) {
            candidate.arrivalMonoNs + context.pacingDelayMs * 1_000_000L
        } else {
            0L
        }
    }
}
