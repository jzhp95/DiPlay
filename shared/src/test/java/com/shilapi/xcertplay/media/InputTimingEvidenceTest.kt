package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class InputTimingEvidenceTest {
    @Test fun nextFrameArrivalWithoutTouchAssociationCannotBecomePhysicalLatency() {
        TouchLatencyProbe.onTouchSent(100,20)
        assertEquals(-1L,TouchLatencyProbe.onFrame(300))
        assertEquals(-1L,TouchLatencyProbe.onFrame(500))
    }
}
