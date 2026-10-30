package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** A complete AP configuration. A missing/hidden password is never guessed from a previous SSID. */
data class CarHotspotProfile(
    val ssid: String,
    val passphrase: String,
    val security: ManualHotspotSecurity,
    val band: ManualHotspotBand = ManualHotspotBand.AUTO,
    val channel: Int = 0,
) {
    enum class Error { CREDENTIALS, SECURITY, CHANNEL, BAND_CHANNEL }

    fun error(): Error? = when {
        ManualHotspotValidation.error(ssid, passphrase) != null -> Error.CREDENTIALS
        (security == ManualHotspotSecurity.OPEN) != passphrase.isEmpty() -> Error.SECURITY
        channel != 0 && channel !in CHANNELS -> Error.CHANNEL
        channel > 0 && band == ManualHotspotBand.GHZ_2_4 && channel > 14 -> Error.BAND_CHANNEL
        channel in 1..14 && band == ManualHotspotBand.GHZ_5 -> Error.BAND_CHANNEL
        else -> null
    }

    fun matches(actual: CarHotspotProfile): Boolean = actual.error() == null &&
        ssid == actual.ssid && passphrase == actual.passphrase && security == actual.security &&
        (band == ManualHotspotBand.AUTO || band == actual.band) && (channel == 0 || channel == actual.channel)

    override fun toString(): String = "CarHotspotProfile(security=$security, band=$band, channel=$channel, credentials=redacted)"

    companion object {
        val CHANNELS: List<Int> = (1..14).toList() + (36..144 step 4).toList() + listOf(149, 153, 157, 161, 165)
    }
}

enum class CarHotspotProfileSource { SAVED, SYSTEM, CONFIRMED, MANUAL }
data class ResolvedCarHotspotProfile(val profile: CarHotspotProfile, val source: CarHotspotProfileSource)

/**
 * Observed system hotspot state. Unlike [CarHotspotProfile], this represents partial information
 * (e.g. when the car system exposes the SSID but masks or omits the passphrase).
 * It is never used to execute connections or configure APs directly; it only guides UI pre-filling and prompts.
 */
data class ObservedHotspotInfo(
    val ssid: String,
    val passwordAvailable: Boolean,
    val rawPassword: String? = null,
    val security: ManualHotspotSecurity = ManualHotspotSecurity.WPA2,
    val band: ManualHotspotBand = ManualHotspotBand.AUTO,
    val channel: Int = 0,
)

object CarHotspotProfileSelection {
    /** Initial import order only; an existing saved profile is resolved before these loaders. */
    fun resolve(
        system: () -> CarHotspotProfile?,
        confirmed: () -> CarHotspotProfile?,
        manual: () -> CarHotspotProfile?,
    ): ResolvedCarHotspotProfile? {
        for ((source, load) in listOf(CarHotspotProfileSource.SYSTEM to system,
            CarHotspotProfileSource.CONFIRMED to confirmed, CarHotspotProfileSource.MANUAL to manual)) {
            val value = try { load()?.takeIf { it.error() == null } } catch (_: Exception) { null } ?: continue
            return ResolvedCarHotspotProfile(value, source)
        }
        return null
    }
}
