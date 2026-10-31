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

internal class AndroidVideoPipeline(
    surface:Surface?=null,private val videoWidth:Int,private val videoHeight:Int,
    private val preferSoftwareHevcDecoder:Boolean,private val videoPacingDelayMillis:Int,
    private val mainVideoFrameRate:Int,private val vendorLowLatencyDecoder:Boolean,
    private val mtkDecoderTuningEnabled:Boolean,private val videoColorOverride:VideoColorOverride,
    private val forceSoftwareVideoDecoder:Boolean,mainScreenSoftwareDecoder:Boolean,
    val mainScreenSoftwareYuv:SoftwareYuvConfig?,val mainScreenHardwareYuv:Boolean,
    onScreenStreamActiveChanged:((Int,Boolean)->Unit)?,val videoPipelinePlan:VideoPipelinePlan?,
    private val onVideoObservation:(VideoOutputObservation)->Unit={},
):MediaSink {
    val mainScreenSoftwareDecoder=mainScreenSoftwareDecoder || mainScreenSoftwareYuv!=null
    // Concurrent registries only: evidence collection never waits for a codec ownership lock.
    fun resourceCounts():Map<String,Int> = mapOf(
        "primaryVideoDecoders" to videoDecoders.size,
        "retiringVideoDecoders" to closingVideoDecoders.size,
        "registeredPrimarySurfaces" to surfaces.size,
    )
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private var defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    // Surface capture, worker publication, retirement and detach snapshots share one ownership boundary.
    // Native codec work and waits never run under this lock.
    private val videoOwnershipLock = Any()
    private var videoClosed = false
    private val videoReleasedListeners = ArrayList<() -> Unit>()
    // Closed decoders whose workers may still hold a codec on a surface until they exit; a surface
    // detach also waits for them.
    private val closingVideoDecoders = ConcurrentHashMap.newKeySet<VideoDecoder>()
    // How long a surface detach waits for decoders to confirm, and then for an unconfirmed one to exit
    // after it is closed. Variables so tests can shorten them.
    internal var detachTimeoutNanos = 2_000_000_000L
    internal var detachGraceNanos = 500_000_000L
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private data class MirrorOptions(val color: VideoColorOverride, val diagnostic: ((String) -> Unit)?)
    private val mirrorOptions = HashMap<Pair<Int, String>, MirrorOptions>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    fun videoOutputObservation(type:Int=110):VideoOutputObservation {
        val actual=videoColorOutput(type)
        return VideoOutputObservation(videoPipelinePlan?.planId,actual?.decoder?.takeIf{it.isNotBlank()},actual?.software,
            actual?.format.orEmpty(),actual?.generation)
    }

    fun videoColorOutput(type: Int = 110): VideoDecoderColorEvidence? = synchronized(videoOwnershipLock) {
        val config = lastVideoConfig[type] ?: return@synchronized null
        videoDecoders[type]?.colorSnapshot()?.takeIf { it.configurationHash == videoConfigurationHash(config.second) }
    }
    fun videoColorEvidence(type: Int = 110): VideoSourceColorEvidence? {
        val captured = synchronized(videoOwnershipLock) {
            val config = lastVideoConfig[type] ?: return null
            Triple(config.first, config.second.copyOf(), videoDecoders[type]?.colorSnapshot())
        }
        val hash = videoConfigurationHash(captured.second)
        return VideoSourceColorEvidence(captured.first.name, captured.second,
            VideoColorMetadata.parse(captured.first, captured.second), captured.third?.takeIf { it.configurationHash == hash }, hash)
    }

    fun videoCadenceSnapshot(type: Int = 110): Map<String, Any?> =
        synchronized(videoOwnershipLock) { videoDecoders[type]?.cadenceSnapshot().orEmpty() }

    fun recentBacklogEpisodes(type: Int = 110): List<BacklogEpisode> =
        synchronized(videoOwnershipLock) { videoDecoders[type]?.recentBacklogEpisodes().orEmpty() }

    fun latestRawYuvSamples(type: Int = 110): List<Yuv420Frame.RawSample> =
        synchronized(videoOwnershipLock) { videoDecoders[type]?.latestRawSamples().orEmpty() }

    fun configureDiagnosticSampling(
        type: Int = 110,
        points: List<Pair<Int, Int>>,
        patchIndices: List<Int?> = emptyList(),
        nominals: List<Int?> = emptyList(),
        frameId: String? = null,
        instanceId: String? = null,
    ) {
        synchronized(videoOwnershipLock) {
            videoDecoders[type]?.configureDiagnosticSampling(points, patchIndices, nominals, frameId, instanceId)
        }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
        }
    }

    /**
     * Starts moving every decoder of this sink off [surface], which a SurfaceHolder destroy callback must
     * not return before. Each worker decides from the surface its codec actually renders to, after any
     * surface change queued before this: one already elsewhere has nothing to do. With [parkMain] the
     * main-screen decoder moves to an offscreen consumer it owns and keeps its state (smooth video, so the
     * picture can return at once); other decoders are released. Decoders closed earlier whose workers have
     * not exited yet are waited for too. [SurfaceDetach.await] returns the result.
     */
    fun beginSurfaceDetach(surface: Surface, parkMain: Boolean): SurfaceDetach {
        val deadline = System.nanoTime() + detachTimeoutNanos
        val requests = ArrayList<SurfaceDetachRequest>()
        val steps = ArrayList<() -> Boolean>()
        synchronized(videoOwnershipLock) {
            surfaces.entries.removeIf { it.value === surface } // future workers must not start on it
            if (defaultSurface === surface) defaultSurface = null
            synchronized(mirrorLock) {
                mirrorSurfaces.entries.filter { it.value === surface }.map { it.key }.forEach { id ->
                    mirrorSurfaces.remove(id)
                    mirrorOptions.remove(id)
                    mirrorDecoders[id]?.let { retire(id.first, it) }
                }
            }
            for ((type, decoder) in videoDecoders.entries.toList()) {
                val request = decoder.requestDetach(surface, park = parkMain && type == MAIN_SCREEN_TYPE)
                requests += request
                steps += { settleDetach(type, decoder, request, deadline) }
            }
            closingVideoDecoders.removeIf { it.exited }
            closingVideoDecoders.toList().forEach { closing -> steps += { closing.awaitExitUntil(deadline + detachGraceNanos) } }
        }
        return SurfaceDetach(requests, steps)
    }

    /** A surface detach in flight across one sink's decoders; see [beginSurfaceDetach]. */
    class SurfaceDetach internal constructor(
        internal val requests: List<SurfaceDetachRequest>,
        private val steps: List<() -> Boolean>,
    ) {
        /**
         * Waits until every decoder has let go of the surface (moved, parked, released, or its worker
         * exited). False when one did not confirm in time and its worker, closed then, did not exit within
         * the grace period either; nothing more can be done from here, as with any native call that hangs.
         */
        fun await(): Boolean = steps.map { it() }.all { it }
    }

    private fun settleDetach(type: Int, decoder: VideoDecoder, request: SurfaceDetachRequest, deadline: Long): Boolean {
        if (decoder.awaitDetach(request, deadline)) return true
        Log.w("xcertplay-usb", "Video detach not confirmed in time type=$type; closing that decoder")
        videoDiagnosticHandlers[type]?.invoke("surface detach not confirmed; decoder closed")
        // The stream's next frame starts a fresh decoder on no surface, so it cannot compete for this one.
        retire(type, decoder)
        return decoder.awaitExitUntil(deadline + detachGraceNanos)
    }

    /** Closes a stream's decoder and keeps it visible to surface detaches until its worker exits. */
    private fun retire(type: Int, decoder: VideoDecoder) {
        synchronized(videoOwnershipLock) {
            // A timed-out detach can reach here after the worker has already completed cleanup. Its
            // onExit removed both registrations; do not republish it after its sole release notification.
            val mirror = synchronized(mirrorLock) { mirrorDecoders.values.any { it === decoder } }
            if (videoDecoders[type] !== decoder && !mirror && decoder !in closingVideoDecoders) return
            closingVideoDecoders += decoder
            videoDecoders.remove(type, decoder)
            synchronized(mirrorLock) { mirrorDecoders.entries.removeIf { it.value === decoder } }
            decoder.close()
        }
    }

    /** After [close], waits up to [timeoutMillis] for this sink's decoders to release their codecs. */
    fun awaitVideoReleased(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val closing = synchronized(videoOwnershipLock) { closingVideoDecoders.toList() }
        return closing.map { it.awaitExitUntil(deadline) }.all { it }
    }

    /** After [close], observes actual release of the tracked stream codecs, outside ownership locks. */
    fun whenVideoReleased(onReleased: () -> Unit) {
        val alreadyReleased = synchronized(videoOwnershipLock) {
            check(videoClosed) { "close before observing video release" }
            if (videoDecoders.isEmpty() && mirrorDecoders.isEmpty() && closingVideoDecoders.isEmpty()) true
            else {
                videoReleasedListeners += onReleased
                false
            }
        }
        if (alreadyReleased) runCatching(onReleased)
    }

    private fun onVideoDecoderExit(decoder: VideoDecoder) {
        val listeners = synchronized(videoOwnershipLock) {
            closingVideoDecoders -= decoder
            videoDecoders.entries.removeIf { it.value === decoder }
            synchronized(mirrorLock) { mirrorDecoders.entries.removeIf { it.value === decoder } }
            if (videoClosed && videoDecoders.isEmpty() && mirrorDecoders.isEmpty() && closingVideoDecoders.isEmpty()) {
                videoReleasedListeners.toList().also { videoReleasedListeners.clear() }
            } else emptyList()
        }
        listeners.forEach { runCatching(it) }
        val resume = synchronized(videoOwnershipLock) {
            diagnosticResumeOwner?.takeIf { it == diagnosticVideoOwner && closingVideoDecoders.isEmpty() }
        }
        resume?.let(::resumeVideoAfterDiagnostics)
    }

    /** True when this sink paces main-screen frames (smooth video); fixed for its lifetime. */
    val videoPacingEnabled: Boolean get() = videoPacingDelayMillis > 0
    val mainVideoLowLatencyEnabled: Boolean get() = vendorLowLatencyDecoder && !mainScreenSoftwareDecoder
    val mainVideoMtkTuningEnabled: Boolean get() = mtkDecoderTuningEnabled && !mainScreenSoftwareDecoder

    /** Requests a keyframe for stream [type], e.g. after its surface came back. */
    /** Running totals of the main screen's decoder for a live FPS counter; null while it has none. */
    fun liveVideoCounters(): LiveVideoCounters? = videoDecoders[MAIN_SCREEN_TYPE]?.liveCounters()

    fun refreshPicture(type: Int) {
        videoDecoders[type]?.refreshPicture()
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?,
        color: VideoColorOverride = VideoColorOverride(), diagnostic: ((String) -> Unit)? = null) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val id = type to key
            synchronized(mirrorLock) {
                mirrorDecoders[id]?.let { retire(type, it) }
                if (surface == null) {
                    mirrorSurfaces.remove(id)
                    mirrorOptions.remove(id)
                    return
                }
                mirrorSurfaces[id] = surface
                mirrorOptions[id] = MirrorOptions(color, diagnostic)
            }
            lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
        }
    }

    /** Stops only the named mirror and waits for its own worker, even while the main sink stays open. */
    fun detachMirrorAndAwait(type: Int, key: String, timeoutMillis: Long): Boolean {
        val label = " stream=$type mirror=$key"
        val retiring = synchronized(videoOwnershipLock) {
            synchronized(mirrorLock) {
                val id = type to key
                mirrorSurfaces.remove(id)
                mirrorOptions.remove(id)
                mirrorDecoders[id]?.let { retire(type, it) }
                closingVideoDecoders.filter { it.hasStatsLabel(label) }
            }
        }
        val deadline = if (timeoutMillis == Long.MAX_VALUE) Long.MAX_VALUE
            else System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        return retiring.map { it.awaitExitUntil(deadline) }.all { it }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(videoOwnershipLock) {
        if (videoClosed || diagnosticVideoOwner != null) return emptyList()
        synchronized(mirrorLock) {
            if (mirrorSurfaces.isEmpty()) return emptyList()
            mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
                mirrorDecoders.getOrPut(id) {
                    val options = mirrorOptions[id]
                    newVideoDecoder(type, surface, " stream=$type mirror=${id.second}",
                        colorOverride = options?.color ?: VideoColorOverride(), diagnostic = options?.diagnostic)
                }
            }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    @Volatile private var diagnosticVideoOwner: String? = null
    private var diagnosticResumeOwner: String? = null
    /** Retire video only; audio, protocol and accessory ownership remain untouched. */
    fun suspendVideoForDiagnostics(owner: String, cancelled: () -> Boolean = { false }): Boolean {
        synchronized(videoOwnershipLock) {
            if (cancelled()) return false
            check(diagnosticVideoOwner == null || diagnosticVideoOwner == owner)
            diagnosticVideoOwner = owner
            diagnosticResumeOwner = null
            videoDecoders.toMap().forEach { (type, decoder) -> retire(type, decoder) }
            synchronized(mirrorLock) { mirrorDecoders.toMap().forEach { (id, decoder) -> retire(id.first, decoder) } }
        }
        val released = awaitVideoReleased(5_000)
        if (cancelled()) { resumeVideoAfterDiagnostics(owner); return false }
        return released
    }
    fun requestDiagnosticKeyFrame() { requestVideoRecovery(MAIN_SCREEN_TYPE) }
    fun resumeVideoAfterDiagnostics(owner: String): Boolean {
        synchronized(videoOwnershipLock) {
            if (diagnosticVideoOwner != owner) return false
            if (closingVideoDecoders.isNotEmpty()) {
                diagnosticResumeOwner = owner
                return false
            }
            diagnosticVideoOwner = null
            diagnosticResumeOwner = null
        }
        requestVideoRecovery(MAIN_SCREEN_TYPE)
        return true
    }
    fun diagnosticsVideoSuspended() = diagnosticVideoOwner != null
    fun diagnosticLeaseReleased(owner: String): Boolean = synchronized(videoOwnershipLock) {
        diagnosticVideoOwner != owner || (videoClosed && videoDecoders.isEmpty() &&
            mirrorDecoders.isEmpty() && closingVideoDecoders.isEmpty())
    }
    /** Only used by disposable fixture/replay sinks, before feeding their first access unit. */
    fun finishInputForDiagnostics() { synchronized(videoOwnershipLock) { videoDecoder(MAIN_SCREEN_TYPE).finishInputForDiagnostics() } }
    fun freezeFirstOutputForDiagnostics() { synchronized(videoOwnershipLock) { videoDecoder(MAIN_SCREEN_TYPE).freezeFirstOutputForDiagnostics() } }

    @Volatile var diagnosticCapture: VideoDiagnosticCapture? = null

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        synchronized(videoOwnershipLock) {
            if (!videoClosed) pendingVideoCodec[type] = codec
        }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val codec = pendingVideoCodec[type] ?: VideoCodec.H264
            if (type == MAIN_SCREEN_TYPE) diagnosticCapture?.configuration(codec, codecData)
            lastVideoConfig[type] = codec to codecData
            if (diagnosticVideoOwner != null) return
            videoDecoder(type).configure(codec, codecData)
            mirrorDecoders(type).forEach { it.configure(codec, codecData) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val receivedNs = System.nanoTime()
            if (type == MAIN_SCREEN_TYPE) diagnosticCapture?.receive(naluBytes, now = receivedNs)
            if (diagnosticVideoOwner != null) return
            videoDecoder(type).submit(naluBytes, receivedNs = receivedNs)
            mirrorDecoders(type).forEach { it.submit(naluBytes, receivedNs = receivedNs) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val receivedNs = System.nanoTime()
            if (type == MAIN_SCREEN_TYPE) diagnosticCapture?.receive(naluBytes, senderNanos, arrivalNanos, receivedNs)
            if (diagnosticVideoOwner != null) return
            videoDecoder(type).submit(naluBytes, senderNanos, arrivalNanos, receivedNs)
            mirrorDecoders(type).forEach { it.submit(naluBytes, receivedNs = receivedNs) }
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            if (!active) {
                videoRecoveryHandlers.remove(type)
                videoDiagnosticHandlers.remove(type)
                videoDecoders[type]?.let { retire(type, it) }
                synchronized(mirrorLock) {
                    mirrorDecoders.keys.filter { it.first == type }.forEach { id -> mirrorDecoders[id]?.let { retire(type, it) } }
                }
                lastVideoConfig.remove(type)
                pendingVideoCodec.remove(type)
            }
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    fun close(){
        synchronized(videoOwnershipLock) {
            videoClosed = true
            videoDecoders.entries.toList().forEach { (type, decoder) -> retire(type, decoder) }
            synchronized(mirrorLock) {
                mirrorDecoders.entries.toList().forEach { (id, decoder) -> retire(id.first, decoder) }
                mirrorDecoders.clear()
                mirrorSurfaces.clear()
                mirrorOptions.clear()
            }
        }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
    }

    private fun videoDecoder(type: Int): VideoDecoder = synchronized(videoOwnershipLock) {
        videoDecoders[type] ?: run {
            // A decoder that replaces a closed one picks up the stream's codec configuration.
            val decoder = newVideoDecoder(type, surfaces[type] ?: defaultSurface, startImmediately = false)
            videoDecoders[type] = decoder
            lastVideoConfig[type]?.let { (codec, data) -> decoder.configure(codec, data) }
            decoder.start()
            decoder
        }
    }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null, startImmediately: Boolean = true,
        colorOverride: VideoColorOverride = videoColorOverride, diagnostic: ((String) -> Unit)? = null): VideoDecoder {
        val isMain = type == MAIN_SCREEN_TYPE && statsLabel == null
        val softwareMain = mainScreenSoftwareDecoder && isMain
        val isDiag = (statsLabel != null && statsLabel.contains("diagnostic", ignoreCase = true)) || diagnostic != null
        val effectivePlan = if (isMain) videoPipelinePlan else null

        val planPurpose = if (isMain && effectivePlan?.request?.purpose != null) {
            effectivePlan.request.purpose
        } else if (isDiag) {
            VideoPurpose.DIAGNOSTIC
        } else {
            VideoPurpose.LIVE
        }

        val planSoftwareYuv = if (isMain) {
            effectivePlan?.softwareYuv ?: mainScreenSoftwareYuv
        } else {
            null
        }

        val planHardwareYuv = if (isMain) {
            (effectivePlan?.hardwareYuv == true) || mainScreenHardwareYuv
        } else {
            false
        }

        val planForceSoftware = if (isMain) {
            (effectivePlan?.decode == VideoDecodePolicy.STRICT_SOFTWARE) || forceSoftwareVideoDecoder || softwareMain
        } else {
            forceSoftwareVideoDecoder
        }

        val planColor = if (isMain && effectivePlan?.request?.color != null) {
            effectivePlan.request.color
        } else {
            colorOverride
        }

        lateinit var created: VideoDecoder
        created = VideoDecoder(
            type,
            surface,
            videoWidth,
            videoHeight,
            preferSoftwareHevcDecoder,
            requestKeyFrame = { requestVideoRecovery(type) },
            report = { message ->
                if (diagnostic != null) runCatching { diagnostic(message) }
                else videoDiagnosticHandlers[type]?.invoke(message)
            },
            colorOverride = planColor,
            softwareYuv = planSoftwareYuv,
            hardwareYuvEnabled = planHardwareYuv,
            forceSoftwareVideoDecoder = planForceSoftware,
            statsLabel = statsLabel,
            purpose = planPurpose,
            onExit = ::onVideoDecoderExit,
            onEvidence = { actual ->
                if (isMain) {
                    onVideoObservation(VideoOutputObservation(
                        effectivePlan?.planId,
                        actual.decoder.takeIf { it.isNotBlank() },
                        actual.software.takeIf { actual.decoder.isNotBlank() },
                        actual.format,
                        actual.generation
                    ))
                }
            },
            // Only the main screen goes to the host's SurfaceView; mirrors and the cluster keep their path.
            pacingDelayNanos = if (isMain) videoPacingDelayMillis * 1_000_000L else 0L,
            operatingRate = videoOperatingRate(type, statsLabel, mainVideoFrameRate),
            vendorLowLatency = vendorLowLatencyDecoder && !softwareMain,
            // Surface software decoding can deliver output independently of the next input job.
            // Raw YUV/EGL stays on one owner thread; never migrate its context to this output thread.
            dedicatedOutputThread = !(isMain && (planSoftwareYuv != null || planHardwareYuv)) &&
                ((vendorLowLatencyDecoder && !softwareMain) || (isMain && (softwareMain || planForceSoftware))),
            framesAtOnce = isMain && videoPacingDelayMillis > 0,
        // Keep the experimental MTK mode on the main CarPlay picture only. Mirrors and the
        // cluster have separate timing and must retain their established decoder configuration.
        mtkDecoderTuningEnabled = mtkDecoderTuningEnabled && type == MAIN_SCREEN_TYPE && statsLabel == null && !softwareMain,
    )
        if (startImmediately) created.start()
        return created
    }

}

/** CarPlay's main screen stream type. */
private const val MAIN_SCREEN_TYPE = 110

/**
 * An offscreen consumer a parked decoder renders to while its SurfaceView is gone. It drops each image
 * on its own thread, so it keeps draining while the main thread waits on a surface handoff. Only the
 * decoder worker creates and closes it, after the codec no longer renders to it.
 */
internal class ParkingOutput(width: Int, height: Int) {
    private val consumerLock = Any()
    private var closed = false
    private val thread = android.os.HandlerThread("carplay-video-parking").apply { start() }
    private val reader: android.media.ImageReader
    val surface: Surface
    /** The looper the consumer drains on, never the main one. */
    val listenerLooper: android.os.Looper get() = thread.looper

    init {
        try {
            reader = android.media.ImageReader.newInstance(
                width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.ImageFormat.PRIVATE, 3,
            )
            reader.setOnImageAvailableListener({ source ->
                synchronized(consumerLock) {
                    if (!closed) source.acquireLatestImage()?.close()
                }
            }, android.os.Handler(thread.looper))
            surface = reader.surface
        } catch (error: Throwable) {
            thread.quitSafely()
            throw error
        }
    }

    fun close() {
        try {
            synchronized(consumerLock) {
                if (!closed) {
                    closed = true
                    reader.close()
                }
            }
        } finally {
            thread.quitSafely()
        }
    }
}

/**
 * The operating rate requested from a stream's decoder, 0 for none: the main screen asks for the frame
 * rate the iPhone is asked for ([frameRate], the frame-rate setting). At 60 fps on my Tang, the Qualcomm
 * decoder's median time from queueing a frame to dequeueing its output was 8.5–9 ms lower with it.
 * Mirrors and the cluster stream keep the format they had.
 */
internal fun videoOperatingRate(type: Int, statsLabel: String?, frameRate: Int): Int =
    if (type == MAIN_SCREEN_TYPE && statsLabel == null && frameRate > 0) frameRate else 0

internal data class DecoderAttempt(
    val codecName: String?,
    val tuned: Boolean,
    val operatingRate: Int = 0,
    val mtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
)

/**
 * Configure attempts in order. Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
 * parameters with BAD_VALUE, so a minimal format and then software follow. An [operatingRate] is tried
 * first on its own, so a decoder that refuses it keeps the tuned format it gets without one. A
 * [lowLatencyDecoder] gets its tuned attempts before the default decoder's.
 */
internal fun videoDecoderAttempts(
    operatingRate: Int,
    softwareDecoder: String?,
    lowLatencyDecoder: String? = null,
    maximumMtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
): List<DecoderAttempt> = buildList {
    if (operatingRate > 0) lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true, operatingRate)) }
    lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true)) }
    val mtkModes = maximumMtkMode.attempts()
    if (operatingRate > 0) {
        mtkModes.forEach { add(DecoderAttempt(null, tuned = true, operatingRate, it)) }
    }
    mtkModes.forEach { add(DecoderAttempt(null, tuned = true, mtkMode = it)) }
    add(DecoderAttempt(codecName = null, tuned = false))
    softwareDecoder?.let { add(DecoderAttempt(it, tuned = false)) }
}

