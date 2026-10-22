package com.shilapi.xcertplay.network

import android.os.Build

/**
 * Identifies firmware that needs the voice-communication microphone source for Siri.
 * This audio-only compatibility check does not register steering-wheel input services.
 */
object GeelyHeadUnit {

    private val identifiers: List<String>
        get() = runCatching {
            listOf(Build.PRODUCT, Build.DEVICE, Build.MODEL, Build.MANUFACTURER, Build.BRAND)
                .map { it.lowercase() }
        }.getOrDefault(emptyList())

    /** Product code of the head unit. */
    private const val PRODUCT = "geely_fx11"

    /** Model name the head unit reports. */
    private const val MODEL = "cs11_a4"

    val detected: Boolean = identifiers.any { id ->
        id == PRODUCT || id == MODEL || id.contains("geely") || id.contains("lynk")
    }
}
