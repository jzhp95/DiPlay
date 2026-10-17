package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import org.robolectric.RuntimeEnvironment

internal fun setSystemNight(context: Context, night: Boolean) {
    val resources = listOf(context.resources, RuntimeEnvironment.getApplication().resources).distinct()
    for (res in resources) {
        val config = Configuration(res.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }
}
