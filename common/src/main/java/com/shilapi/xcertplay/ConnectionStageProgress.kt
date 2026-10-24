package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus

/** Current observed phase only. Never derives a percentage or displays raw protocol data. */
internal class ConnectionStageProgress {
    var textId: Int = R.string.getting_carplay_ready
        private set
    private var acceptsProtocol = true

    fun status(status: CarPlayStatus) {
        acceptsProtocol = status !is CarPlayStatus.Failed && status != CarPlayStatus.ControlEnded
        textId = when (status) {
            CarPlayStatus.DiscoveringMfi -> R.string.preparing_mfi_authentication
            CarPlayStatus.WaitingForMfi -> R.string.waiting_for_mfi_coprocessor
            CarPlayStatus.RequestingMfiPermission -> R.string.requesting_mfi_usb_permission
            CarPlayStatus.MfiReady -> R.string.connection_stage_device_ready
            CarPlayStatus.StartingHotspot -> R.string.connection_stage_network
            is CarPlayStatus.HotspotReady -> R.string.connection_stage_network_ready
            CarPlayStatus.WaitingForPairedIphone -> R.string.looking_for_your_paired_iphone
            CarPlayStatus.ConnectingBluetooth -> R.string.connecting_bluetooth
            CarPlayStatus.RunningWireless -> R.string.connection_stage_identifying
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> R.string.connection_stage_waiting_picture
            CarPlayStatus.DiscoveringIphone -> R.string.discovering_iphone
            CarPlayStatus.WaitingForIphone -> R.string.waiting_for_iphone_over_usb
            CarPlayStatus.RequestingIphonePermission -> R.string.requesting_iphone_usb_permission
            CarPlayStatus.WaitingForReenumeration -> R.string.status_waiting_reenumeration
            CarPlayStatus.SelectingConfiguration -> R.string.selecting_carplay_configuration
            CarPlayStatus.OpeningDataPaths -> R.string.opening_usb_data_paths
            CarPlayStatus.Pairing -> R.string.pairing_with_iphone
            CarPlayStatus.ConnectingControl -> R.string.connecting_iap2_control
            CarPlayStatus.AttachingNetwork -> R.string.connection_stage_airplay_network
            CarPlayStatus.RunningControl -> R.string.connection_stage_waiting_picture
            CarPlayStatus.ControlEnded -> R.string.reconnecting_to_your_iphone
            is CarPlayStatus.Failed -> R.string.connection_stage_failed
        }
    }

    fun protocol(message: String): Boolean = ConnectionStageProtocol.textId(message)?.let(::protocol) ?: false

    fun protocol(next: Int): Boolean {
        if (!acceptsProtocol || next == textId) return false
        textId = next
        return true
    }
}

/** Extracts only a fixed label; safe to use before log redaction without exposing payload data. */
internal object ConnectionStageProtocol {
    fun textId(message: String): Int? {
        if (message.length > 2048) return null
        val line = message.removePrefix("wired ").removePrefix("iAP tunnel ")
        return when {
            line == "iap2 identification accepted" -> R.string.connection_stage_authenticating
            line.startsWith("iap2 mfi rx=0xaa00 ") || line.startsWith("iap2 mfi rx=0xaa02 ") ->
                R.string.connection_stage_authenticating
            line == "iap2 authentication accepted" || line == "iap2 mfi rx=0xaa05 authentication-succeeded" ->
                R.string.connection_stage_authenticated
            line == "iap2 subscriptions sent" -> R.string.connection_stage_configuring
            line == "iap2 rx=0x5702 request-wifi-configuration" -> R.string.connection_stage_sending_network
            line == "iap2 tx=0x5703 accessory-wifi-configuration" -> R.string.connection_stage_joining_network
            line == "iap2 tx=0x4301 carplay-start-session" -> R.string.connection_stage_waiting_session
            line.startsWith("airplay rx ") -> airPlay(line)
            else -> null
        }
    }

    private fun airPlay(line: String): Int? {
        val parts = line.split(' ', limit = 6)
        if (parts.size < 4) return null
        return when {
            parts[3] in listOf("/auth-setup", "/pair-setup", "/pair-verify") -> R.string.connection_stage_pairing
            parts[2] == "SETUP" -> R.string.connection_stage_media
            parts[2] == "RECORD" -> R.string.connection_stage_waiting_picture
            else -> null
        }
    }
}
