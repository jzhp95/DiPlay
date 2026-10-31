package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoBacklogRecoveryTest {
    private fun VideoBacklogRecovery.sample(now: Long, age: Long, span: Long, frames: Int = 3): Boolean =
        observe(now * MS, (now - age) * MS, frames, (now - age + span) * MS)

    @Test fun stableDelayedBacklogDoesNotRecoverAfterGrace() {
        val recovery = VideoBacklogRecovery()
        for (now in listOf(0L, 750L, 1500L, 3000L)) {
            assertFalse(recovery.sample(now, age = 400, span = 200))
        }
    }

    @Test fun spanGrowthRequiresTheWholeGraceAndClearsTheEpisode() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 400, span = 200))
        assertFalse(recovery.sample(749, age = 400, span = 300))
        assertTrue(recovery.sample(750, age = 400, span = 300))
        assertFalse(recovery.sample(751, age = 400, span = 400))
        assertFalse(recovery.sample(1500, age = 600, span = 500))
        assertTrue(recovery.sample(1501, age = 600, span = 500))
    }

    @Test fun frameGrowthCanRecoverWithoutSpanGrowth() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 400, span = 200, frames = 3))
        assertFalse(recovery.sample(750, age = 400, span = 200, frames = 6))
        assertTrue(recovery.sample(751, age = 400, span = 200, frames = 7))
    }

    @Test fun staticTailNeverRecoversAndClearsPreviousBacklog() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 400, span = 200))
        assertFalse(recovery.observe(10_000 * MS, 0, 0, null))
        assertFalse(recovery.sample(10_001, age = 400, span = 400))
        assertFalse(recovery.sample(10_750, age = 600, span = 500))
    }

    @Test fun lowThresholdsResetButTheHysteresisBandPreservesObservation() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 400, span = 200))
        assertFalse(recovery.sample(500, age = 200, span = 80))
        assertTrue(recovery.sample(750, age = 400, span = 300))
        for ((age, span) in listOf(150L to 100L, 400L to 50L)) {
            recovery.reset()
            assertFalse(recovery.sample(0, age = 400, span = 200))
            assertFalse(recovery.sample(500, age, span))
            assertFalse(recovery.sample(750, age = 400, span = 300))
            assertFalse(recovery.sample(1499, age = 600, span = 400))
            assertTrue(recovery.sample(1500, age = 600, span = 400))
        }
    }

    @Test fun softAgeAndSpanBoundariesMustBothStartObservation() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 250, span = 100))
        assertFalse(recovery.sample(750, age = 251, span = 99))
        assertFalse(recovery.sample(751, age = 251, span = 100))
        assertFalse(recovery.sample(1500, age = 251, span = 200))
        assertTrue(recovery.sample(1501, age = 251, span = 200))
    }

    @Test fun hardAgeIsBoundedButNeedsNewerQueuedFrames() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 1499, span = 100))
        assertTrue(recovery.sample(1, age = 1500, span = 100))
        assertFalse(recovery.sample(2, age = 2000, span = 99))
        assertFalse(recovery.observe(2000 * MS, 0, 0, null))
    }

    @Test fun invalidOrFutureTimestampsDoNotInventBacklog() {
        val recovery = VideoBacklogRecovery()
        val invalid = listOf(
            Triple(501L, 1, 501L),
            Triple(100L, 1, 501L),
            Triple(100L, 1, 99L),
            Triple(100L, 1, null),
            Triple(100L, -1, 200L),
        )
        for ((current, frames, newest) in invalid) {
            assertFalse(recovery.sample(0, age = 400, span = 200))
            assertFalse(recovery.observe(500 * MS, current * MS, frames, newest?.times(MS)))
            assertFalse(recovery.sample(750, age = 400, span = 300))
            recovery.reset()
        }
        assertFalse(recovery.sample(1000, age = 400, span = 200))
        assertFalse(recovery.sample(999, age = 400, span = 300))
        assertFalse(recovery.sample(1750, age = 500, span = 400))
    }

    @Test fun recoveryEpisodeRecordsMetricsDurationAndEndReason() {
        val recovery = VideoBacklogRecovery(maxHistory = 4)
        assertFalse(recovery.sample(0, age = 400, span = 200, frames = 3))
        val current = recovery.currentEpisode()
        assertNotNull(current)
        assertEquals(400 * MS, current!!.initialAgeNs)
        assertEquals(200 * MS, current.initialSpanNs)
        assertEquals(3, current.initialDepth)

        assertFalse(recovery.sample(500, age = 450, span = 250, frames = 5))
        assertEquals(450 * MS, current.peakAgeNs)
        assertEquals(250 * MS, current.peakSpanNs)
        assertEquals(5, current.peakDepth)

        assertTrue(recovery.sample(750, age = 460, span = 310, frames = 6))
        val triggered = recovery.currentEpisode()
        assertNotNull("Triggered episode must be available to write back action", triggered)
        triggered?.action = "reference_chain_reset"

        val history = recovery.recentEpisodes()
        assertEquals(1, history.size)
        val recorded = history.first()
        assertEquals(1L, recorded.episodeId)
        assertEquals(750 * MS, recorded.durationNs)
        assertEquals("reference_chain_reset", recorded.action)
        assertEquals("sustained_growth", recorded.endReason)

        recovery.reset()
        assertNull(recovery.currentEpisode())
    }

    @Test fun hardAgeRecoveryEpisodeRecordsHardLimitReason() {
        val recovery = VideoBacklogRecovery()
        assertTrue(recovery.sample(100, age = 1500, span = 120, frames = 4))
        val history = recovery.recentEpisodes()
        assertEquals(1, history.size)
        val ep = history.first()
        assertEquals("trigger_recovery", ep.action)
        assertEquals("hard_limit", ep.endReason)
    }

    private companion object { const val MS = 1_000_000L }
}
