package com.shilapi.xcertplay.media.video

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 有界直接字节缓冲池，带预算约束与显式生命周期管理。
 */
class VideoBufferPool(
    private val budget: VideoResourceBudget,
    private val budgetKind: BudgetKind = BudgetKind.DECRYPTED_AU_POOL,
    val maxPoolBuffers: Int = 16,
    val maxBufferSize: Int = 2 * 1024 * 1024
) : AutoCloseable {

    private val pool = ConcurrentLinkedQueue<PooledBufferEntry>()
    private val pooledCount = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    private class PooledBufferEntry(
        val buffer: ByteBuffer,
        val reservation: BudgetReservation
    )

    /**
     * 借用一块满足 sizeBytes 大小的缓冲租约。
     * 优先复用池中空闲块，无合适块时向 budget 申请新预留并分配直接内存。
     */
    fun acquire(sizeBytes: Int): BufferLease? {
        if (closed.get() || sizeBytes <= 0 || sizeBytes > maxBufferSize) {
            return null
        }

        // 尝试从池中寻找容量不小于 sizeBytes 的块
        var candidate: PooledBufferEntry? = null
        while (true) {
            val entry = pool.poll() ?: break
            pooledCount.decrementAndGet()
            if (entry.buffer.capacity() >= sizeBytes) {
                candidate = entry
                break
            } else {
                // 容量不够则退役该块释放预算
                entry.reservation.close()
            }
        }

        if (candidate != null) {
            val buf = candidate.buffer
            buf.clear()
            buf.limit(sizeBytes)
            return PooledBufferLease(buf, sizeBytes, candidate.reservation, this)
        }

        // 无可用复用块，向预算申请分配新块
        val reservation = budget.tryReserve(budgetKind, sizeBytes.toLong()) ?: return null
        return try {
            val newBuf = ByteBuffer.allocateDirect(sizeBytes)
            PooledBufferLease(newBuf, sizeBytes, reservation, this)
        } catch (t: Throwable) {
            reservation.close()
            throw t
        }
    }

    internal fun recycle(buffer: ByteBuffer, reservation: BudgetReservation) {
        if (closed.get() || pooledCount.get() >= maxPoolBuffers) {
            // 池已关闭或已达最大缓存数量，直接归还预算
            reservation.close()
            return
        }

        buffer.clear()
        pool.offer(PooledBufferEntry(buffer, reservation))
        pooledCount.incrementAndGet()
    }

    fun pooledCount(): Int = pooledCount.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            while (true) {
                val entry = pool.poll() ?: break
                pooledCount.decrementAndGet()
                entry.reservation.close()
            }
        }
    }

    private class PooledBufferLease(
        override val buffer: ByteBuffer,
        override val sizeBytes: Int,
        private val reservation: BudgetReservation,
        private val pool: VideoBufferPool
    ) : BufferLease {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                pool.recycle(buffer, reservation)
            }
        }
    }
}
