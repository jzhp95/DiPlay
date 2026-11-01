package com.shilapi.xcertplay.media.video

import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 全新独立视频运行管线端点 (IndependentVideoEndpoint)。
 * 自包含 Ingress 解析、有界排队、协调器状态机与解码后端，杜绝调用旧模块。
 */
class IndependentVideoEndpoint(
    override val streamRole: Int = 110,
    val sessionId: String = "independent-session",
    val policy: VideoRuntimePolicy = VideoRuntimePolicy(),
    val backendFactory: (VideoStreamConfig, OutputTarget, BackendOptions) -> VideoBackend = { config, target, options ->
        if (target.surface != null && options.allowHardware) {
            PlatformSurfaceBackend(
                RuntimeIdentity(SessionVideoId(sessionId, streamRole), 1L, 1L, 1L, "platform-surface-auto")
            )
        } else {
            PlatformYuvBackend(
                RuntimeIdentity(SessionVideoId(sessionId, streamRole), 1L, 1L, 1L, "platform-yuv-auto")
            )
        }
    }
) : SessionVideoEndpoint {

    private class DirectBufferLease(override val buffer: ByteBuffer) : BufferLease {
        override val sizeBytes: Int = buffer.capacity()
        override fun close() {}
    }

    override val implementation: VideoImplementation = VideoImplementation.INDEPENDENT

    private val isClosed = AtomicBoolean(false)
    private val clock = MonotonicClock { System.nanoTime() }
    private val frameSequence = AtomicLong(0L)
    private val queue = EncodedQueue(8, 8L * 1024 * 1024, clock)
    private var activeConfig: VideoStreamConfig? = null
    private var activeSurface: Surface? = null
    private var activeBackend: VideoBackend? = null

    override fun onConfig(configBytes: ByteArray) {
        if (isClosed.get()) return
        val parsed = VideoConfigParser.parse(
            codec = CodecType.H264,
            codecData = configBytes,
            streamId = SessionVideoId(sessionId, streamRole)
        )
        activeConfig = parsed
        queue.resetForConfig(parsed.streamId.streamGeneration)
    }

    override fun onCodec(codec: Int) {
        // 编码格式类型标记
    }

    override fun onFrame(frameBytes: ByteArray, ptsUs: Long, isKey: Boolean) {
        if (isClosed.get()) return
        val buf = ByteBuffer.allocate(frameBytes.size)
        buf.put(frameBytes)
        buf.flip()
        val lease = DirectBufferLease(buf)

        val unit = EncodedAccessUnit(
            accessUnitId = frameSequence.incrementAndGet(),
            streamId = SessionVideoId(sessionId, streamRole),
            configGeneration = activeConfig?.streamId?.streamGeneration ?: 1L,
            lease = lease,
            sourcePtsUs = if (ptsUs > 0) ptsUs else null,
            arrivalMonoNs = clock.nowNs(),
            isKeyFrame = isKey,
            randomAccessType = if (isKey) RandomAccessType.IDR else RandomAccessType.NONE
        )
        val offer = queue.offer(unit)
        if (offer != OfferResult.ACCEPTED) {
            lease.close()
        }
    }

    override fun attachSurface(surface: Surface) {
        activeSurface = surface
        activeConfig?.let { cfg ->
            val backend = backendFactory(cfg, OutputTarget(surface), BackendOptions())
            backend.configure(cfg, OutputTarget(surface), BackendOptions())
            activeBackend = backend
        }
    }

    override fun detachSurface(): ReleaseTicket {
        activeSurface = null
        val b = activeBackend
        activeBackend = null
        if (b != null) {
            b.requestStop()
            val state = b.awaitReleased(System.nanoTime() + 500_000_000L)
            val ticket = SettableReleaseTicket(state)
            if (state != ReleaseState.PENDING) ticket.complete(state)
            return ticket
        }
        val ticket = SettableReleaseTicket(ReleaseState.RELEASED)
        ticket.complete(ReleaseState.RELEASED)
        return ticket
    }

    override fun snapshot(): RuntimeSnapshot {
        return RuntimeSnapshot(
            sessionId = sessionId,
            streamRole = streamRole,
            requestedImplementation = VideoImplementation.INDEPENDENT,
            actualImplementation = VideoImplementation.INDEPENDENT,
            actualBackend = activeBackend?.capabilities()?.kind,
            releaseState = if (isClosed.get()) ReleaseState.RELEASED else ReleaseState.PENDING
        )
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            queue.close()
            activeBackend?.requestStop()
            activeBackend?.awaitReleased(System.nanoTime() + 500_000_000L)
            activeBackend = null
        }
    }
}
