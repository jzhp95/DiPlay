package com.shilapi.xcertplay.media.video

import android.media.MediaFormat

/** Decoder tags are observations; never substitute the source declaration for an absent tag. */
internal fun decoderFormatDescription(format: MediaFormat, component: String, layout: String): DecoderOutputDescription {
    fun value(key: String) = runCatching { if (format.containsKey(key)) format.getInteger(key) else null }.getOrNull()
    return DecoderOutputDescription(
        reportedRange = when (value("color-range")) { 1 -> ColorRange.FULL; 2 -> ColorRange.LIMITED; else -> ColorRange.UNKNOWN },
        reportedStandard = when (value("color-standard")) { 1 -> ColorMatrix.BT709; 2, 4 -> ColorMatrix.BT601; 6 -> ColorMatrix.BT2020; else -> ColorMatrix.UNKNOWN },
        reportedTransfer = when (value("color-transfer")) { 1 -> ColorTransfer.LINEAR; 3 -> ColorTransfer.BT709; else -> ColorTransfer.UNKNOWN },
        actualComponent = component, planeLayout = layout, reportAvailability = true)
}
