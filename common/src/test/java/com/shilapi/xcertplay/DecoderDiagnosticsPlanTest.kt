package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DecoderDiagnosticsPlanTest {
    @Test fun everySourceHasCompleteIndependentMatrixAndStableIdentity() {
        val plan = DecoderDiagnosticsRun.plan()
        val rows = (0 until plan.length()).map { plan.getJSONObject(it) }
        assertEquals(68, rows.size)
        assertEquals(rows.size, rows.map { it.getString("caseId") }.toSet().size)
        rows.groupBy { it.getString("sourceId") }.values.forEach { group ->
            assertEquals(setOf("P1", "P2", "P3", "P4", "P5", "P6"), group.map { it.getString("path") }.toSet())
            assertEquals(setOf(false, true), group.filter { !it.getBoolean("surface") }.map { it.getBoolean("software") }.toSet())
        }
        assertEquals(6, DecoderDiagnosticsRun.plan(true).length())
    }
    @Test fun executionDoesNotImplyValidColorOrPublication() {
        val row = DecoderDiagnosticsRun.result(DecoderDiagnosticsRun.plan().getJSONObject(0), "completed")
        assertEquals("invalid", row.getString("measurementValidity"))
        assertEquals("not_determined", row.getString("colorVerdict"))
        assertFalse(row.has("publication"))
    }
    @Test fun shortInputWaitsForQueuedFirstPictureAndCaseBudgetIncludesEveryStage() {
        val window=DecoderDiagnosticsCaptureWindow(5000)
        assertEquals(DecoderDiagnosticsCaptureWindow.Action.READ,window.action(100,0))
        window.exhausted=true
        assertEquals(DecoderDiagnosticsCaptureWindow.Action.WAIT,window.action(1600,0))
        assertEquals(DecoderDiagnosticsCaptureWindow.Action.COMPLETE,window.action(2300,1))
        assertEquals(DecoderDiagnosticsCaptureWindow.Action.TIMEOUT,window.action(5000,0))
        assertEquals(43000L,DecoderDiagnosticsRun.caseDeadlineMs(DecoderDiagnosticsRun.plan().getJSONObject(0)))
    }
    @Test fun h1FixturesContainFortyFiveFramesAndMatchTheirIndependentReference() {
        val c=org.robolectric.RuntimeEnvironment.getApplication()
        val files=c.assets.open("color-test/h1-reference.json").bufferedReader().use{org.json.JSONObject(it.readText())}.getJSONObject("files")
        files.keys().forEach{name->
            assertEquals(45,files.getJSONObject(name).getInt("frames"))
            val actual=c.assets.open("color-test/$name.mp4").use{java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes())}
                .joinToString(""){"%02x".format(it.toInt() and 255)}
            assertEquals(files.getJSONObject(name).getString("sha256"),actual)
        }
    }
    @Test fun untrustedRunIdCannotEscapeEvidenceRoot() {
        val c = org.robolectric.RuntimeEnvironment.getApplication()
        assertThrows(IllegalArgumentException::class.java) { DecoderDiagnosticsRun.directory(c, "../../other") }
    }
}
