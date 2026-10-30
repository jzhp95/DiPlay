package com.shilapi.xcertplay.media

import java.security.MessageDigest

/** Pure policy. It never queries Android capabilities, views or preferences. */
object VideoPlanResolver {
    fun surface(hardwareAccelerated:Boolean,smooth:Boolean,direct:Boolean,textureUnsafe:Boolean):VideoOutput =
        if(hardwareAccelerated && !smooth && !direct && !textureUnsafe)VideoOutput.TEXTURE else VideoOutput.SURFACE
    fun resolve(request:VideoPipelineRequest,capabilities:VideoCapabilities=VideoCapabilities()):VideoPipelinePlan {
        require(request.width>0 && request.height>0 && request.fps>=0)
        val hardware=request.hardwareYuv
        val softwareDirect=!hardware && (request.softwareDirect || request.softwareYuv!=null)
        val smooth=!hardware && !softwareDirect && request.smooth
        val direct=hardware || softwareDirect || request.direct
        val yuv=request.softwareYuv.takeUnless{hardware}
        val decode=when{hardware->VideoDecodePolicy.HARDWARE_YUV
            softwareDirect || request.forceSoftware->VideoDecodePolicy.STRICT_SOFTWARE
            else->VideoDecodePolicy.SYSTEM_DEFAULT}
        val output=if(hardware || yuv!=null)VideoOutput.APP_YUV else
            surface(capabilities.hardwareAccelerated!=false,smooth,direct,capabilities.textureUnsafe)
        val supported=when(decode){
            VideoDecodePolicy.STRICT_SOFTWARE->capabilities.softwareAvailable
            VideoDecodePolicy.HARDWARE_YUV->capabilities.hardwareYuvAvailable
            VideoDecodePolicy.SYSTEM_DEFAULT->null
        }
        val reason=when{
            supported==false->"requested_decoder_path_unavailable"
            output==VideoOutput.SURFACE && capabilities.textureUnsafe->"texture_unsafe_decoder"
            output==VideoOutput.SURFACE && capabilities.hardwareAccelerated==false->"window_without_hardware_acceleration"
            else->null}
        val canonical=listOf(request.codec,request.width,request.height,request.fps,request.smooth,request.direct,
            request.softwareDirect,request.forceSoftware,request.softwareYuv,request.hardwareYuv,request.purpose,
            request.color,request.pacingDelayMs,capabilities,output,decode,supported,reason).joinToString("|")
        val id=MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
        return VideoPipelinePlan(id,request,output,decode,smooth,direct,softwareDirect,yuv,hardware,supported,reason)
    }
}
