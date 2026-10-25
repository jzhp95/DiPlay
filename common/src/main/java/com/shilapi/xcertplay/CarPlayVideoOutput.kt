package com.shilapi.xcertplay

/** A session override; the driver's previous output choices stay saved for when it is disabled. */
internal data class CarPlayVideoOutput(val smooth: Boolean, val direct: Boolean, val software: Boolean)

internal fun carPlayVideoOutput(softwareDirect: Boolean, smooth: Boolean, direct: Boolean) =
    if (softwareDirect) CarPlayVideoOutput(smooth = false, direct = true, software = true)
    else CarPlayVideoOutput(smooth, direct, software = false)
