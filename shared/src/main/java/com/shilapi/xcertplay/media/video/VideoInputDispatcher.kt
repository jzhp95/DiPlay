package com.shilapi.xcertplay.media.video

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 触控命令封装。
 */
data class TouchCommand(
    val result: TouchMapResult,
    val timestampNs: Long
)

/**
 * 触控接纳结果。
 */
enum class InputOfferResult {
    ACCEPTED,
    DROPPED_COALESCED,
    REJECTED_QUEUE_FULL,
    REJECTED_SESSION_CLOSED
}

/**
 * 独立触控事件分发器 (VideoInputDispatcher)。
 * 维护有界输入队列，对连续 MOVE 事件进行安全合并，独立于视频渲染线程异步派发。
 */
class VideoInputDispatcher(
    private val maxCapacity: Int = 32,
    private val eventWriter: (TouchCommand) -> Unit = {}
) : AutoCloseable {

    private val lock = ReentrantLock()
    private val queue = ArrayDeque<TouchCommand>()
    private val isClosed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "video-touch-dispatcher").apply { isDaemon = true }
    }

    fun offer(command: TouchCommand): InputOfferResult {
        if (isClosed.get()) {
            return InputOfferResult.REJECTED_SESSION_CLOSED
        }

        var shouldDrain = false

        lock.withLock {
            if (isClosed.get()) {
                return InputOfferResult.REJECTED_SESSION_CLOSED
            }

            // 合并连续同指集合的 MOVE 事件
            if (command.result.action == TouchAction.MOVE && queue.isNotEmpty()) {
                val last = queue.peekLast()
                if (last != null && last.result.action == TouchAction.MOVE && last.result.mappedPointers.size == command.result.mappedPointers.size) {
                    val samePointers = last.result.mappedPointers.indices.all { i ->
                        last.result.mappedPointers[i].id == command.result.mappedPointers[i].id
                    }
                    if (samePointers) {
                        // 替换为最新的 MOVE 坐标
                        queue.removeLast()
                        queue.addLast(command)
                        return InputOfferResult.DROPPED_COALESCED
                    }
                }
            }

            if (queue.size >= maxCapacity) {
                return InputOfferResult.REJECTED_QUEUE_FULL
            }

            queue.addLast(command)
            shouldDrain = true
        }

        if (shouldDrain) {
            executor.execute { drain() }
        }
        return InputOfferResult.ACCEPTED
    }

    private fun drain() {
        while (true) {
            val cmd = lock.withLock {
                if (queue.isEmpty() || isClosed.get()) null else queue.pollFirst()
            } ?: break

            try {
                eventWriter(cmd)
            } catch (_: Throwable) {}
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            lock.withLock {
                queue.clear()
            }
            executor.shutdownNow()
        }
    }
}
