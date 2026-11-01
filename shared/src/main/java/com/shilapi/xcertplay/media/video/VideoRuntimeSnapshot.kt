package com.shilapi.xcertplay.media.video

/**
 * 视频管线不可变运行时快照，用于外部诊断、状态观察与审计。
 */
data class VideoRuntimeSnapshot(
    val sessionId: String,
    val streamRole: Int = 110,
    val requestedImplementation: VideoImplementation = VideoImplementation.LEGACY,
    val actualImplementation: VideoImplementation = VideoImplementation.INDEPENDENT,
    val actualBackend: BackendKind? = null,
    val activityState: StreamActivity = StreamActivity.ACTIVE,
    val releaseState: ReleaseState = ReleaseState.PENDING,
    val configGeneration: Long = 0L,
    val decoderGeneration: Long = 0L,
    val surfaceGeneration: Long = 0L,
    val fallbackReason: String? = null,
    val lastError: String? = null,
    val lastInputArrivalMonoNs: Long? = null,
    val idleDurationMs: Long = 0L,
    val resumeEpisodeId: Long = 0L,
    val appOwnedBytes: Long = 0L,
    val inputQueueDepth: Int = 0,
    val activeLeaseCount: Int = 0
)

typealias RuntimeSnapshot = VideoRuntimeSnapshot
