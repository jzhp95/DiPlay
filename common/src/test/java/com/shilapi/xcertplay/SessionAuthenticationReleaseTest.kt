package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.session.RuntimePhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class SessionAuthenticationReleaseTest {
    @Test fun authenticationRecoveryWaitsForTheSameResourcesAcrossUiOwners() {
        val h=SessionRecoveryHarness();val old=h.attempts.single().resource
        old.released=false
        h.report(CarPlayStatus.Failed("MFi failed",authenticationFailure=true));h.advance()
        assertEquals(RuntimePhase.BLOCKED_RELEASE,h.runtime.snapshot().phase)
        assertSame(old.controller,h.runtime.currentConnection?.controller)
        h.runtime.claimUi(1);h.runtime.claimUi(2)
        assertFalse(h.runtime.start(h.request).accepted)
        h.advance();assertEquals(1,h.attempts.size)
        old.released=true;h.advance()
        assertEquals(2,h.attempts.size);assertEquals(1,old.closes)
    }
    @Test fun explicitStopDuringAuthenticationRetirementCannotCreateReplacement() {
        val h=SessionRecoveryHarness();val old=h.attempts.single().resource;old.released=false
        h.report(CarPlayStatus.Failed("MFi failed",authenticationFailure=true));h.advance()
        h.runtime.stop("user"){};old.released=true;h.advance()
        assertEquals(1,h.attempts.size);assertEquals(RuntimePhase.IDLE,h.runtime.snapshot().phase)
        assertEquals(1,old.closes)
    }
}