/**
 * The operating rate to ask for at the next configure: none once a later attempt ([used]) worked after
 * the rate was refused, so the refusal is not paid again; unchanged when every attempt failed.
 */
internal fun nextOperatingRate(requested: Int, used: DecoderAttempt?): Int =
    if (requested > 0 && used != null && used.operatingRate == 0) 0 else requested

/**
 * Qualcomm's vendor parameters that make its decoders output each frame as soon as it is decoded, in
 * decode order. Empty for other decoders. CarPlay's screen stream has no B-frames,
 * so decode order is display order.
 */
internal fun vendorLowLatencyKeys(codecName: String): List<String> =
    if (codecName.startsWith("c2.qti.") || codecName.startsWith("OMX.qcom.")) {
        listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")
    } else emptyList()

/** One regular decoder of the stream's type, as [lowLatencyDecoderName] sees it. */
internal data class DecoderCandidate(val name: String, val hardware: Boolean, val alias: Boolean, val lowLatency: Boolean)

/**
 * The decoder the main screen tries first with smooth video: the first hardware decoder (not an alias) that
 * advertises the low-latency feature, whose tuned format then asks for low latency. On my Tang this is
 * c2.qti.avc.decoder.low_latency; the default c2.qti.avc.decoder does not advertise the feature.
 */
