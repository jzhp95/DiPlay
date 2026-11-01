package com.shilapi.xcertplay.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoResourceBudgetTest {

    @Test
    fun reservationWithinLimitsSucceedsAndReleasesCleanly() {
        val budget = VideoResourceBudget(globalLimitBytes = 10 * 1024 * 1024)
        val res1 = budget.tryReserve(BudgetKind.DECRYPTED_AU_POOL, 2 * 1024 * 1024)
        assertNotNull(res1)

        val snap1 = budget.snapshot()
        assertEquals(2 * 1024 * 1024L, snap1.globalUsageBytes)
        assertEquals(2 * 1024 * 1024L, snap1.usageByKind[BudgetKind.DECRYPTED_AU_POOL])

        // Idempotent release
        res1?.close()
        res1?.close()

        val snap2 = budget.snapshot()
        assertEquals(0L, snap2.globalUsageBytes)
        assertEquals(0L, snap2.usageByKind[BudgetKind.DECRYPTED_AU_POOL])
        assertEquals(2 * 1024 * 1024L, snap2.highWaterBytes)
    }

    @Test
    fun reservationExceedingKindLimitIsRejected() {
        val budget = VideoResourceBudget()
        // CIPHERTEXT_INGRESS default limit is 8 MiB
        val excessive = budget.tryReserve(BudgetKind.CIPHERTEXT_INGRESS, 9L * 1024 * 1024)
        assertNull(excessive)
        assertEquals(0L, budget.snapshot().globalUsageBytes)
    }

    @Test
    fun reservationExceedingGlobalLimitIsRejectedEvenIfKindAllows() {
        // Global limit 10 MiB, kind limit 16 MiB
        val budget = VideoResourceBudget(
            globalLimitBytes = 10L * 1024 * 1024,
            limitsByKind = mapOf(BudgetKind.DECRYPTED_AU_POOL to 16L * 1024 * 1024)
        )

        val res1 = budget.tryReserve(BudgetKind.DECRYPTED_AU_POOL, 8L * 1024 * 1024)
        assertNotNull(res1)

        // Try to reserve 4 more MiB: 8 + 4 = 12 > 10 global limit
        val res2 = budget.tryReserve(BudgetKind.DECRYPTED_AU_POOL, 4L * 1024 * 1024)
        assertNull(res2)

        res1?.close()
        assertEquals(0L, budget.snapshot().globalUsageBytes)
    }

    @Test
    fun multipleKindsSummingOverGlobalLimitAreRejected() {
        val budget = VideoResourceBudget(globalLimitBytes = 10L * 1024 * 1024)
        val resA = budget.tryReserve(BudgetKind.CIPHERTEXT_INGRESS, 6L * 1024 * 1024)
        assertNotNull(resA)

        // DECRYPTED_AU_POOL limit is 16MB, but remaining global is only 4MB
        val resB = budget.tryReserve(BudgetKind.DECRYPTED_AU_POOL, 5L * 1024 * 1024)
        assertNull(resB)

        resA?.close()
        assertEquals(0L, budget.snapshot().globalUsageBytes)
    }

    @Test
    fun bufferPoolAcquiresRecyclesAndReleasesOnClose() {
        val budget = VideoResourceBudget(globalLimitBytes = 16L * 1024 * 1024)
        val pool = VideoBufferPool(budget, maxPoolBuffers = 2, maxBufferSize = 1024 * 1024)

        // 1. Acquire lease
        val lease1 = pool.acquire(1024)
        assertNotNull(lease1)
        assertEquals(1024L, budget.snapshot().globalUsageBytes)
        assertEquals(0, pool.pooledCount())

        // 2. Return to pool
        lease1?.close()
        assertEquals(1, pool.pooledCount())
        assertEquals(1024L, budget.snapshot().globalUsageBytes) // Budget still held while in pool

        // 3. Re-acquire from pool (reuses same buffer without new budget allocation)
        val lease2 = pool.acquire(512)
        assertNotNull(lease2)
        assertEquals(0, pool.pooledCount())
        assertEquals(1024L, budget.snapshot().globalUsageBytes)

        lease2?.close()
        assertEquals(1, pool.pooledCount())

        // 4. Close pool releases all cached buffers and frees budget
        pool.close()
        assertEquals(0, pool.pooledCount())
        assertEquals(0L, budget.snapshot().globalUsageBytes)
    }
}
