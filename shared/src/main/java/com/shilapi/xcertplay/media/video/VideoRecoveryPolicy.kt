package com.shilapi.xcertplay.media.video

/**
 * 分级恢复动作。
 */
sealed interface RecoveryAction {
    /** R0: 丢弃当前过期帧，推进下一帧 */
    data class R0_DropStale(val reason: String) : RecoveryAction

    /** R1: 参考链断裂，请求上游关键帧 (IDR/IRAP) */
    data class R1_RequestKeyframe(val reason: String) : RecoveryAction

    /** R2: 解码器故障或卡死，重置并重建 Codec */
    data class R2_RecreateCodec(val reason: String) : RecoveryAction

    /** R3: 传输协议异常，重置会话媒体流 */
    data class R3_RestartSession(val reason: String) : RecoveryAction

    /** R4: 资源释放未确认，阻塞会话启动 (BLOCKED_RELEASE) */
    data class R4_BlockRelease(val reason: String) : RecoveryAction
}

/**
 * 分级故障恢复策略 (VideoRecoveryPolicy)。
 * 严禁每个小故障都触发重建 Codec 或关键帧风暴。
 */
object VideoRecoveryPolicy {

    fun decideRecovery(
        failureKind: String,
        isReferenceChainBroken: Boolean = false,
        isCodecUnresponsive: Boolean = false,
        isReleaseBlocked: Boolean = false
    ): RecoveryAction {
        return when {
            isReleaseBlocked -> RecoveryAction.R4_BlockRelease("RELEASE_UNCONFIRMED: $failureKind")
            isCodecUnresponsive -> RecoveryAction.R2_RecreateCodec("CODEC_FAILURE: $failureKind")
            isReferenceChainBroken -> RecoveryAction.R1_RequestKeyframe("REFERENCE_CHAIN_BROKEN: $failureKind")
            else -> RecoveryAction.R0_DropStale("DROP_TRANSIENT_FAILURE: $failureKind")
        }
    }
}
