package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class DiagnosticOutputGateTest {
    @Test fun productionUnaffectedButExplicitCaptureFreezesOneFrame(){
        val gate=DiagnosticOutputGate();repeat(100){assertTrue(gate.allow())}
        gate.request();assertTrue(gate.allow());repeat(100){assertFalse(gate.allow())}
    }
}