internal fun lowLatencyDecoderName(candidates: List<DecoderCandidate>): String? =
    candidates.firstOrNull { it.lowLatency && it.hardware && !it.alias }?.name

/** The low-latency decoder to try at the next configure: none once another decoder ([used]) worked after it. */
internal fun nextLowLatencyDecoder(requested: String?, used: DecoderAttempt?): String? =
    if (requested != null && used != null && used.codecName != requested) null else requested

/**
 * Qualcomm's decoder parameter for output in decoding order. Without it my Tang's decoders released a frame
 * only once one or two later frames had been queued. Decoding order is the display order only for a stream
 * without reordered (B) frames; the iPhone's CarPlay H.264 had none. It is set only for the main screen's H.264
 * with smooth video, and only on a decoder that lists the parameter.
 */
internal const val PICTURE_ORDER_PARAMETER = "vendor.qti-ext-dec-picture-order.enable"

internal data class DiagnosticSamplingConfig(
    val points: List<Pair<Int, Int>>,
    val patchIndices: List<Int?>,
    val nominals: List<Int?>,
    val frameId: String?,
    val instanceId: String?,
)

/** Propagate cleanup failures without replacing an earlier rendering failure. */
internal inline fun <T> withYuvOutputRelease(
    codec: MediaCodec, item: OutputItem, closeImage: () -> Unit, block: () -> T,
): T {
    var primaryFailure: Throwable? = null
    try {
        return block()
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        var cleanupFailure: Throwable? = null
        try { closeImage() } catch (error: Throwable) { cleanupFailure = error }
        try {
            item.release(codec, false)
        } catch (error: Throwable) {
            val previous = cleanupFailure
            if (previous == null) cleanupFailure = error
            else if (previous !== error) previous.addSuppressed(error)
        }
        cleanupFailure?.let { error ->
            val primary = primaryFailure
            if (primary == null) throw error
            if (primary !== error) primary.addSuppressed(error)
        }
    }
}

