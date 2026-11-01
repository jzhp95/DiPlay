package com.shilapi.xcertplay.media.video

import java.util.concurrent.atomic.AtomicLong

/**
 * 视频运行时性能与生命周期指标收集器 (VideoRuntimeMetrics)。
 * 严格基于本机单调时间，按段统计接收、送入、解码、呈现与丢帧，
 * 输出不可变 RuntimeSnapshot 供状态观察与诊断使用。
 */
class VideoRuntimeMetrics(
    val sessionId: String,
    val streamRole: Int = 110,
    private val timeline: VideoFrameTimeline = VideoFrameTimeline(120),
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() }
) {
    private val receivedCount = AtomicLong(0L)
    private val submittedCount = AtomicLong(0L)
    private val decodedCount = AtomicLong(0L)
    private val renderedCount = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)

    fun onFrameReceived(key: FrameKey) {
        val now = clock.nowNs()
        receivedCount.incrementAndGet()
        timeline.record(FrameTimingRecord(key, now))
    }

    fun onFrameSubmitted(key: FrameKey) {
        submittedCount.incrementAndGet()
    }

    fun onFrameDecoded(key: FrameKey) {
        decodedCount.incrementAndGet()
    }

    fun onFrameRendered(key: FrameKey) {
        renderedCount.incrementAndGet()
    }

    fun onFrameDropped(key: FrameKey, reason: String) {
        val now = clock.nowNs()
        droppedCount.incrementAndGet()
        timeline.record(FrameTimingRecord(key, now, isDropped = true, dropReason = reason))
    }

    fun snapshot(
        requestedImpl: VideoImplementation = VideoImplementation.LEGACY,
        actualImpl: VideoImplementation = VideoImplementation.INDEPENDENT,
        actualBackend: BackendKind? = null
    ): RuntimeSnapshot {
        return RuntimeSnapshot(
            sessionId = sessionId,
            streamRole = streamRole,
            requestedImplementation = requestedImpl,
            actualImplementation = actualImpl,
            actualBackend = actualBackend,
            appOwnedBytes = 0L,
            inputQueueDepth = (receivedCount.get() - submittedCount.get()).toInt().coerceAtLeast(0),
            activeLeaseCount = (decodedCount.get() - renderedCount.get() - droppedCount.get()).toInt().coerceAtLeast(0)
        )
    }
}
