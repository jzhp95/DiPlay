package com.shilapi.xcertplay.media.video

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 视频输入流活动状态观察器。
 * 针对 CarPlay 地图等静止画面进行独立建模，区分正常空闲与真实卡顿积压：
 * - 3 秒无新输入且应用／codec 均无待处理工作后进入 QUIESCENT；
 * - 唤醒首帧进入 RESUMING，按单帧自身单调时间计龄，不把静止间隔算作积压延迟；
 * - 唤醒后重新收集 300ms 趋势后转入 ACTIVE；
 * - 500ms 硬年龄保护与容量保护在唤醒期间依然严格生效。
 */
class VideoInputActivity(
    val quiescentThresholdNs: Long = 3_000_000_000L, // 3 seconds
    val resumingTrendWindowNs: Long = 300_000_000L,   // 300 ms
    val hardAgeLimitNs: Long = 500_000_000L           // 500 ms
) {
    private val state = AtomicReference(StreamActivity.ACTIVE)
    private val lastInputArrivalMonoNs = AtomicLong(0L)
    private val idleDurationMs = AtomicLong(0L)
    private val resumeEpisodeId = AtomicLong(0L)
    private val resumeFirstFrameMonoNs = AtomicLong(0L)
    private val trendStartMonoNs = AtomicLong(0L)

    fun currentState(): StreamActivity = state.get()
    fun lastInputMonoNs(): Long = lastInputArrivalMonoNs.get()
    fun idleDurationMs(): Long = idleDurationMs.get()
    fun currentResumeEpisodeId(): Long = resumeEpisodeId.get()

    /**
     * 周期性或在输入到达时观察流活动状态。
     * @param nowNs 当前单调时钟时间
     * @param hasNewInput 本次调用是否有新访问单元到达
     * @param hasPendingWork 解码器或应用队列中是否仍有未完成的待处理工作
     */
    fun observe(nowNs: Long, hasNewInput: Boolean, hasPendingWork: Boolean): StreamActivity {
        if (hasNewInput) {
            val previousArrival = lastInputArrivalMonoNs.getAndSet(nowNs)
            when (state.get()) {
                StreamActivity.QUIESCENT -> {
                    // 从静止恢复，进入 RESUMING 状态
                    val idleMs = if (previousArrival > 0L) (nowNs - previousArrival) / 1_000_000L else 0L
                    idleDurationMs.set(idleMs)
                    resumeEpisodeId.incrementAndGet()
                    resumeFirstFrameMonoNs.set(nowNs)
                    trendStartMonoNs.set(nowNs)
                    state.set(StreamActivity.RESUMING)
                }
                StreamActivity.RESUMING -> {
                    val trendStart = trendStartMonoNs.get()
                    if (trendStart > 0L && nowNs - trendStart >= resumingTrendWindowNs) {
                        state.set(StreamActivity.ACTIVE)
                    }
                }
                StreamActivity.ACTIVE -> {
                    // 保持 ACTIVE
                }
            }
        } else {
            val lastArrival = lastInputArrivalMonoNs.get()
            // 只有无新输入、已过静止阈值、且应用及 codec 均无积压工作时，才允许进入 QUIESCENT
            if (lastArrival > 0L && (nowNs - lastArrival >= quiescentThresholdNs) && !hasPendingWork) {
                if (state.get() != StreamActivity.QUIESCENT) {
                    idleDurationMs.set((nowNs - lastArrival) / 1_000_000L)
                    state.set(StreamActivity.QUIESCENT)
                }
            }
        }
        return state.get()
    }

    /**
     * 判断是否处于真实过载状态。
     * 遵守规范：
     * - 最老待解码帧年龄 >= 500ms（hardAgeLimitNs）即判过载；
     * - 队列深度达到容量硬上限即判过载；
     * - 在 RESUMING 期间，若有合法输入且在单帧硬年龄内，不因静止时长误判过载。
     */
    fun isOverloaded(
        nowNs: Long,
        oldestFrameArrivalNs: Long?,
        currentDepth: Int,
        maxFrames: Int
    ): Boolean {
        if (currentDepth >= maxFrames) {
            return true
        }
        if (oldestFrameArrivalNs != null) {
            val frameAgeNs = nowNs - oldestFrameArrivalNs
            if (frameAgeNs >= hardAgeLimitNs) {
                return true
            }
        }
        return false
    }

    fun reset() {
        state.set(StreamActivity.ACTIVE)
        lastInputArrivalMonoNs.set(0L)
        idleDurationMs.set(0L)
        resumeFirstFrameMonoNs.set(0L)
        trendStartMonoNs.set(0L)
    }
}
