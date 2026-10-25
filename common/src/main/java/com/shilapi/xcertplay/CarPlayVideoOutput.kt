package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.SoftwareYuvConfig

/** Session overrides preserve previous choices for when each experimental mode is disabled. */
internal data class CarPlayVideoOutput(val smooth: Boolean, val direct: Boolean, val software: Boolean,
    val yuv: SoftwareYuvConfig? = null)

internal fun carPlayVideoOutput(softwareDirect: Boolean, smooth: Boolean, direct: Boolean,
    yuv: SoftwareYuvConfig? = null) =
    if (softwareDirect || yuv != null) CarPlayVideoOutput(smooth = false, direct = true, software = true, yuv = yuv)
    else CarPlayVideoOutput(smooth, direct, software = false)
