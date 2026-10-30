package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

enum class VideoPurpose { LIVE,DIAGNOSTIC }
enum class VideoOutput { TEXTURE,SURFACE,APP_YUV }
enum class VideoDecodePolicy { SYSTEM_DEFAULT,STRICT_SOFTWARE,HARDWARE_YUV }
data class VideoPipelineRequest(val codec:VideoCodec=VideoCodec.H264,val width:Int=1280,val height:Int=720,val fps:Int=0,
    val smooth:Boolean=false,val direct:Boolean=false,val softwareDirect:Boolean=false,val forceSoftware:Boolean=false,
    val softwareYuv:SoftwareYuvConfig?=null,val hardwareYuv:Boolean=false,val purpose:VideoPurpose=VideoPurpose.LIVE,
    val color:VideoColorOverride=VideoColorOverride(),val pacingDelayMs:Int=0)
data class VideoCapabilities(val hardwareAccelerated:Boolean?=null,val textureUnsafe:Boolean=false,
    val softwareAvailable:Boolean?=null,val hardwareYuvAvailable:Boolean?=null)
data class VideoPipelinePlan(val planId:String,val request:VideoPipelineRequest,val output:VideoOutput,
    val decode:VideoDecodePolicy,val smooth:Boolean,val direct:Boolean,val softwareDirect:Boolean,
    val softwareYuv:SoftwareYuvConfig?,val hardwareYuv:Boolean,val supported:Boolean?,val reason:String?,
    val schemaVersion:Int=1) {
    fun report():Map<String,Any?> = linkedMapOf("planId" to planId,"schemaVersion" to schemaVersion,
        "purpose" to request.purpose.name,"codec" to request.codec.name,"width" to request.width,"height" to request.height,
        "fps" to request.fps,"output" to output.name,"decodePolicy" to decode.name,"supported" to supported,
        "reason" to reason,"smooth" to smooth,"direct" to direct,"softwareDirect" to softwareDirect,
        "hardwareYuv" to hardwareYuv,"softwareYuv" to softwareYuv?.toString(),"pacingDelayMs" to if(smooth)request.pacingDelayMs else 0,
        "colorRequest" to request.color.toString(),"hardwareDecoderConfirmed" to "unknown_until_observed")
}
/** Actual decoder evidence is independent of the requested plan; missing values stay unknown. */
data class VideoOutputObservation(val planId:String?,val decoder:String?,val software:Boolean?,
    val format:Map<String,Int?>,val generation:Long?,val output:String?=null,val fallbackReason:String?=null) {
    fun report():Map<String,Any?> = linkedMapOf("planId" to planId,"decoder" to decoder,"software" to software,
        "format" to format,"generation" to generation,"output" to (output?:"unknown"),"fallbackReason" to fallbackReason,
        "hardwareConfirmed" to if(software==true)false else "unknown")
}
