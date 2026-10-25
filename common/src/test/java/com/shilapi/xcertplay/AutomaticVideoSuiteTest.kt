package com.shilapi.xcertplay

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AutomaticVideoSuiteTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before @After fun clear() { AutomaticVideoSuite.stop(context, "test_cleanup"); context.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit() }
    @Test fun focusedSequenceRunsOnlyTheRelevantCasesAndPreservesTheirIdentity() {
        val id = AutomaticVideoSuite.begin(context, focused = true)
        for (index in listOf(0, 2, 41, 42, 17, 25, 40)) {
            assertEquals(index, AutomaticVideoSuite.read(context).getInt("index"))
            assertTrue(AutomaticVideoSuite.record(context, id, index, JSONObject(), "collected"))
        }
        val state = AutomaticVideoSuite.read(context)
        assertEquals(7, state.getJSONArray("results").length())
        assertEquals("completed", state.getString("status"))
    }
    @Test fun unconfirmedFinalTeardownStillKeepsTheLastEvidenceAndNeverMarksCompleted() {
        val id = AutomaticVideoSuite.begin(context, focused = true)
        for (index in listOf(0, 2, 41, 42, 17, 25))
            AutomaticVideoSuite.record(context, id, index, JSONObject(), "collected")
        assertTrue(AutomaticVideoSuite.record(context, id, 40,
            JSONObject().put("teardown_interrupted", true), "collected", canContinue = false))
        val state = AutomaticVideoSuite.read(context)
        assertEquals(7, state.getJSONArray("results").length())
        assertEquals("interrupted", state.getString("status"))
        assertFalse(AutomaticVideoSuite.active(context))
    }
    @Test fun everyCaseRunsOnceAndFailuresStillAdvanceWithoutAColorPassClaim() {
        val id = AutomaticVideoSuite.begin(context)
        for (index in ColorTestRun.cases.indices) {
            assertTrue(AutomaticVideoSuite.record(context, id, index, JSONObject().put("rendered", index % 2 == 0),
                if (index % 2 == 0) "自动采集（未人工判色）" else "无法显示"))
            assertFalse(AutomaticVideoSuite.record(context, id, index, JSONObject(), "duplicate"))
        }
        val state = AutomaticVideoSuite.read(context)
        assertEquals("completed", state.getString("status"))
        assertEquals(43, state.getJSONArray("results").length())
        assertEquals("not_automatically_measured", state.getString("visualColorVerdict"))
        assertFalse(AutomaticVideoSuite.active(context))
    }
    @Test fun wrongRunOrOutOfOrderCallbacksCannotAdvanceTheCurrentCase() {
        val id = AutomaticVideoSuite.begin(context)
        assertFalse(AutomaticVideoSuite.record(context, "old", 0, JSONObject(), "old"))
        assertFalse(AutomaticVideoSuite.record(context, id, 1, JSONObject(), "future"))
        assertEquals(0, AutomaticVideoSuite.read(context).getInt("index"))
    }
    @Test fun interruptedRunCannotBeResurrectedByALateFrame() {
        val old = AutomaticVideoSuite.begin(context)
        AutomaticVideoSuite.stop(context, "user_cancelled")
        assertFalse(AutomaticVideoSuite.record(context, old, 0, JSONObject(), "late"))
        val current = AutomaticVideoSuite.begin(context)
        assertNotEquals(old, current)
        assertFalse(AutomaticVideoSuite.record(context, old, 0, JSONObject(), "late"))
        assertEquals(0, AutomaticVideoSuite.read(context).getInt("index"))
    }
    @Test fun aLateExportCannotAttachAnOldArchiveToANewRun() {
        val old = AutomaticVideoSuite.begin(context)
        AutomaticVideoSuite.stop(context, "restart")
        val current = AutomaticVideoSuite.begin(context)
        AutomaticVideoSuite.exported(context, old, "/old/report.zip", true)
        assertEquals(current, AutomaticVideoSuite.read(context).getString("id"))
        assertFalse(AutomaticVideoSuite.read(context).has("exportPath"))
    }
    @Test fun processDeathDuringFinalCleanupDoesNotLeaveTheNewPageWaitingForever() {
        AutomaticVideoSuite.begin(context)
        val value = AutomaticVideoSuite.read(context).put("id", "dead-process")
            .put("status", "completed").put("cleanupPending", true)
        context.getSharedPreferences("automatic_video_suite_v1", 0).edit().putString("state", value.toString()).commit()
        assertFalse(AutomaticVideoSuite.active(context))
        assertFalse(AutomaticVideoSuite.read(context).getBoolean("cleanupPending"))
        assertEquals("interrupted", AutomaticVideoSuite.read(context).getString("status"))
    }
    @Test fun persistedRunningFlagWithoutTheProcessLeaseBecomesInterrupted() {
        AutomaticVideoSuite.begin(context)
        val value = AutomaticVideoSuite.read(context).put("id", "other-process")
        context.getSharedPreferences("automatic_video_suite_v1", 0).edit().putString("state", value.toString()).commit()
        assertFalse(AutomaticVideoSuite.active(context))
        assertEquals("process_restarted", AutomaticVideoSuite.read(context).getString("reason"))
    }
}
