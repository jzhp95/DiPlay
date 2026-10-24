package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ColorTestRunTest {
    @Test fun planCoversBothPathsBothCodecsAndBothEncodedRanges() {
        val cases = ColorTestRun.cases
        val local = cases.filter { it.asset != null }
        assertEquals(16, local.size)
        assertEquals(16, local.map { Triple(it.asset, it.surface, it.forceSoftware) }.distinct().size)
        assertEquals(10, cases.count { it.mainProfile != null })
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

}
