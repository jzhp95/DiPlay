package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.SoftwareYuvConfig

/** Session overrides preserve previous choices for when each experimental mode is disabled. */
internal data class CarPlayVideoOutput(val smooth: Boolean, val direct: Boolean, val software: Boolean,
    val yuv: SoftwareYuvConfig? = null, val hardwareYuv: Boolean = false)

internal fun carPlayVideoOutput(softwareDirect:Boolean,smooth:Boolean,direct:Boolean,
    yuv:SoftwareYuvConfig?=null,hardwareYuv:Boolean=false):CarPlayVideoOutput {
    val plan=com.shilapi.xcertplay.media.VideoPlanResolver.resolve(com.shilapi.xcertplay.media.VideoPipelineRequest(
        softwareDirect=softwareDirect,smooth=smooth,direct=direct,softwareYuv=yuv,hardwareYuv=hardwareYuv))
    return CarPlayVideoOutput(plan.smooth,plan.direct,plan.softwareDirect,plan.softwareYuv,plan.hardwareYuv)
}
