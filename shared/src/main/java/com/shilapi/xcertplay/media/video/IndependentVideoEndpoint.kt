package com.shilapi.xcertplay.media.video

import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One worker owns the codec, frame leases and EGL context. Ingress never waits for decoding. */
class IndependentVideoEndpoint(
    override val streamRole: Int = 110,
    val sessionId: String = "independent-session",
    val policy: VideoRuntimePolicy = VideoRuntimePolicy(),
    val backendFactory: ((VideoStreamConfig, OutputTarget, BackendOptions) -> VideoBackend)? = null,
    private val rendererFactory: () -> EglFrameRenderer = { EglFrameRenderer() },
    private val diagnostic: (String) -> Unit = {},
) : SessionVideoEndpoint {
    override val implementation = VideoImplementation.INDEPENDENT
    private val ingressLock = Any()
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicLong()
    private val queue = EncodedQueue()
    private val commands = LinkedBlockingQueue<() -> Unit>()
    private val release = SettableReleaseTicket()
    private val detachTickets = java.util.concurrent.ConcurrentLinkedQueue<SettableReleaseTicket>()
    private var codec = CodecType.H264
    private var configGeneration = 0L
    private var surfaceGeneration = 0L
    private var declaredConfig: VideoStreamConfig? = null
    private var desiredSurface: Surface? = null
    @Volatile private var recovery: (() -> Unit)? = null
    @Volatile private var description: DecoderOutputDescription? = null
    @Volatile private var state = RuntimeSnapshot(sessionId, streamRole,
        requestedImplementation = VideoImplementation.INDEPENDENT)
    // Worker-owned state.
    private var config: VideoStreamConfig? = null
    private var target: OutputTarget? = null
    private var backend: VideoBackend? = null
    private var renderer: EglFrameRenderer? = null
    private var pending: EncodedAccessUnit? = null
    private var waitingForKey = true
    private var decoderGeneration = 0L
    private val worker = Thread(::run, "independent-video-$streamRole").apply { isDaemon = true; start() }

    fun setRecoveryHandler(handler: () -> Unit) { recovery = handler }
    fun onStreamActive(active: Boolean) = synchronized(ingressLock) {
        if (closed.get() || active) return
        declaredConfig = null
        codec = CodecType.H264
        recovery = null
        val generation = ++configGeneration
        queue.resetForConfig(generation).forEach { it.lease.close() }
        commands.offer {
            retireBackend()
            config = null
            state = state.copy(configGeneration = generation)
            report("state=stream_stopped")
        }
        Unit
    }
    fun outputDescription() = description
    fun sourceDescription() = synchronized(ingressLock) { declaredConfig }
    private fun report(message: String) { runCatching { diagnostic("Video runtime: requested=INDEPENDENT actual=INDEPENDENT role=$streamRole session=$sessionId $message") } }

    override fun onCodec(codec: Int) = synchronized(ingressLock) {
        if (!closed.get()) this.codec = when (codec) { 0 -> CodecType.H264; 1 -> CodecType.HEVC; else -> CodecType.UNKNOWN }
    }

    override fun onConfig(configBytes: ByteArray) = synchronized(ingressLock) {
        if (closed.get()) return
        val parsed = VideoConfigParser.parse(codec, configBytes, SessionVideoId(sessionId, streamRole))
        if (parsed == declaredConfig) return
        declaredConfig = parsed
        val generation = ++configGeneration
        queue.resetForConfig(generation).forEach { it.lease.close() }
        commands.offer {
            retireBackend()
            config = parsed
            state = state.copy(configGeneration = generation)
            configureBackend()
        }
        Unit
    }

    override fun onFrame(frameBytes: ByteArray, ptsUs: Long, isKey: Boolean) = synchronized(ingressLock) {
        if (closed.get() || declaredConfig == null) return
        val unit = AccessUnitNormalizer.normalize(frameBytes, codec, SessionVideoId(sessionId, streamRole),
            configGeneration, sequence.incrementAndGet(), System.nanoTime(), senderNanos = ptsUs * 1000L) ?: return
        if (queue.offer(unit) != OfferResult.ACCEPTED) {
            unit.lease.close()
            queue.resetForConfig(configGeneration).forEach { it.lease.close() }
            commands.offer { pending?.lease?.close(); pending = null; waitingForKey = true; recovery?.invoke() }
        }
    }

    override fun attachSurface(surface: Surface) = synchronized(ingressLock) {
        if (closed.get() || desiredSurface === surface) return
        desiredSurface = surface
        val generation = ++surfaceGeneration
        commands.offer {
            retireBackend()
            target = OutputTarget(surface, streamRole, generation)
            state = state.copy(surfaceGeneration = generation)
            configureBackend()
        }
        Unit
    }

    /** A stale wrapper must never detach a replacement surface. */
    fun detachSurface(surface: Surface): ReleaseTicket = synchronized(ingressLock) {
        if (closed.get()) return release
        if (desiredSurface !== surface) {
            // An earlier attach command may still be releasing this wrapper: acknowledge after that barrier.
            val ticket = SettableReleaseTicket()
            detachTickets.add(ticket)
            commands.offer { ticket.complete(ReleaseState.RELEASED); detachTickets.remove(ticket) }
            return ticket
        }
        detachSurface()
    }

    override fun detachSurface(): ReleaseTicket = synchronized(ingressLock) {
        if (closed.get()) return release
        desiredSurface = null
        val ticket = SettableReleaseTicket()
        detachTickets.add(ticket)
        commands.offer {
            retireBackend()
            target = null
            ticket.complete(ReleaseState.RELEASED)
            detachTickets.remove(ticket)
        }
        ticket
    }

    fun awaitReleased(timeoutMillis: Long): Boolean =
        release.awaitUntil(System.nanoTime() + timeoutMillis * 1_000_000L) == ReleaseState.RELEASED
    fun whenReleased(callback: () -> Unit) { release.onComplete { if (it == ReleaseState.RELEASED) callback() } }
    fun refreshPicture() { if (!closed.get()) commands.offer { recovery?.invoke() } }

    private fun configureBackend() {
        val cfg = config ?: return
        val output = target ?: return
        val options = BackendOptions(allowHardware = policy.decoderPreference != DecoderPreference.SOFTWARE,
            decoderPreference = policy.decoderPreference)
        val kind = policy.preferredBackend ?: BackendKind.PLATFORM_SURFACE
        val identity = RuntimeIdentity(cfg.streamId, state.configGeneration, ++decoderGeneration,
            output.surfaceGeneration, "$sessionId-$streamRole-$decoderGeneration")
        val created = backendFactory?.invoke(cfg, output, options) ?: when (kind) {
            BackendKind.PLATFORM_SURFACE -> PlatformSurfaceBackend(identity, decoderPreference = policy.decoderPreference)
            BackendKind.PLATFORM_YUV -> PlatformYuvBackend(identity)
            BackendKind.NATIVE_YUV -> error("native_yuv_unavailable")
        }
        backend = created // own the backend even when configure fails
        when (val result = created.configure(cfg, output, options)) {
            is ConfigureResult.Success -> {
                if (!result.actualCapabilities.supportsDirectSurface) {
                    renderer = rendererFactory()
                    renderer!!.attach(output)
                }
                waitingForKey = true
                state = state.copy(actualBackend = result.actualCapabilities.kind, decoderGeneration = decoderGeneration, lastError = null)
                report("backend=${state.actualBackend} generation=$decoderGeneration source=${cfg.colorDeclaration} fallback=none colorVerified=false")
                recovery?.invoke()
            }
            is ConfigureResult.Rejected -> error(result.reason)
            is ConfigureResult.Failed -> throw result.cause
        }
    }

    private fun retireBackend() {
        pending?.lease?.close(); pending = null
        val old = backend
        val codecReleased = if (old == null) true else {
            old.requestStop()
            old.awaitReleased(System.nanoTime() + 500_000_000L) == ReleaseState.RELEASED
        }
        val eglReleased = renderer?.stop()?.state() != ReleaseState.BLOCKED
        check(codecReleased && eglReleased) { "backend_release_unconfirmed" }
        backend = null
        renderer = null
        description = null
        state = state.copy(actualBackend = null)
    }

    private fun pump() {
        val b = backend ?: return
        if (pending == null) pending = queue.poll()
        pending?.let { unit ->
            if (unit.configGeneration != state.configGeneration || (waitingForKey && !unit.isKeyFrame)) {
                unit.lease.close(); pending = null
            } else when (b.trySubmit(unit)) {
                OfferResult.ACCEPTED -> { pending = null; waitingForKey = false }
                OfferResult.REJECTED -> { unit.lease.close(); pending = null; waitingForKey = true; recovery?.invoke() }
                OfferResult.BACKPRESSURE -> Unit
            }
        }
        when (val output = b.pollOutput(System.nanoTime())) {
            is BackendOutput.Frame -> {
                try {
                    val observed = (output.lease as? AbstractDecodedFrameLease)?.outputDescription
                    if (observed != null && observed != description) {
                        description = observed
                        report("backend=${state.actualBackend} decoderOutput=$observed fallback=none colorVerified=false")
                    }
                    renderer?.let { r ->
                        val source = config!!.colorDeclaration.let { if (policy.renderMatrix == ColorMatrix.UNKNOWN) it else it.copy(matrix = policy.renderMatrix) }
                        val decision = PixelContractResolver.resolve(source,
                            observed ?: DecoderOutputDescription(), RenderTargetDescription(), policy)
                        val receipt = r.render(output.lease, decision)
                        check(receipt.success) { "render_failed:${receipt.errorStage}" }
                    }
                } finally { output.lease.releaseOnce() }
            }
            is BackendOutput.FormatChanged -> { description = output.description; report("decoderOutput=${output.description} colorVerified=false") }
            is BackendOutput.Failure -> throw output.cause
            BackendOutput.NoOutput -> Unit
        }
    }

    private fun run() {
        try {
            report("state=created fallback=none")
            while (!closed.get()) {
                commands.poll(2, TimeUnit.MILLISECONDS)?.invoke()
                while (true) (commands.poll() ?: break).invoke()
                pump()
            }
        } catch (error: Throwable) {
            state = state.copy(lastError = "${error.javaClass.simpleName}:${error.message}")
            report("state=failed error=${state.lastError} fallback=none")
        } finally {
            synchronized(ingressLock) { closed.set(true); queue.close().forEach { it.lease.close() } }
            val released = runCatching { retireBackend() }.isSuccess
            state = state.copy(releaseState = if (released) ReleaseState.RELEASED else ReleaseState.BLOCKED)
            detachTickets.forEach { it.complete(state.releaseState) }
            commands.clear()
            release.complete(state.releaseState)
            report("state=stopped release=${state.releaseState}")
        }
    }

    override fun snapshot(): RuntimeSnapshot {
        val q = queue.snapshot()
        return state.copy(inputQueueDepth = q.depth, appOwnedBytes = q.bytes)
    }
    override fun close() { synchronized(ingressLock) { closed.set(true) } }
}
