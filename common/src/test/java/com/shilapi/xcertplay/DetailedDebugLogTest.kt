package com.shilapi.xcertplay

import android.view.KeyEvent
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DetailedDebugLogTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() { clean() }
    @After fun clean() {
        restartProcessRecorder()
        context.getSharedPreferences("diplay_debug_recording", 0).edit().clear().commit()
        File(context.filesDir, "detailed-debug").deleteRecursively()
        WheelKeyLog.reset()
    }

    @Test fun enablingImmediatelyPersistsWheelEvidenceAndMarkerInTheExport() {
        assertFalse(DetailedDebugLog.enabled(context))
        DetailedDebugLog.setEnabled(context, true)
        WheelKeyLog.note("focus granted=true")
        WheelKeyLog.press(KeyEvent.KEYCODE_MEDIA_NEXT, 3, 4, true, "NEXT queued=true", false)
        WheelKeyLog.press(KeyEvent.KEYCODE_A, 0, 4, false, "typing must be private", true)
        DetailedDebugLog.record("USER_MARK", "problem observed")
        DetailedDebugLog.setEnabled(context, false)
        WheelKeyLog.note("AFTER_DISABLED")

        val report = DetailedDebugLog.report(context)
        assertTrue(report.contains("focus granted=true"))
        assertTrue(report.contains("NEXT queued=true"))
        assertTrue(report.contains("USER_MARK"))
        assertFalse(report.contains("typing must be private"))
        assertFalse(report.contains("AFTER_DISABLED"))
    }

    @Test fun processReinitializationRestoresTheSwitchAndKeepsEarlierEvents() {
        DetailedDebugLog.setEnabled(context, true)
        DetailedDebugLog.record("connection", "before restart")
        restartProcessRecorder()
        assertTrue(DetailedDebugLog.enabled(context))
        DetailedDebugLog.record("connection", "after restart")
        val report = DetailedDebugLog.report(context)
        assertTrue(report.contains("before restart"))
        assertTrue(report.contains("after restart"))
        DetailedDebugLog.setEnabled(context, false)
        restartProcessRecorder()
        assertFalse(DetailedDebugLog.enabled(context))
        assertTrue(DetailedDebugLog.report(context).contains("before restart"))
    }

    private fun restartProcessRecorder() {
        val field = DetailedDebugLog::class.java.getDeclaredField("recorder").apply { isAccessible = true }
        (field.get(null) as? DetailedDebugRecorder)?.let { it.report(); it.close() }
        field.set(null, null)
    }
}
