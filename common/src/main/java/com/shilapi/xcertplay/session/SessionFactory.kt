package com.shilapi.xcertplay.session

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport

internal interface SessionConnection {
    val controller:CarPlayController
    val sink:AndroidMediaSink
    val display:com.shilapi.xcertplay.CarPlaySessionDisplay? get()=null
    fun start()
    fun close()
    fun released():Boolean
    fun awaitRelease():Boolean
}
internal fun interface SessionConnectionFactory {
    fun readiness(request:SessionStartRequest):String?=null
    fun create(request:SessionStartRequest,listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
        stream:(Int,Boolean)->Unit):SessionConnection
}

/** Resource construction never closes over a screen or a screen-owned logger. */
internal class SessionFactory(context:Context):SessionConnectionFactory {
    private val app=context.applicationContext
    override fun readiness(request:SessionStartRequest):String? {
        if(com.shilapi.xcertplay.diagnostics.DiagnosticLease.blocksProduction())return "diagnostic_active"
        val config=request.runtime
        if(config.transport==CarPlayTransport.WIRELESS && com.shilapi.xcertplay.WirelessPermissions.required(config.wirelessHotspotMode,android.os.Build.VERSION.SDK_INT)
            .any{app.checkSelfPermission(it)!=android.content.pm.PackageManager.PERMISSION_GRANTED})return "wireless_permission_required"
        if(config.transport==CarPlayTransport.WIRED){
            val consent=try{android.net.VpnService.prepare(app)}catch(error:RuntimeException){
                com.shilapi.xcertplay.DetailedDebugLog.record("session","VPN readiness unavailable failureClass=${error.javaClass.simpleName}")
                return "vpn_consent_required"
            }
            if(consent!=null)return "vpn_consent_required"
        }
        if(config.locationReportingEnabled && app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "location_permission_required"
        return null
    }
    override fun create(request:SessionStartRequest,listener:AirPlaySessionListener,status:(CarPlayStatus)->Unit,
        stream:(Int,Boolean)->Unit):SessionConnection {
        val options=request.media
        val microphone=options.microphone && app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val airPlay=request.airPlay.copy(microphone=request.airPlay.microphone && microphone)
        val plan=com.shilapi.xcertplay.media.VideoPlanResolver.resolve(com.shilapi.xcertplay.media.VideoPipelineRequest(
            codec=if(airPlay.hevc)com.shilapi.xcertplay.airplay.VideoCodec.H265 else com.shilapi.xcertplay.airplay.VideoCodec.H264,
            width=options.width,height=options.height,fps=options.frameRate,smooth=options.pacingDelayMs>0,
            direct=options.direct,softwareDirect=options.softwareDirect,
            forceSoftware=options.forceSoftware,softwareYuv=options.softwareYuv,hardwareYuv=options.hardwareYuv,
            color=options.color,pacingDelayMs=options.pacingDelayMs),
            com.shilapi.xcertplay.media.VideoCapabilities(hardwareAccelerated=options.hardwareAccelerated))
        val evidenceIdentity=com.shilapi.xcertplay.DiPlaySessionService.liveRuntime?.snapshot()?.identity
        val sink=AndroidMediaSink(videoPipelinePlan=plan,onVideoObservation={observation->
            com.shilapi.xcertplay.diagnostics.RuntimeEvidence.video.record(evidenceIdentity?.id,evidenceIdentity?.generation,
                "decoder_observation",observation.report())
        },surface=null,videoWidth=options.width,videoHeight=options.height,
            preferSoftwareHevcDecoder=options.hevcSoftware && !options.hardwareYuv,
            forceSoftwareVideoDecoder=options.forceSoftware,mainScreenSoftwareDecoder=plan.softwareDirect,
            mainScreenSoftwareYuv=plan.softwareYuv,mainScreenHardwareYuv=plan.hardwareYuv,
            videoColorOverride=options.color,advancedAudioChannelMapping=options.advancedChannels,
            nativeHfpCalls=AirPlayPersistence.loadNativeHfpCalls(app),
            audioFocusEnabled=AirPlayPersistence.loadAudioFocusEnabled(app),
            audioFocusAutoYield=AirPlayPersistence.loadAudioFocusAutoYield(app),
            mediaChannel=AirPlayPersistence.loadMediaAudioChannel(app),navigationChannel=AirPlayPersistence.loadNavigationAudioChannel(app),
            navigationAttenuationDb=AirPlayPersistence.loadNavigationAudioAttenuationDb(app),context=app,
            navigationStreamType=options.navigationStreamType,onScreenStreamActiveChanged=stream,
            mediaBufferMillis=AirPlayPersistence.loadMediaBufferMillis(app,wireless=request.runtime.transport==CarPlayTransport.WIRELESS),
            onAudioDiagnostic={com.shilapi.xcertplay.DetailedDebugLog.record("audio",it)},
            onMediaAudioChanged=com.shilapi.xcertplay.CarPlayMediaKeys::onMediaAudioChanged,
            callEchoCancellation=AirPlayPersistence.loadCallEchoCancellation(app),callVoiceFilter=AirPlayPersistence.loadCallVoiceFilter(app),
            experimentalAudio=AirPlayPersistence.loadExperimentalAudio(app),callSpeakerphone={AirPlayPersistence.loadCallSpeakerphone(app)},
            videoPacingDelayMillis=options.pacingDelayMs,mainVideoFrameRate=options.frameRate,
            vendorLowLatencyDecoder=AirPlayPersistence.loadLowLatencyDecoder(app),mtkDecoderTuningEnabled=AirPlayPersistence.loadMtkDecoderTuning(app))
        try {
            val controller=CarPlayController(context=app,config=request.runtime,airPlayConfig=airPlay,
                identity=request.identity,pairings=AirPlayPersistence.loadPairings(app){id,key->AirPlayPersistence.savePairing(app,id,key)},
                listener=object:AirPlaySessionListener by listener {
                    override fun onDebugLog(message:String){
                        com.shilapi.xcertplay.DetailedDebugLog.record("session",message)
                        listener.onDebugLog(message)
                    }
                },reportStatus=status,
                media=CarPlayMediaEngine(sink,microphone,options.captureDirectory),
                loadPairRecord={AirPlayPersistence.loadLockdownRecord(app)},savePairRecord={AirPlayPersistence.saveLockdownRecord(app,it)},
                clearPairRecord={AirPlayPersistence.clearLockdownRecord(app)},
                locationProvider=if(request.runtime.locationReportingEnabled)AndroidCarPlayLocationProvider(app)else null)
            controller.inputDiagnostic={metrics->
                com.shilapi.xcertplay.diagnostics.RuntimeEvidence.sessions.record(evidenceIdentity?.id,evidenceIdentity?.generation,"input_command_timing",
                    mapOf("pending" to metrics.pending,"coalescedMoves" to metrics.coalescedMoves,"staleDropped" to metrics.staleDropped,
                        "overflow" to metrics.overflow,"queueWaitNs" to metrics.queueWaitNs,"sendNs" to metrics.sendNs,"oldestAgeNs" to metrics.oldestAgeNs,
                        "written" to metrics.written,"accepted" to metrics.accepted,
                        "queueLockWaitNs" to metrics.queueLockWaitNs,"actionDurationNs" to metrics.actionDurationNs,
                        "lockWaitNs" to metrics.lockWaitNs,"writeAndFlushNs" to metrics.writeAndFlushNs,
                        "phoneAcknowledged" to metrics.phoneAcknowledged,"rejectReason" to metrics.rejectReason))
            }
            return object:SessionConnection {
                override val controller=controller
                override val sink=sink
                override val display=request.display
                private val mirrors:(String,android.view.Surface?)->Unit={key,surface->sink.setMirrorSurface(111,key,surface)}
                override fun start(){
                    com.shilapi.xcertplay.CarPlayMediaKeys.attach(app,controller)
                    if(request.airPlay.videoInCar)com.shilapi.xcertplay.CarPlayVideo.attach(app,controller)
                    com.shilapi.xcertplay.MapMirrors.sink=mirrors
                    com.shilapi.xcertplay.MapMirrors.reapply()
                    controller.start()
                }
                override fun close(){
                    com.shilapi.xcertplay.CarPlayMediaKeys.detach(controller)
                    com.shilapi.xcertplay.CarPlayVideo.detach(controller)
                    if(com.shilapi.xcertplay.MapMirrors.sink===mirrors)com.shilapi.xcertplay.MapMirrors.sink=null
                    try{controller.close()}finally{sink.close()}
                }
                override fun released()=controller.awaitClosed(0) && sink.awaitVideoReleased(0) && sink.audioReleased()
                override fun awaitRelease():Boolean {
                    val protocol=controller.awaitClosed(4000)
                    val video=sink.awaitVideoReleased(2000)
                    return protocol && video && sink.audioReleased()
                }
            }
        } catch(error:Exception){sink.close();throw error}
    }
}
