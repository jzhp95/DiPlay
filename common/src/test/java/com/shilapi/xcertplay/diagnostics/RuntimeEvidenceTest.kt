package com.shilapi.xcertplay.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RuntimeEvidenceTest {
    @Test fun boundedLedgerKeepsNewestEventsAndReportsTruncation() {
        val ledger=RuntimeEvidenceLedger(2)
        repeat(4){ledger.record("session",1,"event",mapOf("revision" to it))}
        val snapshot=ledger.snapshot()
        assertEquals(2L,snapshot.second)
        val rows=snapshot.first.lines().filter{it.isNotBlank()}.map{JSONObject(it)}
        assertEquals(listOf(2,3),rows.map{it.getInt("revision")})
        assertTrue(rows.all{it.getString("sessionId")=="session" && it.getString("clock")=="System.nanoTime"})
    }
    @Test fun unapprovedPrivateFieldsCannotEnterEvidence() {
        val ledger=RuntimeEvidenceLedger()
        ledger.record("session",1,"state",mapOf("password" to "secret","token" to "private","phase" to "RUNNING"))
        val text=ledger.snapshot().first
        assertFalse(text.contains("secret"));assertFalse(text.contains("private"));assertTrue(text.contains("RUNNING"))
    }
    @Test fun capturedFilesCarryCollectionIdentityAndAnExplicitClock() {
        val previous=com.shilapi.xcertplay.DiPlaySessionService.liveRuntime
        com.shilapi.xcertplay.DiPlaySessionService.liveRuntime=null
        try {
            RuntimeEvidence.sessions.record("old-session",7,"capture_fixture")
            val files=RuntimeEvidence.capture("run-test")
            files.filterKeys{it.endsWith(".json")}.values.forEach{value->
                val row=JSONObject(value)
                assertEquals("run-test",row.getString("runId"))
                assertEquals("System.nanoTime",row.getString("clock"))
                assertTrue(row.has("capturedMonotonicNs"))
            }
            val rows=files.getValue("runtime/session-events.jsonl").lineSequence().filter{it.isNotBlank()}.map{JSONObject(it)}.toList()
            assertTrue(rows.all{it.getString("collectionRunId")=="run-test"})
            val fixture=rows.last{it.getString("event")=="capture_fixture"}
            assertEquals("old-session",fixture.getString("sessionId"))
            assertTrue(fixture.isNull("runId"))
        } finally {com.shilapi.xcertplay.DiPlaySessionService.liveRuntime=previous}
    }

}
