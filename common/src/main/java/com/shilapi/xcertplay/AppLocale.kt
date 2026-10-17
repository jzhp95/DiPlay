// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** The receiver UI always uses Simplified Chinese, independently of the head unit's language. */
object AppLocale {
    const val SIMPLIFIED_CHINESE = "zh"

    @Suppress("UNUSED_PARAMETER")
    fun preference(context: Context): String = SIMPLIFIED_CHINESE

    fun wrap(context: Context): Context {
        // Old installations may carry a language chosen in the former picker or restored backup.
        val prefs = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        if (prefs.contains("app_language")) prefs.edit().remove("app_language").apply()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
            setLayoutDirection(Locale.SIMPLIFIED_CHINESE)
        }
        return context.createConfigurationContext(configuration)
    }

    /** Reapply after a head unit replaces the activity configuration, including Android 13+. */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        val resources = context.resources
        if (resources.configuration.locales[0] == Locale.SIMPLIFIED_CHINESE) return false
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
            setLayoutDirection(Locale.SIMPLIFIED_CHINESE)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return true
    }
}
