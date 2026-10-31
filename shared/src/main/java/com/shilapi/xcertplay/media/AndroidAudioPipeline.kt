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

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes, var appliedVolume: Float = FULL_VOLUME)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequestHandle? = null
    private var requestedChannel: AudioChannel? = null
    private var mediaVolume = FULL_VOLUME
    private var closed = false
    private var focusGeneration = 0L
    private var currentListener = listenerFor(focusGeneration)
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            // Android may have queued callbacks before a request was abandoned or replaced.
            if (generation != focusGeneration || request == null || active.isEmpty()) {
                // Dropping this silently hides the one window that matters: between a telephony
                // track closing and media reopening, a head unit that keeps focus looks like
                // nothing at all. Say the callback arrived and why it was not acted on.
                runCatching {
                    report("Audio: focus change=$change dropped" +
                        " stale=${generation != focusGeneration} noRequest=${request == null}" +
                        " activeTracks=${active.size}")
                }
                return@synchronized
            }
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (muteMediaOnTransientLoss) {
                        // This reports a temporary Android focus owner, not a confirmed call.
                        Log.i(TAG, "Audio: muting media during transient focus loss")
                        setMediaVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
        applyMediaVolume(track, active.getValue(track))
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        active.clear()
        refreshRequest()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            val abandoned = request
            request = null
            requestedChannel = null
            mediaVolume = FULL_VOLUME
            manager?.let { abandoned?.abandon(it) }
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        focusGeneration += 1
        manager?.let { request?.abandon(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequestHandle(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        request = next
        requestedChannel = primary.channel
        val result = manager?.let(next::request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) setMediaVolume(FULL_VOLUME)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setMediaVolume(volume: Float) {
        mediaVolume = volume
        active.forEach { (track, entry) -> applyMediaVolume(track, entry) }
    }

    private fun applyMediaVolume(track: AudioTrack, entry: Entry) {
        // Telephony and assistant speech remain audible, and navigation never enters this map.
        // This changes only the renderer's relative gain, never Android's user stream volume.
        if (entry.channel != AudioChannel.MEDIA || entry.appliedVolume == mediaVolume) return
        val applied = runCatching { track.setStereoVolume(mediaVolume, mediaVolume) == AudioTrack.SUCCESS }.getOrDefault(false)
        if (applied) entry.appliedVolume = mediaVolume
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "Apple CarPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}

internal class AndroidAudioPipeline(
    context:Context?,private val advancedAudioChannelMapping:Boolean,private val audioFocusEnabled:Boolean,
    private val audioFocusAutoYield:Boolean,private val mediaChannel:Int,private val navigationChannel:Int,
    navigationAttenuationDb:Int,private val navigationStreamType:Int,private val mediaBufferMillis:Int,
    private val onAudioDiagnostic:(String)->Unit,private val onMediaAudioChanged:(Boolean)->Unit,
    private val callEchoCancellation:Boolean,private val callVoiceFilter:Boolean,private val callSpeakerphone:()->Boolean,
    private val nativeHfpCalls:Boolean,val experimentalAudio:ExperimentalAudioConfig,
):MediaSink {
    // Each downlink publishes its own reference; a mic must match that stream and sample rate.
    private val callEchoReferences = ConcurrentHashMap<AudioStreamId, EchoReference>()
    private val nativeHfpRing = NativeHfpRingPolicy(nativeHfpCalls)
    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        audioFocusAutoYield,
        onAudioDiagnostic,
    )

    /** The media-key session may own Android's current focus request for this same sink. */
    fun onMediaAudioFocusChanged(change: Int) {
        audioFocusCoordinator.onExternalFocusChange(change)
    }

    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val closingAudio=ConcurrentHashMap.newKeySet<AudioRenderer>()
    private val closingMicrophones=ConcurrentHashMap.newKeySet<MicrophoneUplink>()
    private fun pruneRetiredWorkers() {
        closingAudio.removeIf { it.exited }
        closingMicrophones.removeIf { it.captureExited() }
    }
    fun resourceCounts(): Map<String, Int> {
        pruneRetiredWorkers()
        return mapOf(
            "audioRenderers" to audioRenderers.size, "microphoneUplinks" to microphoneUplinks.size,
            "retiringAudioRenderers" to closingAudio.size, "retiringMicrophones" to closingMicrophones.size,
        )
    }
    fun released():Boolean {
        pruneRetiredWorkers()
        return audioRenderers.isEmpty() && microphoneUplinks.isEmpty() && closingAudio.isEmpty() && closingMicrophones.isEmpty()
    }
    private fun retire(renderer: AudioRenderer) {
        closingAudio += renderer
        try { renderer.close() } finally { pruneRetiredWorkers() }
    }
    private fun retire(microphone: MicrophoneUplink) {
        closingMicrophones += microphone
        try { microphone.close() } finally { pruneRetiredWorkers() }
    }
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    @Volatile private var navigationAttenuationDb = NavigationAudioGain.clamp(navigationAttenuationDb)

    /** Apply a saved Audio setting to existing navigation tracks without reconnecting CarPlay. */
    fun setNavigationAttenuationDb(db: Int) {
        navigationAttenuationDb = NavigationAudioGain.clamp(db)
        audioRenderers.values.forEach { it.setNavigationAttenuationDb(navigationAttenuationDb) }
    }
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var callSpeaker: CallSpeaker? = null // guarded by audioModeLock
    private var earpieceFallbackTried = false // guarded by audioModeLock
    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        if (suppressNativeHfpRing(id)) return
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (suppressNativeHfpRing(id)) return
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        nativeHfpRing.stop(id)
        synchronized(this) {
            audioRenderers.remove(id)?.let(::retire)
            callEchoReferences.remove(id)
        }
        updateMediaAudio(id, false)
    }

    private fun suppressNativeHfpRing(id: AudioStreamId): Boolean {
        if (!nativeHfpCalls || id.type != 100 || !id.audioType.equals("alert", ignoreCase = true)) return false
        val alreadySuppressed = nativeHfpRing.contains(id)
        val ringing = !alreadySuppressed &&
            runCatching { audioManager?.mode == AudioManager.MODE_RINGTONE }.getOrDefault(false)
        if (!nativeHfpRing.suppress(id, ringing)) return false
        synchronized(this) { audioRenderers.remove(id)?.let(::retire) }
        if (!alreadySuppressed) runCatching {
            onAudioDiagnostic("Audio: 原车 HFP 电话策略：系统正在响铃，抑制重复 CarPlay 铃声；通话音频保留回退")
        }
        return true
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            val speakerphoneCall = false
            if (config.audioType == "telephony" && !speakerphoneCall) enterCommunicationMode(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) {
                MicrophoneUplink(config, onAudioDiagnostic,
                    speakerphoneCall = speakerphoneCall,
                    echoReference = if (config.audioType == TELEPHONY_AUDIO_TYPE) callEchoReferences[id] else null,
                    gainDb = experimentalAudio.microphoneGainDb)
            }
            uplink.onReleased = { closingMicrophones.remove(uplink); Unit }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        try {
            microphoneUplinks.remove(id)?.let(::retire)
        } finally {
            restoreAudioMode(id)
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            savedAudioMode = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            earpieceFallbackTried = false
            val speakerRequested = runCatching(callSpeakerphone).getOrDefault(false)
            if (speakerRequested) callSpeaker = CallSpeaker.on(manager)
            Log.i("xcertplay-usb", "audio mode $savedAudioMode -> ${manager.mode} for telephony stream=$id")
            // The report, not just logcat: a head unit left in the voice path plays no media,
            // and that cannot be diagnosed from a car without adb. A failing report must not
            // disturb the mode, so it is the last thing done and it cannot throw out of here.
            runCatching {
                onAudioDiagnostic("Audio: mode entered communication from=$savedAudioMode" +
                    " now=${manager.mode} stream=$id" +
                    if (speakerRequested) " loudspeaker=${callSpeaker != null}" else "")
            }
        }
    }

    private fun moveCallToLoudspeaker(id: AudioStreamId) {
        val manager = audioManager ?: return
        val line = synchronized(audioModeLock) {
            if (communicationModeStream != id || callSpeaker != null || earpieceFallbackTried) return
            earpieceFallbackTried = true
            callSpeaker = CallSpeaker.on(manager)
            "Audio: call routed to the earpiece; loudspeaker=${callSpeaker != null} stream=$id"
        }
        runCatching { onAudioDiagnostic(line) }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            callSpeaker?.restore()
            callSpeaker = null
            // The report is built inside the try but sent outside it: reporting from in there
            // would let a failing callback be caught as a failed restore.
            val line = try {
                manager.mode = savedAudioMode
                Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
                "Audio: mode restored requested=$savedAudioMode now=${manager.mode} stream=$active"
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not restore audio mode $savedAudioMode", error)
                val currentMode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
                "Audio: mode restore failed requested=$savedAudioMode now=$currentMode" +
                    " error=${error.javaClass.simpleName}"
            }
            runCatching { onAudioDiagnostic(line) }
        }
    }

    fun prepareClose(){nativeHfpRing.clear()}
    fun close(){
        nativeHfpRing.clear()
        // Audio workers close asynchronously; none may reacquire focus after their sink closes.
        audioFocusCoordinator.close()
        audioRenderers.values.forEach(::retire)
        audioRenderers.clear()
        callEchoReferences.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.values.forEach(::retire)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.let(::retire)
        // A replacement owns a fresh ring: the old worker can still finish a blocking write.
        // The car's phone audio state cancels the echo itself; a second canceller would work against it.
        val echoReference = if (callEchoCancellation && format.audioType == TELEPHONY_AUDIO_TYPE && format.sampleRate > 0) {
            EchoReference(format.sampleRate).also { callEchoReferences[id] = it }
        } else {
            callEchoReferences.remove(id)
            null
        }
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            speakerphoneCall = false,
            echoReference = echoReference,
            voiceFilter = callVoiceFilter && format.audioType == TELEPHONY_AUDIO_TYPE,
        ).also {
            it.onReleased = { closingAudio.remove(it); Unit }
            it.experiments = experimentalAudio
            it.setNavigationAttenuationDb(navigationAttenuationDb)
            if (format.audioType == TELEPHONY_AUDIO_TYPE) it.onRoutedToEarpiece = { moveCallToLoudspeaker(id) }
            audioRenderers[id] = it
            // Cover a Settings change made while this renderer was being prepared.
            it.setNavigationAttenuationDb(navigationAttenuationDb)
        }
    }

    private companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
    }
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

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val speakerphoneCall: Boolean = false,
    /** Receives the played call audio so the call microphone can cancel its echo. */
    private val echoReference: EchoReference? = null,
    voiceFilter: Boolean = false,
) : Closeable {
    // Assigned by the sink before start(); never reloaded during an active stream.
    var experiments = ExperimentalAudioConfig()
    private val outputRecovery = AudioOutputRecovery()
    private var recoveryExhaustedReported = false
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val pcmChannels = if (format.channels >= 2) 2 else 1
    private val voiceFilter = if (voiceFilter && format.sampleRate > 0) VoiceFilter(format.sampleRate, pcmChannels) else null

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private var navigationPlaybackToken = NavigationPlayback.open()
    private var actualLegacyStreamType: Int? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    /** Music with sustained decoder backpressure must not keep playing minutes-old packets. */
    private val mediaLagGuard = if (format.audioType == "media") MediaPacketLagGuard(format.rtpClockRate) else null
    @Volatile private var newestRtpSample = 0
    private var lagRecoveryReports = 0
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    @Volatile private var navigationAttenuationDb = 0
    private var appliedNavigationAttenuationDb: Int? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private var bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var clockTrim: MediaClockTrim? = null
    /** Called when this call's track is routed to the earpiece; the sink moves the call to the loudspeaker. */
    @Volatile var onRoutedToEarpiece: (() -> Unit)? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var rebufferPauseReportCount = 0
    private var rebufferRecoverReportCount = 0
    private var queueFullReportCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    val exited:Boolean get()=!thread.isAlive
    @Volatile var onReleased: (() -> Unit)? = null
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!running) return
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
            newestRtpSample = sample
            // Hold short Wi-Fi bursts intact. If compressed audio remains >1.5s behind for
            // another 1.5s, skip only stale undecoded packets to catch up to the live song.
            val removed = mediaLagGuard?.recover(queue, sample, AudioPacket::sample, now) ?: 0
            if (removed > 0) {
                packetsDropped.addAndGet(removed)
                if (lagRecoveryReports < MAX_REBUFFER_REPORTS) {
                    lagRecoveryReports++
                    report("Audio: stale media packet backlog recovered audioType=${format.audioType} " +
                        "packetsDiscarded=$removed remaining=${queue.size}")
                }
            }
        }
        // Serialize enqueue with final cleanup so a late RTP callback cannot refill a retired worker.
        if (!started || !synchronized(queue) { running && queue.offer(AudioPacket(rtp, sample)) }) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                if (queueFullReportCount < MAX_QUEUE_FULL_REPORTS) {
                    queueFullReportCount++
                    report("Audio: queue full audioType=${format.audioType} count=$queueFullReportCount")
                }
            }
        }
    }

    override fun close() {
        running = false
        NavigationPlayback.close(navigationPlaybackToken)
        thread.interrupt()
    }

    private fun run() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        try {
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            if (experiments != ExperimentalAudioConfig()) runCatching {
                report("Audio: experiments mic=${experiments.microphoneGainDb}dB " +
                    "navigation=${if (experiments.navigationEnabled) ExperimentalAudioConfig.clamp(experiments.navigationDb) else 0}dB " +
                    "call=${if (experiments.callEnabled) ExperimentalAudioConfig.clamp(experiments.callDb) else 0}dB recovery=${experiments.outputRecovery}")
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                // Report Settings changes from this worker, never from the UI thread, also between prompts.
                applyNavigationAttenuation()
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) reportFailure(error)
            throw error
        } finally {
            running = false
            runCatching { logStatsIfDue(force = true) }
            release()
            onReleased?.let { callback ->
                onReleased = null
                callback()
            }
        }
    }

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        actualLegacyStreamType = streamOverride.takeIf { it != 0 }
        var attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        if (streamOverride == 0) {
            attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    actualLegacyStreamType = null
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = audioAttributesFor(selection)
                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        if (mappedChannel == AudioChannel.PHONE) {
            val listener = AudioRouting.OnRoutingChangedListener { routing -> checkCallRoute(routing.routedDevice) }
            if (runCatching { built.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper())) }.isSuccess) {
                routingListener = listener
            }
        }
        applyNavigationAttenuation()

        diagnosticStage = "track-attributes"
        trackAttributes = audioTrackAttributesForFocus(built, attributes)
        diagnosticStage = "track-capacity"
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        // Only music plays long enough for the clocks to drift apart; calls keep their echo reference exact.
        clockTrim = if (mappedChannel == AudioChannel.MEDIA) MediaClockTrim(format.sampleRate, (startThresholdBytes / frameBytes).toLong()) else null
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} " +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"}"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    fun setNavigationAttenuationDb(db: Int) {
        // The Settings callback must never wait for release(), which may be blocked in
        // vendor MediaCodec.stop()/release() while holding the renderer monitor.
        navigationAttenuationDb = NavigationAudioGain.clamp(db)
    }

    private fun applyNavigationAttenuation() {
        if (mappedChannel != AudioChannel.NAVIGATION) return
        val requestedDb = navigationAttenuationDb
        // Leave default playback exactly as it was before this setting existed.
        if (requestedDb == 0 && appliedNavigationAttenuationDb == null) return
        if (appliedNavigationAttenuationDb == requestedDb) return
        appliedNavigationAttenuationDb = requestedDb
        runCatching { report("Audio: navigation attenuation=" + requestedDb + "dB applied=true") }
    }

    /** Lowers navigation guidance in the PCM itself; other channels and 0 dB pass through untouched. */
    private fun attenuateNavigation(data: ByteArray, offset: Int, length: Int) {
        if (mappedChannel != AudioChannel.NAVIGATION) return
        NavigationAudioGain.scalePcm16(data, offset, length, navigationAttenuationDb)
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        return if (speakerphoneCall) AudioChannelMapper.playCallOnSpeaker(selection) else selection
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance intentionally takes no focus: it overlays media without ducking it.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        if (queue.isEmpty()) {
            droppedPacketsLogged = false
        }
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        diagnosticStage = "decoder-input"
        val index = DecoderInputPump.acquire(
            running = { running }, drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
            timeoutNs = AUDIO_INPUT_WAIT_NS,
        )
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = MediaCodec.BufferInfo()
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    var copied = false
                    try {
                        if (size > 0) {
                            val output = codec.getOutputBuffer(index)
                            if (output != null) {
                                if (size > pcm.size) pcm = ByteArray(size)
                                output.position(info.offset)
                                output.limit(info.offset + size)
                                output.get(pcm, 0, size)
                                copied = true
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (copied) writePcm(pcm, 0, size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (track == null && !recoverOutput("missing-track", AudioTrack.ERROR_INVALID_OPERATION)) return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        voiceFilter?.process(data, offset, length)
        applyNavigationAttenuation()
        if (experiments.navigationEnabled || experiments.callEnabled) {
            Pcm16Gain.scale(data, offset, length, experiments.playbackGainDb(
                mappedChannel == AudioChannel.NAVIGATION, format.audioType == "telephony", navigationAttenuationDb))
        } else {
            attenuateNavigation(data, offset, length)
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val track = track ?: break
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = try {
                track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            } catch (error: IllegalStateException) {
                if (!experiments.outputRecovery) throw error
                if (recoverOutput("write-exception", AudioTrack.ERROR_INVALID_OPERATION)) continue
                break
            }
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                if (recoverOutput("write-error", count)) continue
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                if (recoverOutput("zero-write", 0)) continue
                break
            }
            outputRecovery.successfulWrite()
            if (count < writeLength) partialWritesThisWindow++
            val framesWritten = count / frameBytes

            written += count
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            try {
                if (playbackStarted) reportNavigationPlayback(track)
                if (playbackStarted) trimClock(track)
                if (!playbackStarted) {
                    prebufferBytes += count
                    if (prebufferBytes >= startThresholdBytes) {
                        startPlayback(track)
                        Log.i(TAG, "audio playback started type=${format.payloadType}")
                    }
                }
                echoReference?.let { reference ->
                    val pending = if (playbackStarted) {
                        totalWrittenFrames - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                    } else {
                        null
                    }
                    reference.append(data, offset + written - count, count, pcmChannels, pending, System.nanoTime())
                }
            } catch (error: IllegalStateException) {
                if (!experiments.outputRecovery) throw error
                if (!recoverOutput("playback-exception", AudioTrack.ERROR_INVALID_OPERATION)) break
            }
        }
    }

    /** Called only by the audio worker. The decoder/session is kept; old queued PCM is never replayed. */
    private fun recoverOutput(reason: String, code: Int): Boolean {
        if (!outputRecovery.shouldRebuild(code, experiments.outputRecovery, running)) {
            if (running && experiments.outputRecovery && outputRecovery.attempts >= 2 && !recoveryExhaustedReported) {
                recoveryExhaustedReported = true
                runCatching { report("Audio: output recovery exhausted audioType=${format.audioType} reason=$reason code=$code") }
            }
            return false
        }
        runCatching { report("Audio: output recovery attempt=${outputRecovery.attempts} audioType=${format.audioType} reason=$reason code=$code") }
        releaseTrack()
        playbackStarted = false
        prebufferBytes = 0
        startThresholdBytes = 0
        fadeApplied = false
        totalWrittenFrames = 0
        lastPlaybackHeadFrames = null
        lastPcmWriteNs = 0
        underrunsAtPlaybackStart = 0
        statsLastUnderruns = 0
        bufferProgress = AudioBufferProgress(frameBytes)
        clockTrim = null
        echoReference?.reset()
        NavigationPlayback.close(navigationPlaybackToken)
        if (!running) return false
        navigationPlaybackToken = NavigationPlayback.open()
        return try {
            createTrack()
            if (!running) { releaseTrack(); false }
            else {
                check(track?.state == AudioTrack.STATE_INITIALIZED) { "replacement AudioTrack unavailable" }
                requestAudioFocus()
                runCatching { report("Audio: output recovery ready audioType=${format.audioType}") }
                true
            }
        } catch (error: Exception) {
            releaseTrack()
            runCatching { report("Audio: output recovery failed audioType=${format.audioType} ${MediaFailureSummary.describe(error)}") }
            false
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.underrunCount
        track.play()
        playbackStarted = true
        reportNavigationPlayback(track)
        if (rebufferCount > 0 && rebufferRecoverReportCount < MAX_REBUFFER_REPORTS) {
            rebufferRecoverReportCount++
            report("Audio: rebuffer recovered audioType=${format.audioType} rebufferCount=$rebufferCount")
        }
    }

    private fun reportNavigationPlayback(track: AudioTrack) {
        if (!running) return
        val priorityVoice = mappedChannel == AudioChannel.PHONE || mappedChannel == AudioChannel.ASSISTANT
        if (mappedChannel != AudioChannel.NAVIGATION && !priorityVoice) return
        // The optional key-routing hint must never interrupt audio on unusual vendor ROMs.
        runCatching {
            val pendingFrames = bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes
            val bufferedMillis = (pendingFrames * 1000L + format.sampleRate - 1L) / format.sampleRate
            NavigationPlayback.played(navigationPlaybackToken, actualLegacyStreamType, bufferedMillis, priorityVoice)
        }
    }

    private fun checkCallRoute(device: AudioDeviceInfo?) {
        if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) onRoutedToEarpiece?.invoke()
    }

    private fun trimClock(track: AudioTrack) {
        val trim = clockTrim ?: return
        val now = System.nanoTime()
        val compressedRtpFrames = mediaLagGuard?.pendingFrames(queue, newestRtpSample, AudioPacket::sample)
            ?: 0L
        val compressedPcmFrames = compressedRtpFrames * format.sampleRate /
            format.rtpClockRate.coerceAtLeast(1)
        trim.sample(bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes,
            now, compressedPcmFrames)
        val rate = trim.rateDue(now) ?: return
        val result = runCatching { track.setPlaybackRate(rate) }.getOrDefault(AudioTrack.ERROR)
        if (result != AudioTrack.SUCCESS) {
            // The track keeps its rate; music then plays as it did before the trim.
            clockTrim = null
            report("Audio: clock trim off audioType=${format.audioType} rate=$rate result=$result")
        }
    }

    private fun maintainPlaybackBuffer() {
        try {
            maintainTrackBuffer()
        } catch (error: IllegalStateException) {
            if (!experiments.outputRecovery) throw error
            recoverOutput("buffer-exception", AudioTrack.ERROR_INVALID_OPERATION)
        }
    }

    private fun maintainTrackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition,
                startThresholdBytes / 2L)) {
            // The hardware buffer has starved below the recovery floor. Pause without flushing
            // or discarding PCM, then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            // Pausing retains queued PCM. Count it toward the restart threshold so a
            // blocking write cannot fill the paused track before we call play().
            prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                .coerceAtMost(startThresholdBytes.toLong()).toInt()
            lastPcmWriteNs = System.nanoTime()
            rebufferCount++
            if (rebufferPauseReportCount < MAX_REBUFFER_REPORTS) {
                rebufferPauseReportCount++
                val retainedMs = if (bytesPerSecond > 0) prebufferBytes * 1000L / bytesPerSecond else 0L
                report("Audio: rebuffer paused audioType=${format.audioType} rebufferCount=$rebufferCount retainedMs=$retainedMs")
            }
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        try {
            logTrackStatsIfDue(force)
        } catch (error: IllegalStateException) {
            if (!experiments.outputRecovery) throw error
            recoverOutput("stats-exception", AudioTrack.ERROR_INVALID_OPERATION)
        }
    }

    private fun logTrackStatsIfDue(force: Boolean) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.underrunCount ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        // A route decided before the listener was added reaches no callback; the stats see it.
        if (mappedChannel == AudioChannel.PHONE) runCatching { checkCallRoute(currentTrack?.routedDevice) }
        // "PHONE " marks the iPhone's own log lines, which the report drops, and it dropped every
        // call's playback line with it (#647).
        val channelName = if (mappedChannel == AudioChannel.PHONE) "CALL" else mappedChannel?.name
        val line = "audio stats audioType=${format.audioType} channel=$channelName " +
            "routeType=${currentTrack?.routedDevice?.type ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
            "playbackRate=${clockTrim?.rate ?: -1} ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        NavigationPlayback.close(navigationPlaybackToken)
        abandonAudioFocus()
        val codec = codec
        this.codec = null
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
        try {
            releaseTrack()
        } finally {
            synchronized(queue) {
                running = false
                queue.clear()
            }
            pcm = ByteArray(0)
        }
    }

    private fun releaseTrack() {
        abandonAudioFocus()
        val track = track
        this.track = null
        if (track != null) {
            routingListener?.let { listener -> runCatching { track.removeOnRoutingChangedListener(listener) } }
            routingListener = null
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_INPUT_WAIT_NS = 100_000_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "Apple CarPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val MAX_REBUFFER_REPORTS = 5
        const val MAX_QUEUE_FULL_REPORTS = 5
    }
}