internal class OutputItem(
    val index: Int,
    val flags: Int,
    val size: Int,
    val offset: Int,
    val presentationTimeUs: Long,
    val dequeuedNs: Long,
    val surfaceGen: Long,
) {
    var released: Boolean = false
        private set

    fun release(codec: MediaCodec, render: Boolean = false) {
        if (!released) {
            released = true
            codec.releaseOutputBuffer(index, render)
        }
    }

    fun release(codec: MediaCodec, renderTimestampNs: Long) {
        if (!released) {
            released = true
            codec.releaseOutputBuffer(index, renderTimestampNs)
        }
    }

    fun markReleased() {
        released = true
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    private val streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    private val colorOverride: VideoColorOverride = VideoColorOverride(),
    private val forceSoftwareVideoDecoder: Boolean = false,
    private val softwareYuv: SoftwareYuvConfig? = null,
    private val hardwareYuvEnabled: Boolean = false,
    private val statsLabel: String? = null,
    private val purpose: VideoPurpose = VideoPurpose.LIVE,
    pacingDelayNanos: Long = 0L,
    /** Called on the worker as its last step, after it has released its codec. */
    private val onExit: (VideoDecoder) -> Unit = {},
    private val onEvidence:(VideoDecoderColorEvidence)->Unit={},
    operatingRate: Int = 0,
    /** Set [vendorLowLatencyKeys] on the tuned configure attempts. */
    private val vendorLowLatency: Boolean = false,
    /**
     * Release decoded frames from a thread that waits on the codec's output, instead of when the worker
     * next comes back from its queue or from feeding: a frame is shown as soon as it is decoded.
     */
    dedicatedOutputThread: Boolean = false,
    /**
     * The main screen with smooth video: for H.264, try a low-latency decoder first ([lowLatencyDecoderName]) and
     * ask decoders that list it for [PICTURE_ORDER_PARAMETER], so each frame leaves the decoder as soon as it can.
     */
    private val framesAtOnce: Boolean = false,
    private val mtkDecoderTuningEnabled: Boolean = false,
) : Closeable {
    private val pacer = FramePacer()
    private val pacingDelay = if (pacingDelayNanos > 0) PacingDelay(pacingDelayNanos) else null
    // Per queued presentation time (decode thread only): when it was queued, and whether it is a paced
    // frame's local time rather than the queue time.
    private val queuedPresentationUs = LongArray(64)
    private val queuedAtNanos = LongArray(64)
    private val queuedReceivedNs = LongArray(64)
    private val queuedPaced = BooleanArray(64)
    // Set on the paced frames queued just before a pause in the iPhone's frames: the decoder releases a
    // frame only once about two more are queued, so they waited for that pause, which no display delay
    // can hide.
    private val queuedBeforePause = BooleanArray(64)
    private val recentPacedSlots = IntArray(PAUSE_HELD_FRAMES) { -1 }
    private var queuedSlot = 0
    // The decoder releases a frame only once later input arrives, so a frame queued before a still-screen
    // gap waits for the next one; such waits are not decode time and are left out of the stats.
    private var lastQueuedNanos = 0L
    private var resumedAtNanos = 0L
    private var lastQueuedLocalNanos = 0L
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var parking: ParkingOutput? = null
    private var lastConfig: VideoJob.Config? = null
    private var sourceColor: VideoColorMetadata.Description? = null
    private var softwareYuvRenderer: SoftwareYuvRenderer? = null
    private var softwareYuvOutputFormat: MediaFormat? = null
    private var softwareYuvFailed = false
    private var hardwareYuvFallback = false
    private var hardwareYuvStartedNs = 0L
    private val yuvConfig: SoftwareYuvConfig? get() = softwareYuv ?:
        SoftwareYuvConfig().takeIf { hardwareYuvEnabled && !hardwareYuvFallback }
    private val pipelineTiming = VideoPipelineTiming()
    private val diagnosticOutputGate = DiagnosticOutputGate()
    fun freezeFirstOutputForDiagnostics() { diagnosticOutputGate.request() }
    fun finishInputForDiagnostics() { enqueue(VideoJob.EndOfDiagnosticInput) }
    @Volatile private var renderedFrameLogged = false
    // The operating rate still asked for, dropped for this stream once a decoder refuses it; and the rate
    // the current codec was configured with.
    private var operatingRate = operatingRate
    private val colorEvidence = VideoColorEvidenceState(onEvidence)
    private var colorGeneration = 0L
    private var surfaceGeneration = 0L
    fun colorSnapshot(): VideoDecoderColorEvidence = colorEvidence.snapshot()
    private var configuredRate = 0
    private var maximumMtkMode = if (mtkDecoderTuningEnabled) {
        MtkDecoderMode.LOW_LATENCY_NO_REORDER
    } else MtkDecoderMode.NONE
    private var configuredMtkMode = MtkDecoderMode.NONE
    private var configuredMtkCodec2LowLatency = false
    // Whether the vendor low-latency parameters were set on the codec being configured.
    private var configuredVendorLowLatency = false
    // Likewise the low-latency decoder, not tried again once another decoder had to take over; and what the
    // codec that tryConfigure started last was given.
    private var lowLatencyDecoderRefused = false
    private var startedLowLatency = false
    private var startedPictureOrder = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private val backlogRecovery = VideoBacklogRecovery()
    private val cadenceEvidence = VideoCadenceEvidence()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == MAIN_SCREEN_TYPE) "" else " stream=$streamType")
    // Running totals for the live FPS counter, read from the UI thread.
    private val liveReceived = AtomicLong()
    private val liveReceivedBytes = AtomicLong()
    private val liveRendered = AtomicLong()
    private val liveDecodeNanos = AtomicLong()
    private val liveDecodeSamples = AtomicLong()
    private val liveDecodedDiscarded = AtomicLong()

    fun liveCounters() = LiveVideoCounters(
        received = liveReceived.get(),
        receivedBytes = liveReceivedBytes.get(),
        pipeline = pipelineTiming.snapshot(),
        rendered = liveRendered.get(),
        decodeNanos = liveDecodeNanos.get(),
        decodeSamples = liveDecodeSamples.get(),
        decodedDiscarded = liveDecodedDiscarded.get(),
    )

    fun cadenceSnapshot(): Map<String, Any?> = cadenceEvidence.snapshot()
    fun recentBacklogEpisodes(): List<BacklogEpisode> = backlogRecovery.recentEpisodes()
    fun latestRawSamples(): List<Yuv420Frame.RawSample> = softwareYuvRenderer?.latestRawSamples.orEmpty()

    @Volatile private var diagnosticSamplingConfig: DiagnosticSamplingConfig? = null

    fun configureDiagnosticSampling(
        points: List<Pair<Int, Int>>,
        patchIndices: List<Int?> = emptyList(),
        nominals: List<Int?> = emptyList(),
        frameId: String? = null,
        instanceId: String? = null,
    ) {
        val config = DiagnosticSamplingConfig(points.toList(), patchIndices.toList(), nominals.toList(), frameId, instanceId)
        diagnosticSamplingConfig = config
        softwareYuvRenderer?.configureDiagnosticSampling(config.points, config.patchIndices, config.nominals, config.frameId, config.instanceId)
    }
    /** Apply the saved sampling snapshot before a lazily created renderer receives its first image. */
    private fun <T> createDiagnosticRenderer(
        factory: () -> T,
        applySampling: (T, DiagnosticSamplingConfig) -> Unit,
    ): T = factory().also { renderer ->
        diagnosticSamplingConfig?.let { applySampling(renderer, it) }
    }

    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }
    private val outputThread = if (dedicatedOutputThread) {
        Thread(::runOutput, "carplay-video-output").apply { isDaemon = true }
    } else null
    // Guards the codec handoff between the worker and the output thread: [decoder] changes, [outputSurface]
    // and [parking] changes, the per-frame bookkeeping, and the output thread's handling of one buffer.
    // The output thread waits inside dequeueOutputBuffer without it.
    private val outputLock = Object()
    // The codec the output thread is waiting on; the worker waits for it to return before releasing that codec.
    private var dequeuing: MediaCodec? = null
    // A failure on the output thread, handled by the worker as if its own job had failed.
    @Volatile private var outputFailure: Exception? = null

    fun start() {
        thread.start()
        outputThread?.start()
    }

    private fun enqueue(job: VideoJob) {
        if (!queue.offer(job)) close()
    }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        enqueue(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L, receivedNs: Long = System.nanoTime()) {
        VideoDiagnosticTrace.event(colorInstance(), "received_queue", receivedNs, senderNanos, arrivalNanos)
        cadenceEvidence.received(senderNanos.takeIf { it > 0 }, if (arrivalNanos > 0) arrivalNanos else receivedNs)
        stats.onReceived(nalus.size)
        liveReceived.incrementAndGet()
        liveReceivedBytes.addAndGet(nalus.size.toLong())
        // Pacing runs on the worker, in queue order: callbacks of a replaced stream may still deliver here.
        enqueue(VideoJob.Frame(nalus, receivedNs = receivedNs, senderNanos = senderNanos, arrivalNanos = arrivalNanos))
    }

    fun setSurface(surface: Surface?) {
        enqueue(VideoJob.SurfaceChanged(surface))
    }

    /** Asks the worker to stop rendering to [surface], parking the codec when [park]. */
    fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest {
        check(Thread.currentThread() !== thread) { "detach from the decoder worker" }
        // A rejected request remains unconfirmed until awaitDetach observes actual worker exit.
        return SurfaceDetachRequest(surface, park).also { enqueue(VideoJob.DetachSurface(it)) }
    }

    /** True once [request] is confirmed or the worker has exited; false when [deadlineNanos] passed first. */
    fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean =
        request.await(deadlineNanos - System.nanoTime(), workerAlive = thread::isAlive)

    /** True once the worker has exited, so its codec is released. */
    val exited: Boolean get() = !thread.isAlive

    fun hasStatsLabel(label: String): Boolean = statsLabel == label

    /**
     * After [close], waits until [deadlineNanos] for the worker to release its codec and exit. An
     * interrupt does not end the wait early; it is restored before returning.
     */
    fun awaitExitUntil(deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (thread.isAlive) {
                val leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0) break
                try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    fun refreshPicture() {
        enqueue(VideoJob.RefreshPicture)
    }

    override fun close() {
        running = false
        queue.close()
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                outputFailure?.let { failure ->
                    outputFailure = null
                    onJobFailure("output", failure)
                }
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        VideoJob.EndOfDiagnosticInput -> {
                            val activeCodec=decoder
                            if(activeCodec!=null){
                                val input=DecoderInputPump.acquire(running={running},drain={if(outputThread==null)drainOutput(activeCodec)},dequeue={activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)})
                                if(input>=0)activeCodec.queueInputBuffer(input,0,0,System.nanoTime()/1000,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                if(outputThread==null)drainOutput(activeCodec)
                            }
                        }
                        is VideoJob.Frame -> {
                            if (outputThread == null && decoder != null) drainOutput(decoder!!)
                            val backlog = queue.backlogAfterCurrent()
                            val overloaded = !referenceChain.needsKeyFrame && backlogRecovery.observe(
                                System.nanoTime(), job.receivedNs,
                                backlog.pendingFrames, backlog.newestPendingReceivedNs,
                            )
                            if (overloaded) {
                                recoverReferenceChain("video backlog kept growing or exceeded hard limit")
                            } else feed(job.nalus, job.receivedNs, localTimeOf(job))
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        // Once per new surface, so it skips the rate limit an earlier request may still hold.
                        is VideoJob.RefreshPicture -> if (decoder != null) {
                            lastKeyFrameRequestNs = System.nanoTime()
                            requestKeyFrame()
                        }
                        is VideoJob.Resync -> recoverReferenceChain("video queue overflow")
                        null -> Unit
                    }
                    if (outputThread == null) decoder?.let(::drainOutput)
                    if (hardwareYuvEnabled && !hardwareYuvFallback && hardwareYuvStartedNs > 0 &&
                        !renderedFrameLogged && System.nanoTime() - hardwareYuvStartedNs > 5_000_000_000L)
                        throw IllegalStateException("MTK raw YUV produced no frame within 5s")
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    onJobFailure(job?.javaClass?.simpleName ?: "drain", error)
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            running = false
            releaseDecoder()
            outputThread?.let { output ->
                output.interrupt()
                joinUninterruptibly(output, OUTPUT_EXIT_WAIT_MS)
            }
            // The codec is gone, so nothing renders to any surface or to the parking consumer any more.
            parking?.close()
            parking = null
            queue.drain().forEach { (it as? VideoJob.DetachSurface)?.request?.complete(DetachOutcome.RELEASED) }
            runCatching { onExit(this) }
        }
    }

    private fun onJobFailure(stage: String, error: Exception) {
        if (hardwareYuvEnabled && !hardwareYuvFallback) fallBackHardwareYuv("$stage: ${error.javaClass.simpleName}: ${error.message}")
        else if (softwareYuv != null) failSoftwareYuv("$stage: ${error.javaClass.simpleName}: ${error.message}")
        if (running) Log.e(TAG, "video decoder job failed: $stage", error)
        if (running) reportFailure("stage=$stage", error)
        // MTK vendor failures may surface as CodecException or the broader IllegalStateException.
        // Retire the active experimental mode before recreating the decoder.
        if (error is IllegalStateException) {
            dropMtkTuning("codec failed during $stage")
            dropMtkCodec2LowLatency("codec failed during $stage")
        }
        // A codec can accept the rate and still fail once it runs (start errors may surface on
        // later calls): one that failed before its first frame with it is not given it again.
        if (configuredRate > 0 && !renderedFrameLogged && error is MediaCodec.CodecException) {
            dropOperatingRate("codec failed before its first frame")
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /** The output thread: waits on the current codec's output and releases each frame as it comes. */
    private fun runOutput() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        val info = MediaCodec.BufferInfo()
        while (running) {
            val codec = synchronized(outputLock) {
                val current = decoder
                if (current == null || outputFailure != null) {
                    try { outputLock.wait(OUTPUT_IDLE_WAIT_MS) } catch (_: InterruptedException) { return }
                    null
                } else {
                    dequeuing = current
                    current
                }
            } ?: continue
            var index = MediaCodec.INFO_TRY_AGAIN_LATER
            var failure: Exception? = null
            try {
                index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (error: Exception) {
                failure = error
            }
            synchronized(outputLock) {
                dequeuing = null
                outputLock.notifyAll()
                // A codec the worker released or replaced meanwhile has no buffers left to hand out.
                if (decoder === codec) {
                    try {
                        if (failure != null) throw failure
                        if (index >= 0) {
                            val item = OutputItem(index, info.flags, info.size, info.offset, info.presentationTimeUs, System.nanoTime(), surfaceGeneration)
                            try {
                                handleOutput(codec, item, info)
                            } finally {
                                try { item.release(codec, false) } catch (_: Throwable) {}
                            }
                        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            logOutputFormat(codec.outputFormat)
                        }
                    } catch (error: Exception) {
                        if (running) outputFailure = error
                    }
                } else if (index >= 0) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        if (softwareYuv != null && softwareYuvFailed) return
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        colorGeneration = colorEvidence.begin(videoConfigurationHash(config.codecData))
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        sourceColor = if (colorOverride.fromSps || yuvConfig != null) VideoColorMetadata.parse(codec, codecData) else null
        if (colorOverride.fromSps) {
            val description = sourceColor
            report("SPS color request status=${description?.status} hints=${description?.let(VideoColorMetadata::androidHints)} " +
                "manualRange=${colorOverride.range} manualStandard=${colorOverride.standard}")
        }
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        val requestedRate = operatingRate
        val atOnce = framesAtOnce && codec == VideoCodec.H264
        val requestedMtkCodec2 = if (mtkDecoderTuningEnabled && !lowLatencyDecoderRefused) {
            MtkDecoderTuning.codec2LowLatencyDecoderName(mime)
        } else null
        val requestedLowLatency = requestedMtkCodec2
            ?: if (atOnce && !lowLatencyDecoderRefused) findLowLatencyDecoder(mime) else null
        val requestedMtkMode = if (mtkDecoderTuningEnabled &&
            MtkDecoderTuning.legacyAcodecDecoderName(mime) != null) maximumMtkMode else MtkDecoderMode.NONE
        var configured: ConfiguredVideoDecoder? = null
        var used: DecoderAttempt? = null
        val diagnosticSoftware = if (forceSoftwareVideoDecoder) {
            if (softwareYuv != null) softwareYuvDecoderName(mime)
            else diagnosticSoftwareDecoderName(diagnosticSoftwareCandidates(), mime)
        } else null
        if (forceSoftwareVideoDecoder && diagnosticSoftware == null) report("diagnostic software decoder unavailable mime=$mime; hardware fallback disabled")
        val rawHardware = hardwareYuvEnabled && !hardwareYuvFallback
        val hardwareName = if (rawHardware) mtkYuvDecoderName(mime) else null
        val attempts = if (rawHardware) hardwareName?.let { listOf(
            DecoderAttempt(it, tuned = true, operatingRate = requestedRate),
            DecoderAttempt(it, tuned = false)) }.orEmpty()
        else diagnosticVideoDecoderAttempts(forceSoftwareVideoDecoder, diagnosticSoftware, videoDecoderAttempts(
            requestedRate, softwareDecoderName(mime), requestedLowLatency, requestedMtkMode))
        for (attempt in attempts) {
            configured = tryConfigure(mime, csd, surface, attempt, atOnce)
            if (configured != null) { used = attempt; break }
        }
        if (configured == null && rawHardware) {
            fallBackHardwareYuv("no working MTK flexible-YUV decoder for $mime")
            configureDecoder(config)
            return
        }
        if (configured == null) {
            if (softwareYuv != null) failSoftwareYuv("no working software YUV420 decoder for $mime")
            queue.discardCurrentChain()
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        if (nextOperatingRate(requestedRate, used) != requestedRate) dropOperatingRate("refused at configure")
        if (nextLowLatencyDecoder(requestedLowLatency, used) != requestedLowLatency) {
            report("low-latency decoder $requestedLowLatency dropped: refused at configure")
            lowLatencyDecoderRefused = true
        }
        renderedFrameLogged = false
        submittedFrameLogged = false
        synchronized(outputLock) {
            configured?.let { ready ->
                val software = diagnosticSoftwareCandidates().firstOrNull { it.name == ready.codecName }?.softwareReported
                    ?: isKnownPlatformSoftwareVideoDecoder(ready.codecName)
                colorEvidence.configured(colorGeneration, ready.codecName, software)
            }
            decoder = configured?.codec
            outputLock.notifyAll()
        }
        configuredRate = used?.operatingRate ?: 0
        configuredMtkMode = configured?.mtkMode ?: MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = configured != null && requestedMtkCodec2 != null &&
            configured.codecName.equals(requestedMtkCodec2, ignoreCase = true)
        if (configured != null && MtkDecoderTuning.supportsLegacyAcodecKeys(configured.codecName) &&
            configuredMtkMode.ordinal < requestedMtkMode.ordinal) {
            report("MTK decoder tuning fell back at configure from=${requestedMtkMode.diagnosticName} " +
                "to=${configuredMtkMode.diagnosticName}")
            maximumMtkMode = configuredMtkMode
        }
        if (configured != null) {
            hardwareYuvStartedNs = if (rawHardware) System.nanoTime() else 0L
            val rate = if (requestedRate > 0) " operatingRate=$configuredRate" else ""
            val lowLatency = if (vendorLowLatency) " vendorLowLatency=$configuredVendorLowLatency" else ""
            val output = if (outputThread != null) " outputThread=true" else ""
            val atOnceReport = if (atOnce) " lowLatency=$startedLowLatency pictureOrder=$startedPictureOrder" else ""
            val mtk = when {
                configuredMtkCodec2LowLatency -> " mtkTuning=codec2-low-latency"
                MtkDecoderTuning.isDecoderName(configured.codecName) ->
                    " mtkTuning=${configuredMtkMode.diagnosticName}"
                else -> ""
            }
            report("decoder=${configured.codecName} mime=$mime size=${width}x$height" +
                "$rate$lowLatency$output$atOnceReport$mtk")
            Log.i(
                TAG,
                "video decoder configured name=${configured.codecName} mime=$mime size=${width}x$height$mtk",
            )
        }
    }

    private fun dropOperatingRate(reason: String) {
        report("operating rate $operatingRate dropped: $reason")
        operatingRate = 0
    }

    private fun dropMtkTuning(reason: String) {
        val previous = configuredMtkMode
        if (previous == MtkDecoderMode.NONE) return
        val fallback = previous.fallback()
        if (fallback.ordinal < maximumMtkMode.ordinal) maximumMtkMode = fallback
        report("MTK decoder tuning ${previous.diagnosticName} dropped: $reason; " +
            "next=${fallback.diagnosticName}")
    }

    private fun dropMtkCodec2LowLatency(reason: String) {
        if (!configuredMtkCodec2LowLatency) return
        configuredMtkCodec2LowLatency = false
        lowLatencyDecoderRefused = true
        report("MTK Codec2 low-latency decoder dropped: $reason; next=default")
    }

    private fun buildFormat(
        mime: String,
        csd: List<ByteArray>,
        attempt: DecoderAttempt,
        mtkMode: MtkDecoderMode,
    ): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            colorOverride.applyTo(this, sourceColor)
            if (yuvConfig != null) setInteger(MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            if (attempt.tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (attempt.operatingRate > 0) setInteger(MediaFormat.KEY_OPERATING_RATE, attempt.operatingRate)
            }
            mtkMode.formatKeys().forEach { setInteger(it, 1) }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private data class ConfiguredVideoDecoder(
        val codec: MediaCodec,
        val codecName: String,
        val mtkMode: MtkDecoderMode,
    )

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
        /** Ask for [PICTURE_ORDER_PARAMETER] when the decoder lists it ([framesAtOnce], H.264). */
        atOnce: Boolean = false,
    ): ConfiguredVideoDecoder? {
        var candidate: MediaCodec? = null
        var mtkMode = MtkDecoderMode.NONE
        var codecName = attempt.codecName ?: "default"
        var phase = "create"
        var phaseStartNs = System.nanoTime()
        val startedNs = phaseStartNs
        return try {
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            val createdName = runCatching { codec.name }.getOrNull().orEmpty()
            codecName = createdName.ifEmpty { codecName }
            mtkMode = appliedMtkDecoderMode(attempt.mtkMode, createdName)
            val format = buildFormat(mime, csd, attempt, mtkMode)
            phase = "configure"
            phaseStartNs = System.nanoTime()
            val lowLatency = attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val vendorKeys = if (attempt.tuned && vendorLowLatency) vendorLowLatencyKeys(codec.name.orEmpty()) else emptyList()
            vendorKeys.forEach { format.setInteger(it, 1) }
            configuredVendorLowLatency = vendorKeys.isNotEmpty()
            val pictureOrder = attempt.tuned && atOnce && listsPictureOrder(codec)
            if (pictureOrder) format.setInteger(PICTURE_ORDER_PARAMETER, 1)
            codec.configure(format, if (yuvConfig != null) null else surface, null, 0)
            phase = "start"
            phaseStartNs = System.nanoTime()
            codec.start()
            startedLowLatency = lowLatency
            startedPictureOrder = pictureOrder
            val configuredName = runCatching { codec.name }.getOrNull().orEmpty()
                .ifEmpty { createdName }
                .ifEmpty { attempt.codecName ?: "default" }
            ConfiguredVideoDecoder(codec, configuredName, mtkMode)
        } catch (error: Exception) {
            val phaseMs = (System.nanoTime() - phaseStartNs) / 1_000_000
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            runCatching { candidate?.release() }.onFailure {
                Log.w(TAG, "video decoder candidate release failed name=$codecName", it)
            }
            reportFailure("stage=configure phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                "mtkTuning=${mtkMode.diagnosticName} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=$codecName phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                    "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                    "mtkTuning=${mtkMode.diagnosticName} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    /** Android 11 added the low-latency feature; aliases and software-only flags exist from Android 10. */
    private fun findLowLatencyDecoder(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            lowLatencyDecoderName(MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && mime in it.supportedTypes }
                .map { info ->
                    DecoderCandidate(
                        name = info.name,
                        hardware = info.isHardwareAccelerated,
                        alias = info.isAlias,
                        lowLatency = info.getCapabilitiesForType(mime).isFeatureSupported("low-latency"),
                    )
                })
        }.getOrNull()
    }

    /** Android 12 lets a codec list its vendor parameters. */
    private fun listsPictureOrder(codec: MediaCodec): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { PICTURE_ORDER_PARAMETER in codec.supportedVendorParameters }.getOrDefault(false)

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun localTimeOf(frame: VideoJob.Frame): Long =
        if (pacingDelay != null && frame.senderNanos > 0 && frame.arrivalNanos > 0) {
            pacer.localTime(frame.senderNanos, frame.arrivalNanos)
        } else 0L

    /** Completes [request] once the codec no longer renders to its surface, whatever happens here. */
    private fun detach(request: SurfaceDetachRequest): Unit = synchronized(outputLock) {
        var outcome = DetachOutcome.RELEASED
        try {
            outcome = detachAction(outputSurface, request.surface, request.park && yuvConfig == null, decoder != null)
            when (outcome) {
                DetachOutcome.NOT_RENDERING -> Unit
                DetachOutcome.PARKED -> {
                    val consumer = parking ?: ParkingOutput(width, height).also { parking = it }
                    try {
                        checkNotNull(decoder).setOutputSurface(consumer.surface)
                        outputSurface = consumer.surface
                        Log.i(TAG, "video decoder parked off screen")
                    } catch (error: Exception) {
                        Log.w(TAG, "video decoder could not park; releasing it", error)
                        outcome = DetachOutcome.RELEASED
                        outputSurface = null
                        releaseDecoder()
                    }
                }
                DetachOutcome.RELEASED -> {
                    outputSurface = null
                    releaseDecoder()
                    Log.i(TAG, "video decoder detached from surface")
                }
            }
        } catch (error: Exception) {
            outcome = DetachOutcome.RELEASED
            outputSurface = null
            releaseDecoder()
            throw error
        } finally {
            request.complete(outcome)
        }
    }

    private fun changeSurface(surface: Surface?): Unit = synchronized(outputLock) {
        if (outputSurface === surface) return
        outputSurface = surface
        surfaceGeneration++
        val yuvRenderer = softwareYuvRenderer
        softwareYuvRenderer = null
        softwareYuvOutputFormat = null
        runCatching { yuvRenderer?.close() }
        if (surface == null) {
            releaseDecoder()
            queue.discardCurrentChain()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null && yuvConfig == null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray, receivedNs: Long, presentNs: Long = 0L) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recoverHardStall("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        // A slow rebuild must not immediately restart on its obsolete triggering IDR.
        if (referenceChain.needsKeyFrame && VideoRecoveryFrameAge.isObsolete(
                System.nanoTime(), receivedNs, queue.backlogAfterCurrent(),
            )) {
            queue.discardCurrentChain()
            backlogRecovery.reset("obsolete_keyframe")
            requestKeyFrameIfDue()
            return
        }
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        val acquireStartedNs = System.nanoTime()
        val index = DecoderInputPump.acquire(
            running = { running }, drain = { if (outputThread == null) drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            dropMtkTuning("video decoder input stalled")
            dropMtkCodec2LowLatency("video decoder input stalled")
            recoverHardStall("video decoder input stalled")
            return
        }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            // A paced frame carries its local time; MediaCodec returns it with the decoded output.
            val presentationUs = if (presentNs > 0) presentNs / 1000 else System.nanoTime() / 1000
            // Recorded before queueing: the output thread may dequeue the frame as soon as it is queued.
            synchronized(outputLock) {
                val queuedNow = System.nanoTime()
                pipelineTiming.input(receivedNs, acquireStartedNs, queuedNow)
                VideoDiagnosticTrace.event(colorInstance(), "codec_submit", presentationUs, receivedNs, acquireStartedNs, queue.backlogAfterCurrent().pendingFrames.toLong())
                if (lastQueuedNanos != 0L && queuedNow - lastQueuedNanos > STILL_GAP_NS) resumedAtNanos = queuedNow
                lastQueuedNanos = queuedNow
                queuedPresentationUs[queuedSlot] = presentationUs
                queuedAtNanos[queuedSlot] = queuedNow
                queuedReceivedNs[queuedSlot] = receivedNs
                queuedPaced[queuedSlot] = presentNs > 0
                queuedBeforePause[queuedSlot] = false
                if (presentNs > 0) {
                    // A gap between consecutive iPhone frame times, not a decoder backlog, marks a pause.
                    if (lastQueuedLocalNanos != 0L && presentNs - lastQueuedLocalNanos > SENDER_PAUSE_NS) {
                        recentPacedSlots.forEach { if (it >= 0) queuedBeforePause[it] = true }
                    }
                    lastQueuedLocalNanos = presentNs
                    recentPacedSlots.copyInto(recentPacedSlots, 1, 0, PAUSE_HELD_FRAMES - 1)
                    recentPacedSlots[0] = queuedSlot
                }
                queuedSlot = (queuedSlot + 1) % queuedPresentationUs.size
            }
            codec.queueInputBuffer(index, 0, annexB.size, presentationUs, 0)
            referenceChain.onQueued()
        } else {
            recoverHardStall("video frame exceeded codec input capacity")
            return
        }
        if (outputThread == null) drainOutput(codec)
    }

    private fun colorInstance() = colorEvidence.snapshot().instance

    private fun recoverReferenceChain(reason: String) {
        val episode = backlogRecovery.currentEpisode()
        episode?.action = "reference_chain_reset"
        VideoDiagnosticTrace.event(colorInstance(), "recovery:reference_chain_reset:$reason", 0)
        Log.w(TAG, "Video recovery (reference chain reset): $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: reference_chain_reset: $reason; waiting for keyframe")
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun recoverHardStall(reason: String) {
        val episode = backlogRecovery.currentEpisode()
        episode?.action = "codec_recreated"
        VideoDiagnosticTrace.event(colorInstance(), "recovery:codec_recreated:$reason", 0)
        Log.w(TAG, "Video recovery (codec recreated): $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: codec_recreated: $reason; waiting for keyframe")
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun recover(reason: String) {
        recoverReferenceChain(reason)
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun queuedSlotOf(presentationUs: Long): Int =
        queuedPresentationUs.indices.firstOrNull { queuedPresentationUs[it] == presentationUs } ?: -1

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val batch = ArrayList<OutputItem>(8)
        val batchStartNs = System.nanoTime()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        processOutputBatch(codec, batch)
                        return
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        processOutputBatch(codec, batch)
                        logOutputFormat(codec.outputFormat)
                    }
                    index >= 0 -> {
                        val dequeuedNs = System.nanoTime()
                        val item = OutputItem(index, info.flags, info.size, info.offset, info.presentationTimeUs, dequeuedNs, surfaceGeneration)
                        batch.add(item)
                        val isEos = info.size == 0 && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (isEos) {
                            processOutputBatch(codec, batch)
                            return
                        }
                        if (isConfig || batch.size >= 8 || dequeuedNs - batchStartNs >= 2_000_000L) {
                            processOutputBatch(codec, batch)
                        }
                    }
                    else -> {
                        processOutputBatch(codec, batch)
                        return
                    }
                }
            }
            processOutputBatch(codec, batch)
        } finally {
            for (item in batch) {
                try {
                    item.release(codec, false)
                } catch (_: Throwable) {
                }
            }
            batch.clear()
        }
    }

    private fun processOutputBatch(codec: MediaCodec, batch: MutableList<OutputItem>) {
        if (batch.isEmpty()) return
        try {
            val count = batch.size
            for (i in 0 until count) {
                val item = batch[i]
                if (item.released) continue
                if (item.surfaceGen != surfaceGeneration) {
                    item.release(codec, false)
                    continue
                }
                if (item.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ||
                    (item.size == 0 && item.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)) {
                    item.release(codec, false)
                    continue
                }
                val newerOutputAvailable = i < count - 1
                val slot = queuedSlotOf(item.presentationTimeUs)
                val now = System.nanoTime()
                val receivedNs = if (slot >= 0 && queuedReceivedNs[slot] > 0) queuedReceivedNs[slot]
                    else if (slot >= 0 && queuedAtNanos[slot] > 0) queuedAtNanos[slot]
                    else item.dequeuedNs
                val ageNs = (now - receivedNs).coerceAtLeast(0L)
                val firstFrame = !renderedFrameLogged
                val isDiagnostic = (purpose == VideoPurpose.DIAGNOSTIC) ||
                    diagnosticOutputGate.isRequested ||
                    (statsLabel != null && statsLabel.contains("diagnostic", ignoreCase = true)) ||
                    (streamType != MAIN_SCREEN_TYPE)
                val explicitlyPaced = pacingDelay != null && slot >= 0 && queuedPaced[slot]

                val discard = VideoOutputFreshness.shouldDiscardDecodedOutput(
                    ageNs = ageNs,
                    newerOutputAvailable = newerOutputAvailable,
                    firstFrame = firstFrame,
                    diagnostic = isDiagnostic,
                    explicitlyPaced = explicitlyPaced,
                )

                if (discard) {
                    item.release(codec, false)
                    liveDecodedDiscarded.incrementAndGet()
                    stats.onDecodedDiscarded()
                    VideoDiagnosticTrace.event(colorInstance(), "decoded_discarded", item.presentationTimeUs, ageNs)
                } else {
                    val bufferInfo = MediaCodec.BufferInfo().apply {
                        set(item.offset, item.size, item.presentationTimeUs, item.flags)
                    }
                    handleOutput(codec, item, bufferInfo)
                }
            }
        } finally {
            for (item in batch) {
                try {
                    item.release(codec, false)
                } catch (_: Throwable) {
                }
            }
            batch.clear()
        }
    }

    /** Handles one dequeueOutputBuffer result; false when there is nothing more to drain for now. */
    private fun handleOutput(codec: MediaCodec, item: OutputItem, info: MediaCodec.BufferInfo): Boolean {
        try {
            when {
                item.index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                item.index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                item.index >= 0 -> {
                    if (info.size == 0 && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        item.release(codec, false); return false
                    }
                    if (yuvConfig != null) return handleYuvOutput(codec, item, info)
                    val render = outputSurface != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && diagnosticOutputGate.allow()
                    // Parked output goes to the offscreen consumer: it keeps the codec's state, but it is
                    // not shown, so it is neither paced nor counted.
                    val shown = render && outputSurface !== parking?.surface
                    val now = System.nanoTime()
                    VideoDiagnosticTrace.event(colorInstance(), "codec_output", info.presentationTimeUs)
                    val slot = queuedSlotOf(info.presentationTimeUs)
                    val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
                    val heldOverGap = queued in 1 until resumedAtNanos
                    if (queued > 0 && !heldOverGap) {
                        pipelineTiming.output(queued, now)
                        stats.onDecodeLatency(now - queued)
                        liveDecodeNanos.addAndGet(now - queued)
                        liveDecodeSamples.incrementAndGet()
                    }
                    val delay = pacingDelay
                    val paced = shown && delay != null && slot >= 0 && queuedPaced[slot]
                    val localNs = info.presentationTimeUs * 1000
                    val pauseHeld = paced && queuedBeforePause[slot]
                    val targetNs = if (paced && delay != null) {
                        if (!heldOverGap && !pauseHeld) delay.onFrame(now - localNs)
                        stats.onPacingDelay(delay.nanos)
                        localNs + delay.nanos
                    } else 0L
                    val releaseStartedNs = System.nanoTime()
                    if (targetNs - now in 1..MAX_PACING_AHEAD_NS) {
                        item.release(codec, targetNs)
                    } else {
                        if (shown && delay != null && !heldOverGap && !pauseHeld) stats.onLate()
                        item.release(codec, render)
                    }
                    if (shown) {
                        VideoDiagnosticTrace.event(colorInstance(), "surface_release", info.presentationTimeUs, releaseStartedNs)
                        pipelineTiming.surfaceRelease(System.nanoTime() - releaseStartedNs)
                        stats.onRendered()
                        liveRendered.incrementAndGet()
                        colorEvidence.rendered(colorGeneration)
                    }
                    if (shown && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
                }
                else -> return false
            }
            return true
        } finally {
            try {
                item.release(codec, false)
            } catch (_: Throwable) {
            }
        }
    }


    /** The worker owns both the Image and EGL; the codec never renders this buffer to a Surface. */
    private fun handleYuvOutput(codec: MediaCodec, item: OutputItem, info: MediaCodec.BufferInfo): Boolean {
        var image: android.media.Image? = null
        var shown = false
        val dequeuedNs = System.nanoTime()
        VideoDiagnosticTrace.event(colorInstance(), "codec_output", info.presentationTimeUs)
        withYuvOutputRelease(codec, item, closeImage = { image?.close() }) {
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ||
                info.size == 0 && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                item.release(codec, false)
                return false
            }
            val surface = outputSurface ?: run { item.release(codec, false); return true }
            image = codec.getOutputImage(item.index)
            if (image == null && info.size == 0) {
                item.release(codec, false)
                return true
            }
            val frame = checkNotNull(image) { "decoder supplied no YUV Image" }
            if (!diagnosticOutputGate.allow()) {
                item.release(codec, false)
                return true
            }
            val color = SoftwareYuvColor.resolve(checkNotNull(yuvConfig), sourceColor,
                softwareYuvOutputFormat ?: codec.outputFormat)
            val renderer = softwareYuvRenderer ?: createDiagnosticRenderer(
                factory = { SoftwareYuvRenderer(surface) },
                applySampling = { newRenderer, cfg ->
                    newRenderer.configureDiagnosticSampling(cfg.points, cfg.patchIndices, cfg.nominals, cfg.frameId, cfg.instanceId)
                },
            ).also { softwareYuvRenderer = it }
            val draw = renderer.draw(frame, color)
            pipelineTiming.draw(draw.copyNanos, draw.drawNanos, draw.textureUploadNanos, draw.drawOnlyNanos, draw.swapNanos, draw.totalNanos)
            VideoDiagnosticTrace.event(colorInstance(), "yuv_draw", info.presentationTimeUs, draw.copyNanos, draw.drawNanos)
            shown = true
        }
        if (shown) {
            val slot = queuedSlotOf(info.presentationTimeUs)
            val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
            val now = System.nanoTime()
            if (queued > 0 && queued !in 1 until resumedAtNanos) {
                pipelineTiming.output(queued, dequeuedNs)
                stats.onDecodeLatency(now - queued); liveDecodeNanos.addAndGet(now - queued); liveDecodeSamples.incrementAndGet()
            }
            stats.onRendered(); liveRendered.incrementAndGet(); colorEvidence.rendered(colorGeneration)
            if (!renderedFrameLogged) {
                renderedFrameLogged = true
                report("first frame rendered")
                report("${if (hardwareYuvEnabled) "hardware-yuv" else "software-yuv"} rendered via app EGL RGB; ${SoftwareYuvColor.resolve(checkNotNull(yuvConfig),
                    sourceColor, softwareYuvOutputFormat ?: codec.outputFormat).description}")
            }
        }
        return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
    }

    private fun fallBackHardwareYuv(reason: String) {
        if (hardwareYuvFallback) return
        hardwareYuvFallback = true
        hardwareYuvStartedNs = 0L
        report("hardware-yuv unavailable: $reason; falling back to Surface decoder")
    }

    private fun failSoftwareYuv(reason: String) {
        if (softwareYuvFailed) return
        softwareYuvFailed = true
        report("software-yuv unavailable: $reason; no hardware or Surface-codec fallback")
    }

    private fun logOutputFormat(format: MediaFormat) {
        softwareYuvOutputFormat = format
        val yuvColor = yuvConfig?.let { SoftwareYuvColor.resolve(it, sourceColor, format) }
        val rendererFields = if (yuvColor == null) emptyMap() else mapOf(
            (if (hardwareYuvEnabled) "hardware-yuv" else "software-yuv") to 1, "yuv-render-range" to if (yuvColor.fullRange) 1 else 2,
            "yuv-render-matrix" to if (yuvColor.bt709) 1 else 2)
        if (yuvColor != null) report("${if (hardwareYuvEnabled) "hardware-yuv" else "software-yuv"} format ${yuvColor.description}; decoded=$format")
        colorEvidence.format(colorGeneration,
            listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_COLOR_STANDARD,
                MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_FORMAT,
                MediaFormat.KEY_STRIDE, MediaFormat.KEY_SLICE_HEIGHT, "crop-left", "crop-top", "crop-right", "crop-bottom")
                .associateWith { format.intOrNull(it) } + rendererFields + mapOf(
                    "hardware-yuv-requested" to if (hardwareYuvEnabled) 1 else 0,
                    "hardware-yuv-fallback" to if (hardwareYuvFallback) 1 else 0,
                    "independent-output" to if (outputThread != null) 1 else 0),
            format.toString() + if (yuvColor != null) " app-EGL ${yuvColor.description}" else "")
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    // Worker only; [outputLock] orders it against the output thread.
    private fun releaseDecoder() {
        val yuvRenderer = softwareYuvRenderer
        softwareYuvRenderer = null
        softwareYuvOutputFormat = null
        runCatching { yuvRenderer?.close() }.onFailure { Log.w(TAG, "YUV EGL release failed", it) }
        backlogRecovery.reset()
        val codec: MediaCodec?
        synchronized(outputLock) {
            codec = decoder
            decoder = null
            // The output thread returns from dequeueOutputBuffer within its timeout and then sees no codec.
            awaitNotDequeuing(codec)
            colorEvidence.invalidate(colorGeneration)
        }
        configuredRate = 0
        configuredMtkMode = MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = false
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    /** Waits, holding [outputLock], until the output thread no longer waits on [codec]; keeps an interrupt. */
    private fun awaitNotDequeuing(codec: MediaCodec?) {
        if (codec == null) return
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OUTPUT_EXIT_WAIT_MS)
        while (dequeuing === codec) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try { outputLock.wait(leftMillis) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joinUninterruptibly(thread: Thread, timeoutMillis: Long) {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (thread.isAlive) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        // The output thread's wait for a decoded frame; a release waits at most this long for it to return.
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_IDLE_WAIT_MS = 10L
        const val OUTPUT_EXIT_WAIT_MS = 500L
        const val INPUT_TIMEOUT_US = 10_000L
        // A paced frame more than this ahead is treated as a bad mapping and shown at once.
        const val MAX_PACING_AHEAD_NS = 1_000_000_000L
        // Longer input gaps are a still screen, not decoding work.
        const val STILL_GAP_NS = 500_000_000L
        // Consecutive frames this far apart in iPhone time are a pause in its frames: about four frame
        // intervals at 30 fps.
        const val SENDER_PAUSE_NS = 120_000_000L
        // Frames before a pause that wait for it: the decoder holds about two, sometimes three.
        const val PAUSE_HELD_FRAMES = 3
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

/** Use the existing hard backlog age only when a newer frame remains in this control segment. */
internal object VideoRecoveryFrameAge {
    fun isObsolete(nowNs: Long, receivedNs: Long, backlog: VideoDecodeQueue.Backlog): Boolean =
        nowNs >= receivedNs && nowNs - receivedNs >= 1_500_000_000L &&
            backlog.pendingFrames > 0 && backlog.newestPendingReceivedNs?.let {
                it <= nowNs && it - receivedNs >= 100_000_000L
            } == true
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }
