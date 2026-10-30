package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recovery moved from the Host to the service; no page-owned budget is injected here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class WirelessStartupRecoveryTest {
    private val failure=CarPlayStatus.Failed("timeout",startupFailure=WirelessStartupFailure.FIRST_TCP_TIMEOUT)
    @Test fun authenticationFailureGetsOnlyOneFreshConnectionRetry() {
        val h=SessionRecoveryHarness();val auth=CarPlayStatus.Failed("MFi failed",authenticationFailure=true)
        h.report(auth);h.report(auth)
        assertEquals(1,h.pendingTimers);h.advance();assertEquals(2,h.attempts.size)
        h.report(auth);h.advance()
        assertEquals(2,h.attempts.size);assertEquals(RuntimePhase.FAILED,h.runtime.snapshot().phase)
        assertTrue(ProjectionUiStateRenderer.render(h.runtime.snapshot()).showRetry)
    }
    @Test fun aLateActiveSessionCannotReviveAnAuthenticationFailedConnection() {
        val h=SessionRecoveryHarness()
        h.report(CarPlayStatus.Failed("MFi failed",authenticationFailure=true))
        h.attempts.first().listener.onSessionActive(mock(AirPlaySession::class.java))
        assertEquals(RuntimePhase.RECONNECTING,h.runtime.snapshot().phase)
        h.advance();assertEquals(2,h.attempts.size)
    }
    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        val h=SessionRecoveryHarness();h.report(failure);h.report(failure)
        h.report(CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1,h.pendingTimers);h.advance();assertEquals(2,h.attempts.size)
    }
    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndPreservesCause() {
        val h=SessionRecoveryHarness()
        repeat(6){h.report(failure);h.advance()}
        assertEquals(6,h.attempts.size);assertEquals(RuntimePhase.FAILED,h.runtime.snapshot().phase)
        assertEquals(failure,h.runtime.snapshot().protocolStatus)
        assertTrue(ProjectionUiStateRenderer.render(h.runtime.snapshot()).showRetry)
    }
    @Test fun nonRetryableHotspotAndBluetoothFailuresStopWithoutConsumingAnotherAttempt() {
        for(reason in listOf(WirelessStartupFailure.HOTSPOT_CONFIGURATION,WirelessStartupFailure.BLUETOOTH_UNUSABLE,
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT)){
            val h=SessionRecoveryHarness();h.report(CarPlayStatus.Failed("unavailable",startupFailure=reason));h.advance()
            assertEquals(reason.name,1,h.attempts.size)
            assertEquals(RuntimePhase.FAILED,h.runtime.snapshot().phase)
        }
    }
    @Test fun ordinaryBluetoothFailureKeepsRetrying() {
        val h=SessionRecoveryHarness();h.report(CarPlayStatus.Failed("Bluetooth radio off"))
        assertEquals(RuntimePhase.RECONNECTING,h.runtime.snapshot().phase)
        assertFalse(ProjectionUiStateRenderer.render(h.runtime.snapshot()).showRetry)
        h.advance();assertEquals(2,h.attempts.size)
    }
    @Test fun hotspotNotReadyHasTheSameBoundedBudgetAsFirstTcpTimeout() {
        val h=SessionRecoveryHarness()
        val failure=CarPlayStatus.Failed("not ready",startupFailure=WirelessStartupFailure.HOTSPOT_NOT_READY)
        repeat(6){h.report(failure);h.advance()}
        assertEquals(RuntimePhase.FAILED,h.runtime.snapshot().phase)
        assertEquals(failure,h.runtime.snapshot().protocolStatus)
    }
    @Test fun manualReconfigureResetsExhaustedBudget() {
        val h=SessionRecoveryHarness();repeat(6){h.report(failure);h.advance()}
        assertTrue(h.runtime.reconfigure(h.request,"manual").accepted)
        assertEquals(7,h.attempts.size)
        h.report(failure);h.advance();assertEquals(8,h.attempts.size)
    }
    @Test fun staleStatusAndActiveCallbacksCannotChangeReplacementState() {
        val h=SessionRecoveryHarness();val old=h.attempts.single()
        h.report(failure);h.advance();val identity=h.runtime.snapshot().identity
        old.report(CarPlayStatus.Failed("MFi failed",authenticationFailure=true))
        old.listener.onSessionActive(mock(AirPlaySession::class.java))
        assertEquals(identity,h.runtime.snapshot().identity);assertEquals(RuntimePhase.STARTING,h.runtime.snapshot().phase)
        assertEquals(0,h.pendingTimers)
    }
    @Test fun explicitStopCancelsScheduledRecoveryWithoutAPage() {
        val h=SessionRecoveryHarness();h.report(failure);h.runtime.stop("user"){};h.advance()
        assertEquals(1,h.attempts.size);assertEquals(RuntimePhase.IDLE,h.runtime.snapshot().phase)
        assertNull(h.runtime.snapshot().identity)
    }
    @Test fun closingAPageSubscriptionDoesNotStopRecovery() {
        val h=SessionRecoveryHarness();var updates=0
        val subscription=h.runtime.subscribe{updates++};subscription.close();val before=updates
        h.report(failure);h.advance()
        assertEquals(2,h.attempts.size);assertEquals(before,updates)
    }
    @Test fun wifiResetRequiresExplicitUserAction() {
        val h=SessionRecoveryHarness();val failure=CarPlayStatus.Failed("reset",wifiResetRequired=true)
        h.report(failure);h.advance()
        assertEquals(1,h.attempts.size);assertEquals(RuntimePhase.WAITING_PREREQUISITES,h.runtime.snapshot().phase)
        assertEquals("wifi_reset_required",h.runtime.snapshot().failure)
        assertFalse(ProjectionUiStateRenderer.render(h.runtime.snapshot()).requestPrerequisites)
    }
    @Test fun aRecoveredControlLoopCancelsItsOldRetryButANewLossCanRetry() {
        val h=SessionRecoveryHarness();h.report(failure)
        h.report(CarPlayStatus.RunningControl);h.advance()
        assertEquals(1,h.attempts.size)
        h.report(CarPlayStatus.ControlEnded);h.advance()
        assertEquals(2,h.attempts.size)
    }
    @Test fun anActiveSessionCancelsAnEarlierOrdinaryFailureRetry() {
        val h=SessionRecoveryHarness();h.report(failure)
        h.attempts.last().listener.onSessionActive(mock(AirPlaySession::class.java));h.advance()
        assertEquals(1,h.attempts.size);assertEquals(RuntimePhase.RUNNING,h.runtime.snapshot().phase)
    }

    @Test fun aNewFailureAfterRecoverySchedulesAFreshRetry() {
        val h=SessionRecoveryHarness()
        h.report(CarPlayStatus.Failed("configuration write failed"))
        h.report(CarPlayStatus.RunningControl)
        h.report(CarPlayStatus.Failed("transport write failed"))
        h.advance();assertEquals(2,h.attempts.size)
    }
    @Test fun transportLossAfterRecoverySchedulesAFreshRetry() {
        val h=SessionRecoveryHarness()
        h.report(CarPlayStatus.Failed("configuration write failed"))
        h.report(CarPlayStatus.RunningControl)
        h.attempts.last().listener.onTransportError("transport lost")
        h.advance();assertEquals(2,h.attempts.size)
    }
    @Test fun waitingAfterFailureDoesNotCancelRecovery() {
        val h=SessionRecoveryHarness()
        h.report(CarPlayStatus.Failed("configuration write failed"))
        h.report(CarPlayStatus.WaitingForIphone)
        h.advance();assertEquals(2,h.attempts.size)
    }

}
