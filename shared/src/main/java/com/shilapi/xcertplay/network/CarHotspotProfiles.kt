package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity

/** Desired edits and verified system profiles never share keys with DiPlay's manual fallback. */
object CarHotspotProfiles {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_managed_hotspot", Context.MODE_PRIVATE)

    fun draft(context: Context): CarHotspotProfile? = load(context, "draft")
    fun confirmed(context: Context): CarHotspotProfile? = load(context, "confirmed")
    fun saveDraft(context: Context, profile: CarHotspotProfile) = save(context, "draft", profile)
    fun saveConfirmed(context: Context, profile: CarHotspotProfile) = save(context, "confirmed", profile)

    fun resolve(context: Context, manual: () -> CarHotspotProfile?, log: (String) -> Unit = {}): ResolvedCarHotspotProfile? {
        val result = CarHotspotProfileSelection.resolve(
            system = { CarHotspotController(context, log).read()?.also { saveConfirmed(context, it) } },
            confirmed = { confirmed(context) }, manual = manual,
        )
        log("hotspot configuration source=${result?.source?.name ?: "UNAVAILABLE"}")
        return result
    }

    private fun save(context: Context, prefix: String, profile: CarHotspotProfile) {
        require(profile.error() == null)
        prefs(context).edit().putString("${prefix}_ssid", profile.ssid)
            .putString("${prefix}_password", profile.passphrase).putString("${prefix}_security", profile.security.name)
            .putString("${prefix}_band", profile.band.name).putInt("${prefix}_channel", profile.channel).apply()
    }

    private fun load(context: Context, prefix: String): CarHotspotProfile? = runCatching {
        val prefs = prefs(context)
        val ssid = prefs.getString("${prefix}_ssid", null) ?: return null
        CarHotspotProfile(ssid, prefs.getString("${prefix}_password", null) ?: return null,
            ManualHotspotSecurity.valueOf(prefs.getString("${prefix}_security", "")!!),
            ManualHotspotBand.valueOf(prefs.getString("${prefix}_band", "")!!), prefs.getInt("${prefix}_channel", 0))
            .takeIf { it.error() == null }
    }.getOrNull()
}
