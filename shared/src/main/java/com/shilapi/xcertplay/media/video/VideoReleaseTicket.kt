package com.shilapi.xcertplay.media.video

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 可配置与完成的 ReleaseTicket 实现。
 * 遵循规范：未真正退役前保持 BLOCKED 或 PENDING，绝不通过计时器伪造 RELEASED。
 */
class SettableReleaseTicket(
    initialState: ReleaseState = ReleaseState.PENDING
) : ReleaseTicket {

    private val currentState = AtomicReference(initialState)
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val listeners = CopyOnWriteArrayList<(ReleaseState) -> Unit>()

    override fun state(): ReleaseState = currentState.get()

    /**
     * 标记释放完成，状态迁移到 RELEASED 或 BLOCKED。
     * 触发已注册监听器并在分发后自动解绑，防止持有外部引用。
     */
    fun complete(finalState: ReleaseState) {
        require(finalState != ReleaseState.PENDING) { "Final state cannot be PENDING" }

        val changed = lock.withLock {
            if (currentState.compareAndSet(ReleaseState.PENDING, finalState)) {
                condition.signalAll()
                true
            } else {
                false
            }
        }

        if (changed) {
            val callbacks = ArrayList(listeners)
            listeners.clear()
            for (cb in callbacks) {
                try {
                    cb(finalState)
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun awaitUntil(deadlineNs: Long): ReleaseState {
        val stateNow = currentState.get()
        if (stateNow != ReleaseState.PENDING) {
            return stateNow
        }

        lock.withLock {
            while (currentState.get() == ReleaseState.PENDING) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) {
                    // 超时到达仍未释放，返回当前实际状态（PENDING 或 BLOCKED），不伪造 RELEASED
                    return currentState.get()
                }
                condition.await(remainingNs, TimeUnit.NANOSECONDS)
            }
            return currentState.get()
        }
    }

    override fun onComplete(listener: (ReleaseState) -> Unit): AutoCloseable {
        val stateNow = currentState.get()
        if (stateNow != ReleaseState.PENDING) {
            // 已完成，立即同步分发
            try {
                listener(stateNow)
            } catch (_: Throwable) {
            }
            return AutoCloseable {}
        }

        val unsubscribed = AtomicBoolean(false)
        listeners.add(listener)

        // 再次检查防止在添加过程中已 complete
        val stateAfter = currentState.get()
        if (stateAfter != ReleaseState.PENDING) {
            if (listeners.remove(listener)) {
                try {
                    listener(stateAfter)
                } catch (_: Throwable) {
                }
            }
        }

        return AutoCloseable {
            if (unsubscribed.compareAndSet(false, true)) {
                listeners.remove(listener)
            }
        }
    }

    companion object {
        fun completed(state: ReleaseState): ReleaseTicket {
            require(state != ReleaseState.PENDING)
            return SettableReleaseTicket(state)
        }
    }
}
