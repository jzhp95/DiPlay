package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File

/** Opt-in, process-wide event log; separate from the on-screen log and session log lifetime. */
internal object DetailedDebugLog {
    private const val PREFS = "diplay_debug_recording"
    @Volatile private var recorder: DetailedDebugRecorder? = null
    val recording: Boolean get() = recorder?.recording == true

    @Synchronized fun initialize(context: Context) {
        if (recorder != null) return
        recorder = DetailedDebugRecorder(File(context.filesDir, "detailed-debug"), "${Process.myPid()}").also {
            it.setEnabled(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false))
        }
        record("process", "started uid=${Process.myUid()} api=${Build.VERSION.SDK_INT} package=${context.packageName}")
        if (recorder?.recording == true) recordIdentity(context)
    }

    fun enabled(context: Context): Boolean {
        initialize(context)
        return recorder?.recording == true
    }

    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        recorder?.setEnabled(enabled)
        if (enabled) {
            record("process", "capture started uid=${Process.myUid()} api=${Build.VERSION.SDK_INT} package=${context.packageName}")
            recordIdentity(context)
        }
    }

    fun record(category: String, message: String) {
        // Nothing touches disk on the connection, Binder, audio or UI callback thread.
        if (recorder?.recording != true) return
        runCatching { recorder?.record(category, "uptimeMs=${SystemClock.elapsedRealtime()} $message") }
    }

    fun report(context: Context): String {
        initialize(context)
        return recorder?.report().orEmpty()
    }

    private fun recordIdentity(context: Context) {
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            val platform = pm.checkSignatures("android", context.packageName) == PackageManager.SIGNATURE_MATCH
            val oem = context.checkSelfPermission("ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION") == PackageManager.PERMISSION_GRANTED
            record("identity", "version=${info.versionName} installedAt=${info.lastUpdateTime} platformSigned=$platform oemPermission=$oem")
            record("system", "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} build=${Build.DISPLAY}")
        }.onFailure { record("identity", "unavailable type=${it.javaClass.simpleName}") }
    }
}
