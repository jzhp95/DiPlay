package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoDiagnosticLeaseTest {
    @Test fun cancelledLateSuspendCannotLeaveProductionPaused() {
        val sink=AndroidMediaSink()
        try{
            assertFalse(sink.suspendVideoForDiagnostics("expired"){true})
            assertFalse(sink.diagnosticsVideoSuspended())
            assertTrue(sink.suspendVideoForDiagnostics("owner"))
            assertFalse(sink.resumeVideoAfterDiagnostics("other"));assertTrue(sink.diagnosticsVideoSuspended())
            assertTrue(sink.resumeVideoAfterDiagnostics("owner"));assertFalse(sink.diagnosticsVideoSuspended())
        }finally{sink.close()}
    }
}
