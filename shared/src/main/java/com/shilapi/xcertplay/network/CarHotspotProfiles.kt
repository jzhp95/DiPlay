package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity

/** One saved user configuration; old drafts and observed system profiles remain separate. */
object CarHotspotProfiles {
    private val savedLock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("diplay_managed_hotspot", Context.MODE_PRIVATE)

    fun saved(context: Context): CarHotspotProfile? = synchronized(savedLock) { load(context, "saved") }
    fun saveSaved(context: Context, profile: CarHotspotProfile) = synchronized(savedLock) { save(context, "saved", profile) }

    /** A completed system operation must not undo a newer edit saved while it was running. */
    internal fun saveSavedIfUnchanged(context: Context, expected: CarHotspotProfile?, profile: CarHotspotProfile) = synchronized(savedLock) {
        if (load(context, "saved") == expected) save(context, "saved", profile)
    }

    /** Compatibility setters cannot replace a usable configuration with an unfinished field edit. */
    fun updateSavedIfValid(context: Context, update: (CarHotspotProfile) -> CarHotspotProfile): Boolean = synchronized(savedLock) {
        val current = load(context, "saved") ?: return@synchronized false
        val updated = update(current)
        if (updated.error() != null) return@synchronized false
        save(context, "saved", updated)
        true
    }

    fun draft(context: Context): CarHotspotProfile? = load(context, "draft")
    fun confirmed(context: Context): CarHotspotProfile? = load(context, "confirmed")
    fun saveDraft(context: Context, profile: CarHotspotProfile) = save(context, "draft", profile)
    fun saveConfirmed(context: Context, profile: CarHotspotProfile) = save(context, "confirmed", profile)

    fun resolve(context: Context, manual: () -> CarHotspotProfile?, log: (String) -> Unit = {}): ResolvedCarHotspotProfile? {
        saved(context)?.let {
            log("hotspot configuration source=SAVED")
            return ResolvedCarHotspotProfile(it, CarHotspotProfileSource.SAVED)
        }
        // Firmware reads may block. Never hold the save lock while importing old configuration.
        val imported = CarHotspotProfileSelection.resolve(
            system = { CarHotspotController(context, log).read()?.also { saveConfirmed(context, it) } },
            confirmed = { confirmed(context) }, manual = manual,
        )
        val result = synchronized(savedLock) {
            // A user save made during the slow read always wins, including when import found nothing.
            load(context, "saved")?.let { ResolvedCarHotspotProfile(it, CarHotspotProfileSource.SAVED) }
                ?: imported?.also { save(context, "saved", it.profile) }
        }
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
        // Read one preference snapshot so concurrent complete writes cannot produce a mixed profile.
        val values = prefs(context).all
        val ssid = values["${prefix}_ssid"] as? String ?: return null
        CarHotspotProfile(ssid, values["${prefix}_password"] as? String ?: return null,
            ManualHotspotSecurity.valueOf(values["${prefix}_security"] as? String ?: return null),
            ManualHotspotBand.valueOf(values["${prefix}_band"] as? String ?: return null),
            values["${prefix}_channel"] as? Int ?: 0).takeIf { it.error() == null }
    }.getOrNull()
}
