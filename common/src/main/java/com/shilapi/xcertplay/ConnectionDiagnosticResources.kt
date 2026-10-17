package com.shilapi.xcertplay

import android.content.res.Resources
import com.shilapi.xcertplay.host.R

/** Internal diagnostic tokens for the existing stage classifier, never a selectable UI language.
 * Keep native-error matching independent of localized Android string resources.
 */
@Suppress("DEPRECATION")
internal class ConnectionDiagnosticResources(private val base: Resources) :
    Resources(base.assets, base.displayMetrics, base.configuration) {
    override fun getText(id: Int): CharSequence = tokens[id] ?: base.getText(id)

    private companion object {
        val tokens = mapOf(
            R.string.auto_value to "auto",
            R.string.carplay_control_running to "CarPlay control running",
            R.string.carplay_control_window_ended to "CarPlay control window ended",
            R.string.carplay_session_ended_reconnecting to "CarPlay session ended; reconnecting",
            R.string.connecting_bluetooth to "Connecting Bluetooth",
            R.string.connecting_iap2_control to "Connecting iAP2 control",
            R.string.could_not_start_carplay_return_to_diplay_and_check_app_per to "Could not start CarPlay. Return to Apple CarPlay and check app permissions.",
            R.string.discovering_iphone to "Discovering iPhone",
            R.string.existing_wifi_attaching to "Attaching to existing Wi-Fi",
            R.string.existing_wifi_ready to "Existing Wi-Fi ready: %1\$s · %2\$s · channel %3\$d · %4\$s",
            R.string.first_tcp_timeout to "The iPhone did not connect to CarPlay in time",
            R.string.hotspot_network_not_ready to "Hotspot network is not ready",
            R.string.mfi_authentication_ready to "MFi authentication ready",
            R.string.opening_usb_data_paths to "Opening USB data paths",
            R.string.pairing_with_iphone to "Pairing with iPhone",
            R.string.preparing_mfi_authentication to "Preparing MFi authentication",
            R.string.requesting_iphone_usb_permission to "Requesting iPhone USB permission",
            R.string.requesting_mfi_usb_permission to "Requesting MFi USB permission",
            R.string.selecting_carplay_configuration to "Selecting CarPlay configuration",
            R.string.starting_airplay_service to "Starting AirPlay service",
            R.string.starting_wireless_hotspot to "Starting wireless hotspot",
            R.string.status_attaching_ncm to "Attaching NCM/AirPlay network",
            R.string.status_failed to "Failed: %1\$s",
            R.string.status_hotspot_ready to "Hotspot ready: %1\$s, %2\$s, %3\$s, channel %4\$s",
            R.string.status_waiting_reenumeration to "Waiting for iPhone re-enumeration",
            R.string.transport_error_reconnecting to "Transport error; reconnecting",
            R.string.vpn_authorization_unavailable to "This head unit cannot open VPN authorization. Return to Apple CarPlay to check connection permissions or use wireless.",
            R.string.vpn_consent_was_denied to "VPN consent was denied",
            R.string.waiting_for_iphone_over_usb to "Waiting for iPhone over USB",
            R.string.waiting_for_mfi_coprocessor to "Waiting for MFi coprocessor",
            R.string.waiting_for_paired_iphone to "Waiting for paired iPhone",
            R.string.wireless_carplay_active to "Wireless CarPlay active",
            R.string.wireless_carplay_control_running to "Wireless CarPlay control running",
            R.string.wireless_startup_retries_exhausted to "Automatic retries stopped. Check the connection and retry manually.",
        )
    }
}
