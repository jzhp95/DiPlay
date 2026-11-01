package com.shilapi.xcertplay.media.video

import java.util.concurrent.atomic.AtomicLong

/**
 * 视频呈现调度器 (VideoPresentationScheduler)。
 * 驱动呈现决策执行并维护显示、丢弃、定格统计指标。
 */
class VideoPresentationScheduler(
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() }
) {
    private val displayedFrames = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    private val heldFrames = AtomicLong(0L)

    val displayedCount: Long get() = displayedFrames.get()
    val droppedCount: Long get() = droppedFrames.get()
    val heldCount: Long get() = heldFrames.get()

    fun evaluate(
        candidate: FrameCandidate,
        hasPendingBacklog: Boolean = false,
        hasNewerReadyCandidate: Boolean = false,
        isDiagnosticHold: Boolean = false,
        maxAllowedAgeMs: Long = 100L,
        pacingDelayMs: Long = 0L
    ): PresentationDecision {
        val now = clock.nowNs()
        val context = PresentationContext(
            currentMonoNs = now,
            hasPendingBacklog = hasPendingBacklog,
            hasNewerReadyCandidate = hasNewerReadyCandidate,
            isDiagnosticHold = isDiagnosticHold,
            maxAllowedAgeMs = maxAllowedAgeMs,
            pacingDelayMs = pacingDelayMs
        )

        val decision = OutputFreshnessPolicy.decide(candidate, context)
        when (decision) {
            is PresentationDecision.Display -> displayedFrames.incrementAndGet()
            is PresentationDecision.DropStale -> droppedFrames.incrementAndGet()
            is PresentationDecision.Hold -> heldFrames.incrementAndGet()
            is PresentationDecision.RejectGeneration -> {}
        }
        return decision
    }

    fun reset() {
        displayedFrames.set(0L)
        droppedFrames.set(0L)
        heldFrames.set(0L)
    }
}
