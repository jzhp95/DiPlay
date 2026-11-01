package com.shilapi.xcertplay.media.video

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 独立主屏视频入口适配器。
 * 对接上游 ScreenStream / MediaSink 回调，将数据规范化封装后快速、非阻塞移交给 VideoRuntime。
 */
class VideoIngressAdapter(
    val streamId: SessionVideoId,
    private val runtime: VideoRuntime,
    private val bufferPool: VideoBufferPool? = null,
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() }
) {
    private val active = AtomicBoolean(true)
    private val currentCodec = AtomicReference(CodecType.H264)
    private val configGeneration = AtomicLong(0L)
    private val accessUnitSequence = AtomicLong(0L)
    private val currentConfig = AtomicReference<VideoStreamConfig?>(null)

    fun onCodec(type: Int, codec: CodecType) {
        if (!active.get() || type != streamId.streamRole) return
        currentCodec.set(codec)
    }

    fun onConfig(type: Int, codecData: ByteArray): VideoStreamConfig? {
        if (!active.get() || type != streamId.streamRole || codecData.isEmpty()) return null

        val codec = currentCodec.get()
        val parsedConfig = VideoConfigParser.parse(codec, codecData, streamId)

        val existing = currentConfig.get()
        if (existing != null && existing.codec == parsedConfig.codec && existing.csdHash == parsedConfig.csdHash) {
            // 重复同配置不递增 generation，避免无效重建
            return existing
        }

        configGeneration.incrementAndGet()
        currentConfig.set(parsedConfig)
        runtime.onConfig(parsedConfig)
        return parsedConfig
    }

    fun onFrame(
        type: Int,
        naluBytes: ByteArray,
        senderNanos: Long = 0L,
        arrivalNanos: Long = 0L
    ): OfferResult {
        if (!active.get() || type != streamId.streamRole || naluBytes.isEmpty()) {
            return OfferResult.REJECTED
        }

        val arrivalTime = if (arrivalNanos > 0L) arrivalNanos else clock.nowNs()
        val auId = accessUnitSequence.incrementAndGet()
        val gen = configGeneration.get()

        val unit = AccessUnitNormalizer.normalize(
            payload = naluBytes,
            codec = currentCodec.get(),
            streamId = streamId,
            configGeneration = gen,
            accessUnitId = auId,
            arrivalMonoNs = arrivalTime,
            senderNanos = senderNanos,
            bufferPool = bufferPool
        ) ?: return OfferResult.REJECTED

        val result = runtime.tryOfferAccessUnit(unit)
        if (result != OfferResult.ACCEPTED) {
            // 只有未被接纳时，调用方负责安全释放租约
            unit.lease.close()
        }
        return result
    }

    fun onScreenStreamActive(type: Int, streamActive: Boolean) {
        if (type == streamId.streamRole) {
            active.set(streamActive)
        }
    }

    fun stop() {
        active.set(false)
    }

    fun currentConfigGeneration(): Long = configGeneration.get()
    fun currentConfig(): VideoStreamConfig? = currentConfig.get()
}
