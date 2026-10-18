package com.shilapi.xcertplay

import android.content.Context
import android.os.Process

/** Reading an ordinary UID is successful evidence, but does not grant system identity. */
internal data class UidDiagnostic(val processUid: Int?, val installedUid: Int?, val packageName: String) {
    val appId: Int? get() = processUid?.takeIf { it >= 0 }?.rem(100_000)
    val isSystem: Boolean get() = appId == 1000
    val matchesInstallation: Boolean get() = processUid != null && processUid == installedUid

    companion object {
        fun read(context: Context) = UidDiagnostic(
            runCatching { Process.myUid().takeIf { it >= 0 } }.getOrNull(),
            runCatching { context.packageManager.getApplicationInfo(context.packageName, 0).uid.takeIf { it >= 0 } }.getOrNull(),
            context.packageName,
        )
    }
}
