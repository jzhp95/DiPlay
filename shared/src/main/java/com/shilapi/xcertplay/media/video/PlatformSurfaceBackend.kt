package com.shilapi.xcertplay.media.video

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 独立平台 MediaCodec 硬解直出后端。
 * 画面直接呈现在应用提供的 SurfaceView / Surface 上；如实声明不支持 CPU 原始采样（raw_yuv_unavailable）。
 */
class PlatformSurfaceBackend(
    val identity: RuntimeIdentity,
    private val driverFactory: (String) -> PlatformCodecDriver = { mime ->
        AndroidMediaCodecDriver.createDecoderByType(mime)
    }
) : VideoBackend {

    private val lock = ReentrantLock()
    private val driverRef = AtomicReference<PlatformCodecDriver?>(null)
    private val activeConfig = AtomicReference<VideoStreamConfig?>(null)
    private val activeTarget = AtomicReference<OutputTarget?>(null)
    private val isRunning = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private val outputFrameSequence = AtomicLong(0L)

    override fun capabilities(): BackendCapabilities = BackendCapabilities(
        kind = BackendKind.PLATFORM_SURFACE,
        supportedCodecs = setOf(CodecType.H264, CodecType.HEVC),
        supportsCpuSampling = false, // 直出路径不伪造 CPU 可读 Image
        supportsDirectSurface = true
    )

    override fun configure(
        config: VideoStreamConfig,
        target: OutputTarget,
        options: BackendOptions
    ): ConfigureResult {
        lock.withLock {
            if (isReleased.get()) {
                return ConfigureResult.Rejected("Backend is already released")
            }

            val mime = when (config.codec) {
                CodecType.H264 -> "video/avc"
                CodecType.HEVC -> "video/hevc"
                CodecType.UNKNOWN -> return ConfigureResult.Rejected("Unsupported codec: UNKNOWN")
            }

            return try {
                // 若驱动已存在且 Surface 支持切换
                val existingDriver = driverRef.get()
                if (existingDriver != null && activeConfig.get() == config) {
                    if (target.surface != null && target != activeTarget.get()) {
                        existingDriver.setOutputSurface(target.surface)
                        activeTarget.set(target)
                    }
                    return ConfigureResult.Success(identity.backendInstanceId, capabilities())
                }

                // 重新创建或启动驱动
                existingDriver?.let {
                    try { it.stop() } catch (_: Throwable) {}
                    try { it.close() } catch (_: Throwable) {}
                }

                val driver = driverFactory(mime)
                val format = MediaFormat.createVideoFormat(mime, config.width, config.height)

                // 填入 CSD 参数集
                config.csd.forEachIndexed { idx, bytes ->
                    format.setByteBuffer("csd-$idx", ByteBuffer.wrap(bytes.clone()))
                }

                // 配置颜色提示（若有）
                config.colorDeclaration.let { color ->
                    if (color.range != ColorRange.UNKNOWN) {
                        format.setInteger("color-range", if (color.range == ColorRange.FULL) 1 else 2)
                    }
                    val standard = when (color.matrix) {
                        ColorMatrix.BT709 -> 1
                        ColorMatrix.BT601 -> 4
                        ColorMatrix.BT2020 -> 6
                        else -> null
                    }
                    standard?.let { format.setInteger("color-standard", it) }
                }

                driver.configure(format, target.surface, 0)
                driver.start()

                driverRef.set(driver)
                activeConfig.set(config)
                activeTarget.set(target)
                isRunning.set(true)

                ConfigureResult.Success(identity.backendInstanceId, capabilities())
            } catch (t: Throwable) {
                ConfigureResult.Failed(t)
            }
        }
    }

    override fun trySubmit(unit: EncodedAccessUnit): OfferResult {
        val driver = driverRef.get() ?: return OfferResult.REJECTED
        if (!isRunning.get() || isReleased.get()) return OfferResult.REJECTED

        val inputIndex = driver.dequeueInputBuffer(0L)
        if (inputIndex < 0) {
            return OfferResult.BACKPRESSURE
        }

        val inputBuffer = driver.getInputBuffer(inputIndex)
        if (inputBuffer == null) {
            return OfferResult.REJECTED
        }

        inputBuffer.clear()
        val unitBuffer = unit.lease.buffer
        unitBuffer.rewind()
        val toCopy = Math.min(inputBuffer.remaining(), unitBuffer.remaining())
        val oldLimit = unitBuffer.limit()
        unitBuffer.limit(unitBuffer.position() + toCopy)
        inputBuffer.put(unitBuffer)
        unitBuffer.limit(oldLimit)

        val ptsUs = unit.sourcePtsUs ?: (unit.arrivalMonoNs / 1_000L)
        val flags = if (unit.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

        driver.queueInputBuffer(inputIndex, 0, toCopy, ptsUs, flags)
        unit.lease.close() // 消费所有权
        return OfferResult.ACCEPTED
    }

    override fun pollOutput(deadlineNs: Long): BackendOutput {
        val driver = driverRef.get() ?: return BackendOutput.NoOutput
        if (!isRunning.get() || isReleased.get()) return BackendOutput.NoOutput

        val info = MediaCodec.BufferInfo()
        val timeoutUs = Math.max(0L, (deadlineNs - System.nanoTime()) / 1_000L)

        return when (val output = driver.dequeueOutputBuffer(info, timeoutUs)) {
            is CodecDriverOutput.OutputBuffer -> {
                val frameKey = FrameKey(
                    identity = identity,
                    accessUnitId = outputFrameSequence.incrementAndGet(),
                    codecPtsUs = output.presentationTimeUs
                )
                val description = DecoderOutputDescription(
                    reportedRange = activeConfig.get()?.colorDeclaration?.range ?: ColorRange.UNKNOWN,
                    reportedStandard = activeConfig.get()?.colorDeclaration?.matrix ?: ColorMatrix.UNKNOWN,
                    planeLayout = "OPAQUE_SURFACE",
                    actualComponent = driver.componentName,
                    reportAvailability = true
                )

                val lease = MediaCodecDecodedFrameLease(
                    key = frameKey,
                    bufferIndex = output.index,
                    outputDescription = description,
                    onReleaseBuffer = { idx ->
                        driver.releaseOutputBuffer(idx, true) // 直出呈现
                    }
                )
                BackendOutput.Frame(lease)
            }
            is CodecDriverOutput.OutputFormatChanged -> {
                val desc = DecoderOutputDescription(
                    reportedRange = ColorRange.UNKNOWN,
                    reportedStandard = ColorMatrix.UNKNOWN,
                    planeLayout = "OPAQUE_SURFACE",
                    actualComponent = driver.componentName,
                    reportAvailability = true
                )
                BackendOutput.FormatChanged(desc)
            }
            is CodecDriverOutput.TryAgainLater -> BackendOutput.NoOutput
            is CodecDriverOutput.EndOfStream -> BackendOutput.NoOutput
            is CodecDriverOutput.DriverError -> BackendOutput.Failure(output.cause)
        }
    }

    override fun requestStop() {
        isRunning.set(false)
        driverRef.get()?.let {
            try { it.flush() } catch (_: Throwable) {}
        }
    }

    override fun awaitReleased(deadlineNs: Long): ReleaseState {
        lock.withLock {
            if (isReleased.compareAndSet(false, true)) {
                isRunning.set(false)
                driverRef.getAndSet(null)?.let { driver ->
                    try {
                        driver.stop()
                    } catch (_: Throwable) {}
                    try {
                        driver.close()
                    } catch (_: Throwable) {}
                }
            }
            return if (isReleased.get()) ReleaseState.RELEASED else ReleaseState.BLOCKED
        }
    }
}
