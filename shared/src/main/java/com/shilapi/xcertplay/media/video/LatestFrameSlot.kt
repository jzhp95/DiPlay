package com.shilapi.xcertplay.media.video

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 独立最新帧调度槽 (LatestFrameSlot)。
 * 严格实施单槽待渲染缓冲 (1 pending) 与单槽进行中渲染保护 (1 in-flight)。
 * 锁内只移交所有权，所有底层的 lease.releaseOnce() 均在锁外执行，杜绝死锁。
 */
class LatestFrameSlot : AutoCloseable {

    private val lock = ReentrantLock()
    private var pendingFrame: DecodedFrameLease? = null
    private var inFlightFrame: DecodedFrameLease? = null
    private val isClosed = AtomicBoolean(false)

    /**
     * 发布新解码帧。
     * 若已有待渲染帧 (pending)，则新帧替换旧帧，被替换的旧帧立即在锁外归还。
     * 若已关闭，则返回 false，调用方必须自行保留并归还传入的租约。
     */
    fun publish(frame: DecodedFrameLease): Boolean {
        var toRelease: DecodedFrameLease? = null

        lock.withLock {
            if (isClosed.get()) {
                return false
            }

            // 替换 pending 槽，移出旧租约在锁外释放
            toRelease = pendingFrame
            pendingFrame = frame
        }

        toRelease?.let {
            try {
                it.releaseOnce()
            } catch (_: Throwable) {
                // 异常向调用方或恢复链路记录，不阻断发布
            }
        }
        return true
    }

    /**
     * 渲染线程提取待绘制帧。
     * 若当前已有帧在绘制中 (in-flight != null)，则返回 null，不交出下一帧；
     * 若有待绘制帧且当前无进行中帧，则将 pendingFrame 转为 inFlightFrame 并返回。
     */
    fun take(): DecodedFrameLease? {
        lock.withLock {
            if (isClosed.get() || inFlightFrame != null) {
                return null
            }
            val frame = pendingFrame
            pendingFrame = null
            inFlightFrame = frame
            return frame
        }
    }

    /**
     * 渲染线程完成一帧的呈现生命周期。
     * 在 finally 块中调用，清理 in-flight 标记，并在锁外保证尚未归还的租约尝试释放一次。
     */
    fun finish(frame: DecodedFrameLease) {
        var toRelease: DecodedFrameLease? = null

        lock.withLock {
            if (inFlightFrame === frame) {
                inFlightFrame = null
                toRelease = frame
            } else {
                toRelease = frame
            }
        }

        toRelease?.let {
            try {
                it.releaseOnce()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 关闭槽。
     * 拒绝后续 publish，清空并归还待绘制帧；进行中帧由其 finish 调用自行归还。
     */
    override fun close() {
        var toRelease: DecodedFrameLease? = null

        lock.withLock {
            if (isClosed.compareAndSet(false, true)) {
                toRelease = pendingFrame
                pendingFrame = null
            }
        }

        toRelease?.let {
            try {
                it.releaseOnce()
            } catch (_: Throwable) {}
        }
    }
}
