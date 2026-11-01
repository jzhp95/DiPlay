package com.shilapi.xcertplay.media.video

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 访问单元队列不可变快照。
 */
data class EncodedQueueSnapshot(
    val depth: Int,
    val bytes: Long,
    val oldestArrivalNs: Long?,
    val configGeneration: Long,
    val overflowReason: String? = null
)

/**
 * 独立有界压缩帧队列。
 * 遵循 Spec §10 硬约束：最多 8 个访问单元且不超过 8 MiB，保护参考链不被随意破坏。
 */
class EncodedQueue(
    val maxFrames: Int = 8,
    val maxBytes: Long = 8L * 1024 * 1024,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() }
) {
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<EncodedAccessUnit>()
    private val currentBytes = AtomicLong(0L)
    private val currentConfigGeneration = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private var lastOverflowReason: String? = null

    /**
     * 尝试将访问单元入队。
     * - 已关闭或配置代际过旧：REJECTED；
     * - 单帧超出队列物理上限：REJECTED；
     * - 达到帧数或字节上限：BACKPRESSURE（调用方负责归还租约）；
     * - 成功入队：ACCEPTED（转移租约所有权）。
     */
    fun offer(unit: EncodedAccessUnit): OfferResult {
        if (closed.get()) {
            return OfferResult.REJECTED
        }

        val unitBytes = unit.lease.sizeBytes.toLong()
        if (unitBytes > maxBytes) {
            lastOverflowReason = "frame_size_exceeds_max_bytes (${unitBytes} > $maxBytes)"
            return OfferResult.REJECTED
        }

        lock.withLock {
            if (closed.get()) return OfferResult.REJECTED

            val activeGen = currentConfigGeneration.get()
            if (unit.configGeneration < activeGen) {
                lastOverflowReason = "outdated_config_generation (${unit.configGeneration} < $activeGen)"
                return OfferResult.REJECTED
            }

            if (queue.size >= maxFrames) {
                lastOverflowReason = "max_frames_reached (${queue.size} >= $maxFrames)"
                return OfferResult.BACKPRESSURE
            }

            if (currentBytes.get() + unitBytes > maxBytes) {
                lastOverflowReason = "max_bytes_reached (${currentBytes.get() + unitBytes} > $maxBytes)"
                return OfferResult.BACKPRESSURE
            }

            queue.addLast(unit)
            currentBytes.addAndGet(unitBytes)
            lastOverflowReason = null
            return OfferResult.ACCEPTED
        }
    }

    /**
     * 取出队首访问单元。
     */
    fun poll(): EncodedAccessUnit? {
        lock.withLock {
            val unit = queue.pollFirst() ?: return null
            currentBytes.addAndGet(-unit.lease.sizeBytes.toLong())
            return unit
        }
    }

    /**
     * 遭遇新配置代际屏障时，原子清空当前旧代际队列并返回所有被丢弃单元，供调用方安全释放租约。
     */
    fun resetForConfig(newConfigGeneration: Long): List<EncodedAccessUnit> {
        lock.withLock {
            currentConfigGeneration.set(newConfigGeneration)
            val discarded = ArrayList(queue)
            queue.clear()
            currentBytes.set(0L)
            lastOverflowReason = null
            return discarded
        }
    }

    /**
     * 关闭队列并排空剩余单元供释放。
     */
    fun close(): List<EncodedAccessUnit> {
        lock.withLock {
            closed.set(true)
            val discarded = ArrayList(queue)
            queue.clear()
            currentBytes.set(0L)
            return discarded
        }
    }

    fun snapshot(): EncodedQueueSnapshot {
        lock.withLock {
            val oldest = queue.peekFirst()?.arrivalMonoNs
            return EncodedQueueSnapshot(
                depth = queue.size,
                bytes = currentBytes.get(),
                oldestArrivalNs = oldest,
                configGeneration = currentConfigGeneration.get(),
                overflowReason = lastOverflowReason
            )
        }
    }

    fun isClosed(): Boolean = closed.get()
}
