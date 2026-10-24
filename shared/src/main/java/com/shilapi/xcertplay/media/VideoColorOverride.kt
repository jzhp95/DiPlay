package com.shilapi.xcertplay.media

import android.media.MediaFormat

/** Explicit diagnostic hints. Empty by default; normal sessions retain the stream's metadata. */
data class VideoColorOverride(val range: Int? = null, val standard: Int? = null) {
    init {
        require(range == null || range in 1..2)
        require(standard == null || standard in listOf(1, 2, 4, 6))
    }
    fun applyTo(format: MediaFormat) {
        range?.let { format.setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
        standard?.let { format.setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
    }
}
