package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDiagnosticCaptureTest {
    private val key = byteArrayOf(0, 0, 0, 2, 0x65, 0)
    private val delta = byteArrayOf(0, 0, 0, 2, 0x41, 0)
    @Test fun onlyWholeGopsBecomeStrictReplay() {
        val c = VideoDiagnosticCapture(VideoCodec.H264, byteArrayOf(1), maxDurationNs = 10)
        c.receive(delta, now = 1); c.receive(key, now = 2); c.receive(delta, now = 5); c.receive(key, now = 20)
        val r = c.finish(); assertTrue(r.strictReplay); assertEquals(2, r.frames.size)
        assertArrayEquals(key, r.frames.first().bytes)
    }
    @Test fun changedConfigurationAndBudgetNeverPretendComplete() {
        val c = VideoDiagnosticCapture(VideoCodec.H264, byteArrayOf(1), budget = 6)
        c.receive(key, now = 1); c.receive(delta, now = 2)
        assertFalse(c.finish().strictReplay)
        val changed = VideoDiagnosticCapture(VideoCodec.H264, byteArrayOf(1))
        changed.receive(key); changed.configuration(VideoCodec.H264, byteArrayOf(2))
        assertTrue(changed.finish().configurationChanged); assertFalse(changed.finish().strictReplay)
    }
    @Test fun traceIsOptInBoundedAndStops() {
        VideoDiagnosticTrace.begin()
        repeat(50_002) { VideoDiagnosticTrace.event("d", "codec_output", it.toLong()) }
        val r = VideoDiagnosticTrace.end(); assertEquals(50_000, r.events.size); assertEquals(2, r.dropped)
        VideoDiagnosticTrace.event("d", "ignored", 0)
        assertEquals(50_000, VideoDiagnosticTrace.end().events.size)
    }
}
