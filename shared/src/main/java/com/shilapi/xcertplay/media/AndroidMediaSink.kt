package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.media.video.*
import com.shilapi.xcertplay.media.AudioFocusRequestHandle
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** Protocol-facing media facade. Video and audio resources each have one owner. */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val audioFocusAutoYield: Boolean = true,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    navigationAttenuationDb: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    /** Opt-in call echo cancellation, replacing a controllable platform canceller while available. */
    private val callEchoCancellation: Boolean = false,
    /** Cut the bass that head units add when they play a call as music. */
    private val callVoiceFilter: Boolean = false,
    /** Read at each call: a phone or tablet used as the head unit plays calls on its loudspeaker (#701). */
    private val callSpeakerphone: () -> Boolean = { false },
    /**
     * Smooth video: show main-screen frames at the iPhone's frame time plus a delay that starts here and
     * then follows how late the decoder releases frames ([PacingDelay]); 0 shows each as soon as it is
     * decoded. Only a SurfaceView honours the timestamps, so the host sets it with one.
     */
    private val videoPacingDelayMillis: Int = 0,
    /** The main screen's frame rate (the frame-rate setting), requested from its decoder as the operating rate; 0 for none. */
    private val mainVideoFrameRate: Int = 0,
    /**
     * Low-latency decoding (a setting): ask Qualcomm decoders to output each frame as soon as it is decoded,
     * and hand decoded frames to the screen from a thread of their own.
     */
    private val vendorLowLatencyDecoder: Boolean = false,
    /** Opt-in MTK ACodec low-latency/no-reorder modes, with per-session automatic fallback. */
    private val mtkDecoderTuningEnabled: Boolean = false,
    private val videoColorOverride: VideoColorOverride = VideoColorOverride(),
    private val forceSoftwareVideoDecoder: Boolean = false,
    private val nativeHfpCalls: Boolean = false,
    /** Daily colour compatibility: strict software decoding for the main screen, excluding mirrors. */
    mainScreenSoftwareDecoder: Boolean = false,
    /** CPU-readable YUV, converted by the app only for the main non-mirror decoder. */
    val mainScreenSoftwareYuv: SoftwareYuvConfig? = null,
    val mainScreenHardwareYuv: Boolean = false,
    val experimentalAudio: ExperimentalAudioConfig = ExperimentalAudioConfig(),
    val videoPipelinePlan:VideoPipelinePlan?=null,
    onVideoObservation:(VideoOutputObservation)->Unit={},
    val videoImplementation:VideoImplementation=VideoImplementation.LEGACY,
    independentPolicy:VideoRuntimePolicy=VideoRuntimePolicy(),
    videoSessionId:String="independent-session",
    private val onVideoRuntimeDiagnostic:(String)->Unit={},
    independentEndpointFactory:((VideoRuntimePolicy)->IndependentVideoEndpoint)?=null,
) : MediaSink {
    private val closed=AtomicBoolean(false)
    private val independent=if(videoImplementation==VideoImplementation.INDEPENDENT)
        independentEndpointFactory?.invoke(independentPolicy) ?: IndependentVideoEndpoint(sessionId=videoSessionId,
            policy=independentPolicy,diagnostic={message->onVideoRuntimeDiagnostic(message);independentDiagnostic?.invoke(message)}) else null
    @Volatile private var independentDiagnostic:((String)->Unit)?=null
    @Volatile private var screenListener=onScreenStreamActiveChanged
    init { if(surface!=null)independent?.attachSurface(surface) }
    private fun independentMain(type:Int)=type==110 && independent!=null
    fun videoRuntimeSnapshot()=independent?.snapshot()
    internal val video=AndroidVideoPipeline(surface,videoWidth,videoHeight,preferSoftwareHevcDecoder,videoPacingDelayMillis,
        mainVideoFrameRate,vendorLowLatencyDecoder,mtkDecoderTuningEnabled,videoColorOverride,forceSoftwareVideoDecoder,
        mainScreenSoftwareDecoder,mainScreenSoftwareYuv,mainScreenHardwareYuv,onScreenStreamActiveChanged,videoPipelinePlan,onVideoObservation)
    internal val audio=AndroidAudioPipeline(context,advancedAudioChannelMapping,audioFocusEnabled,audioFocusAutoYield,
        mediaChannel,navigationChannel,navigationAttenuationDb,navigationStreamType,mediaBufferMillis,onAudioDiagnostic,
        onMediaAudioChanged,callEchoCancellation,callVoiceFilter,callSpeakerphone,nativeHfpCalls,experimentalAudio)
    val mainScreenSoftwareDecoder get()=video.mainScreenSoftwareDecoder
    val videoPacingEnabled get()=independent==null && video.videoPacingEnabled
    val mainVideoLowLatencyEnabled get()=independent==null && video.mainVideoLowLatencyEnabled
    val mainVideoMtkTuningEnabled get()=independent==null && video.mainVideoMtkTuningEnabled
    internal var detachTimeoutNanos:Long
        get()=video.detachTimeoutNanos
        set(value){video.detachTimeoutNanos=value}
    internal var detachGraceNanos:Long
        get()=video.detachGraceNanos
        set(value){video.detachGraceNanos=value}
    var diagnosticCapture:VideoDiagnosticCapture?
        get()=video.diagnosticCapture
        set(value){video.diagnosticCapture=value}
    fun resourceOwnershipCounts():Map<String,Int> = video.resourceCounts()+audio.resourceCounts()+mapOf(
        "independentVideoRuntimes" to if(independent!=null && independent.snapshot().releaseState!=ReleaseState.RELEASED)1 else 0)
    fun videoOutputObservation(type:Int=110)=if(independentMain(type)) {
        val actual=independent?.outputDescription()
        VideoOutputObservation(null,actual?.actualComponent,null,emptyMap(),independent?.snapshot()?.decoderGeneration,
            output=independent?.snapshot()?.actualBackend?.name)
    } else video.videoOutputObservation(type)
    fun videoColorOutput(type:Int=110)=if(independentMain(type))null else video.videoColorOutput(type)
    fun videoColorEvidence(type:Int=110)=if(independentMain(type))null else video.videoColorEvidence(type)
    fun videoCadenceSnapshot(type: Int = 110): Map<String, Any?> = if(independentMain(type)) independent!!.snapshot().let {
        mapOf("requestedImplementation" to it.requestedImplementation.name,"actualImplementation" to it.actualImplementation.name,
            "actualBackend" to it.actualBackend?.name,"lastError" to it.lastError,"inputQueueDepth" to it.inputQueueDepth,
            "physicalTouchLatency" to "unknown","colorVerified" to false)
    } else video.videoCadenceSnapshot(type)
    fun recentBacklogEpisodes(type: Int = 110): List<BacklogEpisode> = video.recentBacklogEpisodes(type)
    fun latestRawYuvSamples(type: Int = 110): List<Yuv420Frame.RawSample> = video.latestRawYuvSamples(type)
    fun configureDiagnosticSampling(
        points: List<Pair<Int, Int>>,
        patchIndices: List<Int?> = emptyList(),
        nominals: List<Int?> = emptyList(),
        frameId: String? = null,
        instanceId: String? = null,
    ) = video.configureDiagnosticSampling(110, points, patchIndices, nominals, frameId, instanceId)
    fun setSurface(type:Int,surface:Surface){if(independentMain(type))independent?.attachSurface(surface) else video.setSurface(type,surface)}
    fun clearSurface(type:Int,surface:Surface){if(independentMain(type))independent?.detachSurface(surface) else video.clearSurface(type,surface)}
    class SurfaceDetach internal constructor(private val delegate:AndroidVideoPipeline.SurfaceDetach,private val independent:ReleaseTicket?=null){
        internal val requests get()=delegate.requests
        fun await():Boolean {
            val legacy=delegate.await()
            val main=independent?.awaitUntil(System.nanoTime()+2_000_000_000L)?.let{it==ReleaseState.RELEASED} ?: true
            return legacy && main
        }
    }
    fun beginSurfaceDetach(surface:Surface,parkMain:Boolean)=SurfaceDetach(video.beginSurfaceDetach(surface,parkMain),independent?.detachSurface(surface))
    fun awaitVideoReleased(timeoutMillis:Long):Boolean {
        val deadline=System.nanoTime()+timeoutMillis*1_000_000L
        val legacy=video.awaitVideoReleased(timeoutMillis)
        return (independent?.awaitReleased(((deadline-System.nanoTime())/1_000_000L).coerceAtLeast(0)) ?: true) && legacy
    }
    fun whenVideoReleased(onReleased:()->Unit){video.whenVideoReleased{if(independent==null)onReleased()else independent.whenReleased(onReleased)}}
    fun liveVideoCounters()=video.liveVideoCounters()
    fun refreshPicture(type:Int){if(independentMain(type))independent?.refreshPicture()else video.refreshPicture(type)}
    fun setMirrorSurface(type:Int,key:String,surface:Surface?,color:VideoColorOverride=VideoColorOverride(),diagnostic:((String)->Unit)?=null)=
        if(independentMain(type)){diagnostic?.invoke("Independent main-screen mirrors unavailable")}else video.setMirrorSurface(type,key,surface,color,diagnostic)
    fun detachMirrorAndAwait(type:Int,key:String,timeoutMillis:Long)=video.detachMirrorAndAwait(type,key,timeoutMillis)
    fun setScreenStreamActiveChangedListener(listener:((Int,Boolean)->Unit)?){screenListener=listener;video.setScreenStreamActiveChangedListener(listener)}
    fun suspendVideoForDiagnostics(owner:String,cancelled:()->Boolean={false})=if(independent!=null)false else video.suspendVideoForDiagnostics(owner,cancelled)
    fun resumeVideoAfterDiagnostics(owner:String)=video.resumeVideoAfterDiagnostics(owner)
    fun diagnosticLeaseReleased(owner:String)=video.diagnosticLeaseReleased(owner)
    fun diagnosticsVideoSuspended()=video.diagnosticsVideoSuspended()
    fun requestDiagnosticKeyFrame(){if(independent!=null)independent.refreshPicture()else video.requestDiagnosticKeyFrame()}
    fun finishInputForDiagnostics(){check(independent==null){"Independent runtime diagnostic input is unavailable"};video.finishInputForDiagnostics()}
    fun freezeFirstOutputForDiagnostics(){check(independent==null){"Independent runtime diagnostic capture is unavailable"};video.freezeFirstOutputForDiagnostics()}
    fun audioReleased():Boolean=audio.released()
    fun onMediaAudioFocusChanged(change:Int)=audio.onMediaAudioFocusChanged(change)
    fun setNavigationAttenuationDb(db:Int)=audio.setNavigationAttenuationDb(db)
    override fun setVideoRecoveryHandler(type:Int,handler:()->Unit){if(independentMain(type))independent?.setRecoveryHandler(handler)else video.setVideoRecoveryHandler(type,handler)}
    override fun setVideoDiagnosticHandler(type:Int,handler:(String)->Unit){if(independentMain(type))independentDiagnostic=handler else video.setVideoDiagnosticHandler(type,handler)}
    override fun onVideoCodec(type:Int,codec:VideoCodec){if(independentMain(type))independent?.onCodec(codec.ordinal)else video.onVideoCodec(type,codec)}
    override fun onVideoConfig(type:Int,codecData:ByteArray){if(independentMain(type))independent?.onConfig(codecData)else video.onVideoConfig(type,codecData)}
    override fun onVideoFrame(type:Int,naluBytes:ByteArray)=onVideoFrame(type,naluBytes,0L,System.nanoTime())
    override fun onVideoFrame(type:Int,naluBytes:ByteArray,senderNanos:Long,arrivalNanos:Long){
        if(independentMain(type))independent?.onFrame(naluBytes,senderNanos/1000L)else video.onVideoFrame(type,naluBytes,senderNanos,arrivalNanos)
    }
    override fun onScreenStreamActive(type:Int,active:Boolean){
        if(closed.get())return
        if(independentMain(type)){
            independent?.onStreamActive(active)
            if(!active)independentDiagnostic=null
            screenListener?.invoke(type,active)
        }else video.onScreenStreamActive(type,active)
    }
    override fun onAudioStarted(id:AudioStreamId,format:AudioFormat,firstSample:Int){if(!closed.get())audio.onAudioStarted(id,format,firstSample)}
    override fun onAudioRtp(id:AudioStreamId,format:AudioFormat,rtp:ByteArray,sample:Int){if(!closed.get())audio.onAudioRtp(id,format,rtp,sample)}
    override fun onAudioStopped(id:AudioStreamId)=audio.onAudioStopped(id)
    override fun onMicrophoneStarted(id:AudioStreamId,config:MicrophoneConfig){if(!closed.get())audio.onMicrophoneStarted(id,config)}
    override fun onMicrophoneStopped(id:AudioStreamId)=audio.onMicrophoneStopped(id)
    fun close(){if(closed.compareAndSet(false,true)){audio.prepareClose();try{independent?.close();video.close()}finally{audio.close()}}}
}
