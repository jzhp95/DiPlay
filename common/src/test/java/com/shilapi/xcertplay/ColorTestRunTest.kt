package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ColorTestRunTest {
    @Test fun planCoversBothPathsBothCodecsAndBothEncodedRanges() {
        val cases = ColorTestRun.cases
        val local = cases.take(40).filter { it.asset != null && !it.hardwareYuv }
        assertEquals(setOf("h264_full", "h264_limited"), cases.take(43).filter { it.asset != null && it.hardwareYuv }.map { it.asset }.toSet())
        assertEquals(24, local.size)
        assertEquals(24, local.map { listOf(it.asset, it.surface, it.forceSoftware, it.color.fromSps) }.distinct().size)
        assertEquals(17, cases.count { it.mainProfile != null })
    }
    @Test fun unrenderedAndFailedCasesCannotBeReportedAsNormal() {
        val run = ColorTestRun()
        assertFalse(run.record("正常"))
        run.rendered = true
        assertTrue(run.record("正常"))
        run.select(1)
        assertFalse(run.rendered)
        assertFalse(run.record("正常"))
        assertTrue(run.record("无法显示"))
        assertTrue(run.report().contains("无法显示"))
    }
    @Test fun revisitingACaseDoesNotLoseOtherResultsAndCannotReuseOldRenderState() {
        val run = ColorTestRun()
        run.rendered = true
        run.record("发白")
        run.select(1)
        run.rendered = true
        run.record("正常")
        run.select(0)
        assertFalse(run.rendered)
        assertEquals("发白", run.results[0])
        assertEquals("正常", run.results[1])
    }
    @Test fun repeatedComparisonsKeepTheirOwnParametersAndCaptureReferences() {
        val run = ColorTestRun()
        run.rendered = true
        run.record("发白", capture = "observation-a.png")
        run.record("正常", range = 1, standard = 1, savedPicture = true, capture = "observation-b.png")
        assertEquals(2, run.history.size)
        assertNull(run.history[0].range)
        assertEquals("observation-a.png", run.history[0].capture)
        assertEquals(1, run.history[1].range)
        assertEquals(1, run.history[1].standard)
        assertTrue(run.history[1].savedPicture)
        assertEquals("observation-b.png", run.history[1].capture)
        assertTrue(run.report().contains("range=1"))
    }


    @Test fun spsComparisonsAppendWithoutChangingExistingCaseIndices() {
        val cases = ColorTestRun.cases
        assertEquals(51, cases.size)
        assertEquals("current", cases[16].mainProfile)
        assertEquals("yuv-auto", cases[36].mainProfile)
        assertTrue(cases.subList(36, 40).all { it.forceSoftware && it.mainProfile!!.startsWith("yuv-") })
        assertEquals("software-direct", cases[25].mainProfile)
        assertTrue(cases.take(26).none { it.color.fromSps })
        val sps = cases.filter { it.color.fromSps }
        assertEquals(10, sps.size)
        assertEquals(8, sps.count { it.asset != null })
        assertEquals(setOf("sps-texture", "sps-direct"), sps.mapNotNull { it.mainProfile }.toSet())
        assertTrue(sps.all { it.color.range == null && it.color.standard == null })
    }
}
