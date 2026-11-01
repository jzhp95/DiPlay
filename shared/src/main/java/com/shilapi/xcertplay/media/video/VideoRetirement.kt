package com.shilapi.xcertplay.media.video

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频资源安全退役与释放屏障协调器 (VideoRetirement)。
 * 保证异步解码器、渲染循环和缓冲池真正确认释放，
 * 若无法在超时内确认释放，则进入 BLOCKED_RELEASE，防止资源泄漏或重叠竞争。
 */
class VideoRetirement(
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() }
) {
    private val isRetiring = AtomicBoolean(false)

    fun begin(
        reason: String,
        deadlineNs: Long,
        onStop: () -> Unit,
        checkReleased: () -> Boolean
    ): ReleaseTicket {
        val ticket = SettableReleaseTicket(ReleaseState.PENDING)
        isRetiring.set(true)

        try {
            onStop()
        } catch (_: Throwable) {}

        if (checkReleased()) {
            ticket.complete(ReleaseState.RELEASED)
            return ticket
        }

        // 有界等待资源完成释放
        while (clock.nowNs() < deadlineNs) {
            if (checkReleased()) {
                ticket.complete(ReleaseState.RELEASED)
                return ticket
            }
            Thread.yield()
        }

        // 超时仍未释放：标记为 BLOCKED
        if (checkReleased()) {
            ticket.complete(ReleaseState.RELEASED)
        } else {
            ticket.complete(ReleaseState.BLOCKED)
        }
        return ticket
    }
}
