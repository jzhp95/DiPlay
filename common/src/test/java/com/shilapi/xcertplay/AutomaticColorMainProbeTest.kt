package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.VideoColorOverride
import org.junit.Assert.*
import org.junit.Test

class AutomaticColorMainProbeTest {
    @Test fun legacyAutomaticRequestCannotAcquireOrStartAMainScreenProbe() {
        val index=ColorTestRun.cases.indexOfFirst{it.mainProfile=="direct"}
        try {ColorMainProbeRegistry.begin(index,VideoColorOverride(),"retired-run");fail("legacy entry must be rejected")}
        catch(expected:IllegalArgumentException){assertNull(ColorMainProbeRegistry.lookup("retired-run"))}
        assertFalse(com.shilapi.xcertplay.diagnostics.DiagnosticLease.busy())
    }
}
