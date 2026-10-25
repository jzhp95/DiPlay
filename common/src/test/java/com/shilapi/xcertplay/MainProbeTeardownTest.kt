package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MainProbeTeardownTest {
    @Test fun closureCanFinishAfterTheOldFourSecondWindowWithoutAnUnconfirmedResult() {
        var clock = 0L
        val result = MainProbeTeardown.await(
            { budget -> assertEquals(30_000L, budget); clock += 7000; true },
            { budget -> assertEquals(23_000L, budget); clock += 2000; true },
            now = { clock })
        assertTrue(result.getBoolean("confirmed"))
        assertEquals(9000, result.getInt("totalWaitMillis"))
    }
    @Test fun timeoutKeepsVideoAndControllerFailuresSeparateAndCannotSpendTheBudgetTwice() {
        var clock = 0L
        val result = MainProbeTeardown.await(
            { budget -> clock += budget; false },
            { budget -> assertEquals(0L, budget); true },
            now = { clock })
        assertFalse(result.getBoolean("confirmed"))
        assertFalse(result.getBoolean("controllerReleased"))
        assertTrue(result.getBoolean("videoReleased"))
        assertEquals(30_000, result.getInt("totalWaitMillis"))
    }
    @Test fun aTimedOutResourceBlocksNewTrialsUntilActualRelease() {
        var released = false
        try {
            MainProbeTeardown.retain { released }
            assertTrue(MainProbeTeardown.blocked())
            released = true
            assertFalse(MainProbeTeardown.blocked())
        } finally { released = true; MainProbeTeardown.blocked() }
    }
}
