package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import org.junit.Assert.*
import org.junit.Test

class ConnectionStageProgressTest {
    @Test fun hardwareReadinessIsDistinctFromSuccessfulAuthentication() {
        val stage = ConnectionStageProgress()
        stage.status(CarPlayStatus.RequestingMfiPermission)
        assertEquals(R.string.requesting_mfi_usb_permission, stage.textId)
        stage.status(CarPlayStatus.MfiReady)
        assertEquals(R.string.connection_stage_device_ready, stage.textId)
        stage.status(CarPlayStatus.RunningWireless)
        assertTrue(stage.protocol("iap2 identification accepted"))
        assertEquals(R.string.connection_stage_authenticating, stage.textId)
        assertTrue(stage.protocol("wired iap2 mfi rx=0xaa05 authentication-succeeded"))
        assertEquals(R.string.connection_stage_authenticated, stage.textId)
    }
    @Test fun recognizedProgressUsesFixedLabelsAndIgnoresTelemetryAndRawSecrets() {
        val stage = ConnectionStageProgress()
        assertTrue(stage.protocol("iap2 tx=0x5703 accessory-wifi-configuration"))
        assertEquals(R.string.connection_stage_joining_network, stage.textId)
        assertFalse(stage.protocol("TRACE password=secret iap2 authentication accepted"))
        assertFalse(stage.protocol("airplay rx POST /feedback cseq=4 body=0"))
        assertEquals(R.string.connection_stage_joining_network, stage.textId)
        assertTrue(stage.protocol("iAP tunnel iap2 tx=0x4301 carplay-start-session"))
        assertTrue(stage.protocol("airplay rx POST /pair-verify cseq=4 body=100"))
        assertEquals(R.string.connection_stage_pairing, stage.textId)
        assertTrue(stage.protocol("airplay rx SETUP / cseq=5 body=100"))
        assertEquals(R.string.connection_stage_media, stage.textId)
        assertTrue(stage.protocol("airplay rx RECORD / cseq=6 body=0"))
        assertEquals(R.string.connection_stage_waiting_picture, stage.textId)
    }
    @Test fun terminalStatusBlocksLateProtocolMessagesUntilANewAttemptStarts() {
        val stage = ConnectionStageProgress()
        stage.status(CarPlayStatus.Failed("USB failed"))
        assertFalse(stage.protocol("iap2 authentication accepted"))
        assertEquals(R.string.connection_stage_failed, stage.textId)
        stage.status(CarPlayStatus.DiscoveringMfi)
        assertTrue(stage.protocol("iap2 authentication accepted"))
        stage.status(CarPlayStatus.ControlEnded)
        assertFalse(stage.protocol("airplay rx RECORD / cseq=1 body=0"))
        assertEquals(R.string.reconnecting_to_your_iphone, stage.textId)
    }
    @Test fun wiredBringupDoesNotDisappearBehindGenericPreparationText() {
        val stage = ConnectionStageProgress()
        stage.status(CarPlayStatus.SelectingConfiguration)
        assertEquals(R.string.selecting_carplay_configuration, stage.textId)
        stage.status(CarPlayStatus.OpeningDataPaths)
        assertEquals(R.string.opening_usb_data_paths, stage.textId)
        stage.status(CarPlayStatus.Pairing)
        assertEquals(R.string.pairing_with_iphone, stage.textId)
        stage.status(CarPlayStatus.RunningControl)
        assertEquals(R.string.connection_stage_waiting_picture, stage.textId)
    }
}
