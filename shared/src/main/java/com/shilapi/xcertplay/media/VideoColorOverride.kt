package com.shilapi.xcertplay.media

import android.media.MediaFormat

/** Explicit diagnostic hints. Empty by default; normal sessions retain the stream's metadata. */
data class VideoColorOverride(val range: Int? = null, val standard: Int? = null, val fromSps: Boolean = false) {
    init {
        require(range == null || range in 1..2)
        require(standard == null || standard in listOf(1, 2, 4, 6))
    }
    fun applyTo(format: MediaFormat, source: VideoColorMetadata.Description? = null) {
        if (fromSps && source != null) VideoColorMetadata.androidHints(source).forEach { (key, value) ->
            format.setInteger(key, value)
        }
        range?.let { format.setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
        standard?.let { format.setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
    }
}
