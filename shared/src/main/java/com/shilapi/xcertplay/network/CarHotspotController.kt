package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface CarHotspotOperations {
    fun read(): CarHotspotProfile?
    fun apply(profile: CarHotspotProfile, cancelled: () -> Boolean = { false }): CarHotspotControlResult
    fun enable(cancelled: () -> Boolean = { false }): CarHotspotControlResult
}

/** Explicit AP changes only. Never disables client Wi-Fi or alters the legacy manual credentials. */
class CarHotspotController(context: Context, private val log: (String) -> Unit = {}) : CarHotspotOperations {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val control = CarHotspotControl(listOfNotNull(
        wifi?.let(::ModernHotspotApi),
        wifi?.let(::LegacyHotspotApi),
        runCatching { EcarxHotspotApi(app) }.onFailure { log("ECarX Wifi6Ap:init ${it.javaClass.simpleName}") }.getOrNull(),
    ), log)

    override fun read(): CarHotspotProfile? = control.read()

    /** Run on a worker. All callers share the lock, including boot and UI requests. */
    override fun apply(profile: CarHotspotProfile, cancelled: () -> Boolean): CarHotspotControlResult = lock.withLock {
        control.apply(profile, cancelled).also { if (it.success) it.profile?.let { p -> CarHotspotProfiles.saveConfirmed(app, p) } }
    }

    override fun enable(cancelled: () -> Boolean): CarHotspotControlResult = lock.withLock {
        val direct = control.enable(cancelled)
        if (direct.success || cancelled()) return@withLock direct
        val fallback = CarHotspotTethering.enable(app, cancelled, timeoutMillis = 8_000, log = log)
        if (fallback == CarHotspotTethering.Result.READY) CarHotspotControlResult(true, true, read(), "Android tethering:running")
        else CarHotspotControlResult(false, null, null, "Android tethering:${fallback.name}")
    }

    companion object {
        private val lock = ReentrantLock()
        internal fun vendorEnabled(context: Context): Boolean? = runCatching { EcarxHotspotApi(context.applicationContext).enabled() }.getOrNull()
    }
}

private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
    val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size &&
        it.parameterTypes.indices.all { i -> args[i] == null || boxed(it.parameterTypes[i]).isInstance(args[i]) } }
        ?: throw NoSuchMethodException(name)
    return method.invoke(target, *args)
}
private fun boxed(type: Class<*>): Class<*> = when (type) {
    Integer.TYPE -> Integer::class.java
    java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
    else -> type
}
private fun accepted(value: Any?) = value == null || value == true
private fun ssid(value: String?): String? = value?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
private fun band(value: Int, modern: Boolean): ManualHotspotBand = when (value) {
    if (modern) 1 else 0 -> ManualHotspotBand.GHZ_2_4
    if (modern) 2 else 1 -> ManualHotspotBand.GHZ_5
    else -> ManualHotspotBand.AUTO
}
private fun profile(ssid: String?, password: String?, security: ManualHotspotSecurity, band: ManualHotspotBand, channel: Int): CarHotspotProfile? {
    val name = ssid(ssid) ?: return null
    if (security != ManualHotspotSecurity.OPEN && (password == null || password == "*")) return null
    return CarHotspotProfile(name, password.orEmpty(), security, band, channel).takeIf { it.error() == null }
}

private class ModernHotspotApi(private val wifi: WifiManager) : CarHotspotApi {
    override val name = "Android SoftApConfiguration"
    private fun configuration() = invoke(wifi, "getSoftApConfiguration") ?: error("no-config")
    override fun read(): CarHotspotProfile? {
        val config = configuration()
        val security = when (invoke(config, "getSecurityType") as? Int) {
            0 -> ManualHotspotSecurity.OPEN; 1 -> ManualHotspotSecurity.WPA2
            2 -> ManualHotspotSecurity.WPA3_TRANSITION; 3 -> ManualHotspotSecurity.WPA3
            else -> return null
        }
        val radioBand = invoke(config, "getBand") as? Int ?: return null
        if (radioBand !in 1..3) return null // 6 GHz/60 GHz cannot be represented by the current CarPlay radio model.
        return profile(invoke(config, "getSsid") as? String, invoke(config, "getPassphrase") as? String, security,
            band(radioBand, true), (invoke(config, "getChannel") as? Int) ?: 0)
    }
    override fun enabled() = runCatching { invoke(wifi, "isWifiApEnabled") as? Boolean }.getOrNull()
    override fun supports(profile: CarHotspotProfile) = runCatching { Class.forName("android.net.wifi.SoftApConfiguration\$Builder") }.isSuccess
    override fun configure(profile: CarHotspotProfile): Boolean {
        val type = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val prior = runCatching { configuration() }.getOrNull()
        val builder = if (prior != null) runCatching { type.getConstructor(prior.javaClass).newInstance(prior) }.getOrNull()
            ?: type.getConstructor().newInstance() else type.getConstructor().newInstance()
        invoke(builder, "setSsid", profile.ssid)
        val security = when (profile.security) {
            ManualHotspotSecurity.OPEN -> 0; ManualHotspotSecurity.WPA2 -> 1
            ManualHotspotSecurity.WPA3_TRANSITION -> 2; ManualHotspotSecurity.WPA3 -> 3
        }
        invoke(builder, "setPassphrase", profile.passphrase.takeUnless { security == 0 }, security)
        val radioBand = when (profile.band) {
            ManualHotspotBand.GHZ_2_4 -> 1; ManualHotspotBand.GHZ_5 -> 2
            ManualHotspotBand.AUTO -> if (profile.channel > 14) 2 else if (profile.channel > 0) 1 else 3
        }
        if (profile.channel > 0) invoke(builder, "setChannel", profile.channel, radioBand)
        else invoke(builder, "setBand", radioBand)
        return invoke(wifi, "setSoftApConfiguration", invoke(builder, "build")) == true
    }
    override fun start(): Boolean {
        val config = configuration()
        return try { accepted(invoke(wifi, "startTetheredHotspot", config)) }
        catch (_: NoSuchMethodException) { accepted(invoke(wifi, "startSoftAp", config)) }
    }
    override fun stop() = accepted(invoke(wifi, "stopSoftAp"))
}

