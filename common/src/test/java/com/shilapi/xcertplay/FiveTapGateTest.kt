package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class FiveTapGateTest {
    @Test fun onlyTheFifthContinuousTapOpensThePage() {
        val gate = FiveTapGate()
        repeat(4) { assertFalse(gate.tap(it * 150L)) }
        assertTrue(gate.tap(600L))
        assertFalse(gate.tap(750L))
    }

    @Test fun aPauseOrClockResetStartsANewSequence() {
        val gate = FiveTapGate()
        repeat(4) { assertFalse(gate.tap(it * 150L)) }
        assertFalse(gate.tap(2_000L))
        assertFalse(gate.tap(0L))
        repeat(3) { assertFalse(gate.tap((it + 1) * 150L)) }
        assertTrue(gate.tap(600L))
    }
}
