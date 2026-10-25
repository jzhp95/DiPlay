package com.shilapi.xcertplay

import android.app.Activity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomaticColorContinuationTest {
    private lateinit var activity: Activity
    private var page: ColorTestPage? = null
    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
        activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).edit().clear().commit()
    }
    @After fun cleanup() {
        page?.close(keepAutomation = true)
        val deadline = System.nanoTime() + 2_000_000_000
        while (AutomaticVideoCleanup.blocked() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertFalse(AutomaticVideoCleanup.blocked())
        AutomaticVideoSuite.stop(activity, "cleanup")
        activity.getSharedPreferences("automatic_video_suite_v1", 0).edit().clear().commit()
        activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).edit().clear().commit()
    }
    private fun result(id: String) {
        val data = JSONObject().put("token", "owned-token").put("index", 16).put("verdict", "无法显示")
            .put("teardown_complete", true).put("teardown_confirmed", true)
            .put("evidence", JSONObject().put("auto_run_id", id).put("rendered", false))
        activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).edit()
            .putString("pending_token", "owned-token").putString("pending_result", data.toString()).commit()
    }
    @Test fun manualFixtureAndMainStartsWaitForThePreviousResourcesInsteadOfCrashing() {
        var released = false
        page = ColorTestPage(activity, DiPlayPalette.LIGHT)
        try {
            MainProbeTeardown.retain { released }
            for (index in listOf(0, 17)) {
                page!!.run.select(index)
                org.robolectric.util.ReflectionHelpers.setField(page, "visible", true)
                org.robolectric.util.ReflectionHelpers.setField(page, "active", true)
                page!!.javaClass.getDeclaredMethod("playCurrent").apply { isAccessible = true }.invoke(page)
                assertFalse(org.robolectric.util.ReflectionHelpers.getField<Boolean>(page, "active"))
                assertNull(activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).getString("pending_token", null))
            }
        } finally { released = true; MainProbeTeardown.blocked() }
    }
    @Test fun unconfirmedMainResultIsSavedBeforeTheSuiteStops() {
        val id = AutomaticVideoSuite.begin(activity)
        repeat(16) { AutomaticVideoSuite.record(activity, id, it, JSONObject(), "fixture") }
        result(id)
        val prefs = activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0)
        val data = JSONObject(prefs.getString("pending_result", "{}"))
        data.put("teardown_confirmed", false)
        data.getJSONObject("evidence").put("teardown_interrupted", true)
        prefs.edit().putString("pending_result", data.toString()).commit()
        page = ColorTestPage(activity, DiPlayPalette.LIGHT)
        page!!.javaClass.getDeclaredMethod("consumeMainResult").apply { isAccessible = true }.invoke(page)
        val state = AutomaticVideoSuite.read(activity)
        assertEquals(17, state.getJSONArray("results").length())
        assertEquals("interrupted", state.getString("status"))
        assertTrue(state.getJSONArray("results").getJSONObject(16).getJSONObject("evidence").getBoolean("teardown_interrupted"))
    }
    @Test fun anAutomaticMainFailureStillAdvancesOnceAfterConfirmedTeardown() {
        val id = AutomaticVideoSuite.begin(activity)
        repeat(16) { AutomaticVideoSuite.record(activity, id, it, JSONObject(), "fixture") }
        result(id)
        page = ColorTestPage(activity, DiPlayPalette.LIGHT)
        val consume = page!!.javaClass.getDeclaredMethod("consumeMainResult").apply { isAccessible = true }
        consume.invoke(page); consume.invoke(page)
        assertEquals(17, AutomaticVideoSuite.read(activity).getInt("index"))
        assertEquals(17, AutomaticVideoSuite.read(activity).getJSONArray("results").length())
    }
    @Test fun aLateMainResultCannotAdvanceOrStopADifferentRun() {
        val id = AutomaticVideoSuite.begin(activity)
        repeat(16) { AutomaticVideoSuite.record(activity, id, it, JSONObject(), "fixture") }
        result("old-run")
        page = ColorTestPage(activity, DiPlayPalette.LIGHT)
        page!!.javaClass.getDeclaredMethod("consumeMainResult").apply { isAccessible = true }.invoke(page)
        assertEquals(16, AutomaticVideoSuite.read(activity).getInt("index"))
        assertTrue(AutomaticVideoSuite.active(activity))
    }
}