@Suppress("DEPRECATION")
private class LegacyHotspotApi(private val wifi: WifiManager) : CarHotspotApi {
    override val name = "Android WifiConfiguration"
    override fun read(): CarHotspotProfile? {
        val config = invoke(wifi, "getWifiApConfiguration") as? WifiConfiguration ?: return null
        val security = when {
            config.allowedKeyManagement[8] -> return null // SAE must use the modern API; do not mislabel it WPA2.
            config.allowedKeyManagement[4] || config.allowedKeyManagement[1] -> ManualHotspotSecurity.WPA2
            config.allowedKeyManagement[0] -> ManualHotspotSecurity.OPEN
            else -> return null
        }
        val radioBand = runCatching { config.javaClass.getField("apBand").getInt(config) }.getOrDefault(-1)
        val channel = runCatching { config.javaClass.getField("apChannel").getInt(config) }.getOrDefault(0)
        if (radioBand !in -1..1) return null
        return profile(config.SSID, config.preSharedKey?.removeSurrounding("\""), security, band(radioBand, false), channel)
    }
    override fun enabled() = runCatching { invoke(wifi, "isWifiApEnabled") as? Boolean }.getOrNull()
    override fun supports(profile: CarHotspotProfile) = profile.security in listOf(ManualHotspotSecurity.OPEN, ManualHotspotSecurity.WPA2)
    override fun configure(profile: CarHotspotProfile): Boolean {
        val config = WifiConfiguration().apply {
            SSID = profile.ssid
            preSharedKey = profile.passphrase.takeUnless { profile.security == ManualHotspotSecurity.OPEN }
            allowedKeyManagement.set(if (profile.security == ManualHotspotSecurity.OPEN) 0 else 4)
        }
        val selectedBand = when (profile.band) {
            ManualHotspotBand.GHZ_2_4 -> 0; ManualHotspotBand.GHZ_5 -> 1
            ManualHotspotBand.AUTO -> if (profile.channel > 14) 1 else if (profile.channel > 0) 0 else -1
        }
        config.javaClass.getField("apBand").setInt(config, selectedBand)
        config.javaClass.getField("apChannel").setInt(config, profile.channel)
        return invoke(wifi, "setWifiApConfiguration", config) == true
    }
    override fun start(): Boolean {
        val config = invoke(wifi, "getWifiApConfiguration")
        return try { accepted(invoke(wifi, "startSoftAp", config)) }
        catch (_: NoSuchMethodException) { accepted(invoke(wifi, "setWifiApEnabled", config, true)) }
    }
    override fun stop(): Boolean = try { accepted(invoke(wifi, "stopSoftAp")) }
        catch (_: NoSuchMethodException) { accepted(invoke(wifi, "setWifiApEnabled", null, false)) }
}

/** Same explicit Wifi6Ap entry points used by the reference APK. Unsupported fields never get dropped. */
private class EcarxHotspotApi(context: Context) : CarHotspotApi {
    override val name = "ECarX Wifi6Ap"
    private val ap = Class.forName("com.ecarx.xui.adaptapi.wifiap.Wifi6Ap")
        .getMethod("create", Context::class.java).invoke(null, context) ?: error("no-Wifi6Ap")
    private fun getter(vararg names: String): Any? {
        val name = names.firstOrNull { n -> ap.javaClass.methods.any { it.name == n && it.parameterCount == 0 } } ?: return null
        return invoke(ap, name)
    }
    private fun radioConstant(name: String): Int = Class.forName("com.ecarx.xui.adaptapi.wifiap.IWifiAPHost").getField(name).getInt(null)
    // This API does not expose a complete security configuration. Android must read back the
    // complete profile; never infer WPA2/WPA3 or reuse an unrelated password to claim success.
    override fun read(): CarHotspotProfile? = null
    override fun enabled() = getter("getWifi6ApEnabled") as? Boolean
    override fun supports(profile: CarHotspotProfile) = profile.channel == 0 && profile.security == ManualHotspotSecurity.WPA2
    override fun configure(profile: CarHotspotProfile): Boolean {
        if (!supports(profile)) return false
        // AUTO has no documented ECarX band constant; use the currently selected band rather than invent one.
        val band = when (profile.band) {
            ManualHotspotBand.GHZ_2_4 -> radioConstant("WIFIAP_FREQUENCY_2")
            ManualHotspotBand.GHZ_5 -> radioConstant("WIFIAP_FREQUENCY_5")
            ManualHotspotBand.AUTO -> getter("getWifi6ApFrequencyBand") as? Int ?: return false
        }
        return accepted(invoke(ap, "setWifi6ApConfiguration", profile.ssid, profile.passphrase, band, 8))
    }
    override fun start() = accepted(invoke(ap, "setWifi6ApEnabled", true))
    override fun stop() = accepted(invoke(ap, "setWifi6ApEnabled", false))
}
