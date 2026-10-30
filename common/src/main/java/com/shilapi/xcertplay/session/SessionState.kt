package com.shilapi.xcertplay.session

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.media.SoftwareYuvConfig
import com.shilapi.xcertplay.media.VideoColorOverride
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.io.File

internal data class SessionUiCommand(val owner:Int,val ownerGeneration:Long,val stopGeneration:Long)
internal data class SessionIdentity(val id:String,val generation:Long)
internal enum class RuntimePhase { IDLE,WAITING_PREREQUISITES,STARTING,RUNNING,RECONNECTING,STOPPING,BLOCKED_RELEASE,FAILED }
internal data class CommandReceipt(val accepted:Boolean,val reason:String?=null)
internal data class StopResult(val released:Boolean,val reason:String?,val retainedResourceCount:Int)
internal data class SessionState(val identity:SessionIdentity?=null,val phase:RuntimePhase=RuntimePhase.IDLE,
    val protocolStatus:CarPlayStatus?=null,val failure:String?=null)
internal fun interface SessionObserver {fun changed(state:SessionState)}
internal fun interface SessionSubscription:java.io.Closeable {override fun close()}

/** Window-dependent inputs are values; no View, Activity or callback is part of a start request. */
internal data class SessionMediaOptions(
    val width:Int,val height:Int,val hevcSoftware:Boolean=false,val forceSoftware:Boolean=false,
    val softwareDirect:Boolean=false,val softwareYuv:SoftwareYuvConfig?=null,val hardwareYuv:Boolean=false,
    val color:VideoColorOverride=VideoColorOverride(),val advancedChannels:Boolean=false,
    val navigationStreamType:Int=14,val pacingDelayMs:Int=0,val frameRate:Int=0,
    val microphone:Boolean=false,val captureDirectory:File?=null,val direct:Boolean=false,val hardwareAccelerated:Boolean?=null,
)
internal data class SessionStartRequest(val runtime:CarPlayRuntimeConfig,val airPlay:AirPlayConfig,
    val identity:AirPlayIdentity,val media:SessionMediaOptions,
    val display:com.shilapi.xcertplay.CarPlaySessionDisplay?=null)
