package com.shilapi.xcertplay.media.video

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 资源预算类别（严格对应 Spec §22 划分）。
 */
enum class BudgetKind(val defaultLimitBytes: Long) {
    /** 接收密文暂存：8 MiB */
    CIPHERTEXT_INGRESS(8L * 1024 * 1024),
    /** 解密后 AU 池，含排队／解析／移交借用：合计 16 MiB */
    DECRYPTED_AU_POOL(16L * 1024 * 1024),
    /** 应用持有的解码帧／显示引用：合计 24 MiB */
    DECODED_FRAMES(24L * 1024 * 1024),
    /** 纹理上传重排暂存：8 MiB */
    TEXTURE_REPACK(8L * 1024 * 1024),
    /** 控制、指标和诊断轻量环形数据：4 MiB */
    METRICS_DIAGNOSTICS(4L * 1024 * 1024)
}

/**
 * 预算预留句柄，实现 AutoCloseable 具备幂等释放责任。
 */
interface BudgetReservation : AutoCloseable {
    val kind: BudgetKind
    val bytes: Long
    override fun close()
}

/**
 * 资源预算快照。
 */
data class BudgetSnapshot(
    val globalUsageBytes: Long,
    val globalLimitBytes: Long,
    val usageByKind: Map<BudgetKind, Long>,
    val highWaterBytes: Long
)

/**
 * 统一资源预算管理器（全局应用自有预算保护上限默认 64 MiB）。
 */
class VideoResourceBudget(
    val globalLimitBytes: Long = 64L * 1024 * 1024,
    private val limitsByKind: Map<BudgetKind, Long> = BudgetKind.values().associateWith { it.defaultLimitBytes }
) {
    private val totalUsed = AtomicLong(0L)
    private val highWater = AtomicLong(0L)
    private val usedByKind = ConcurrentHashMap<BudgetKind, AtomicLong>().apply {
        BudgetKind.values().forEach { put(it, AtomicLong(0L)) }
    }

    /**
     * 尝试预留指定类别的内存预算。
     * 若超过类别上限或超过全局保护上限（64 MiB），则返回 null。
     */
    fun tryReserve(kind: BudgetKind, bytes: Long): BudgetReservation? {
        if (bytes <= 0L) return null
        val kindLimit = limitsByKind[kind] ?: kind.defaultLimitBytes
        val kindCounter = usedByKind.getValue(kind)

        while (true) {
            val currentKindUsed = kindCounter.get()
            val newKindUsed = currentKindUsed + bytes
            if (newKindUsed > kindLimit) {
                return null
            }

            val currentTotal = totalUsed.get()
            val newTotal = currentTotal + bytes
            if (newTotal > globalLimitBytes) {
                return null
            }

            if (kindCounter.compareAndSet(currentKindUsed, newKindUsed)) {
                if (totalUsed.compareAndSet(currentTotal, newTotal)) {
                    updateHighWater(newTotal)
                    return ReservationImpl(kind, bytes)
                } else {
                    // Total CAS 竞争失败，回滚 kindCounter 并重试
                    kindCounter.addAndGet(-bytes)
                }
            }
        }
    }

    private fun updateHighWater(newTotal: Long) {
        var current = highWater.get()
        while (newTotal > current) {
            if (highWater.compareAndSet(current, newTotal)) break
            current = highWater.get()
        }
    }

    private fun release(kind: BudgetKind, bytes: Long) {
        usedByKind.getValue(kind).addAndGet(-bytes)
        totalUsed.addAndGet(-bytes)
    }

    fun snapshot(): BudgetSnapshot {
        val kindUsage = usedByKind.mapValues { it.value.get() }
        return BudgetSnapshot(
            globalUsageBytes = totalUsed.get(),
            globalLimitBytes = globalLimitBytes,
            usageByKind = kindUsage,
            highWaterBytes = highWater.get()
        )
    }

    private inner class ReservationImpl(
        override val kind: BudgetKind,
        override val bytes: Long
    ) : BudgetReservation {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                this@VideoResourceBudget.release(kind, bytes)
            }
        }
    }
}
