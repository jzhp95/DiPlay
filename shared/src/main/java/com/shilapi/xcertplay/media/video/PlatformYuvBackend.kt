package com.shilapi.xcertplay.media.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 独立平台 CPU 可读 YUV 解码后端 (PlatformYuvBackend)。
 * 使用 MediaCodec 配置 COLOR_FormatYUV420Flexible 且不附加 Surface，
 * 从而输出由 Image 提取的 CPU 可读真实平面 (PlaneView)。
 * 实际使用的解码组件名称 (OMX.xxx / c2.xxx) 必须如实登记至帧租约输出描述中。
 */
class PlatformYuvBackend(
    val identity: RuntimeIdentity,
    private val driverFactory: (String, DecoderPreference) -> PlatformCodecDriver = { mime, pref ->
        AndroidMediaCodecDriver.createDecoder(mime, pref)
    }
) : VideoBackend {

    private val lock = ReentrantLock()
    private val driverRef = AtomicReference<PlatformCodecDriver?>(null)
    private val activeConfig = AtomicReference<VideoStreamConfig?>(null)
    private val activeOptions = AtomicReference<BackendOptions?>(null)
    private val isRunning = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private val outputFrameSequence = AtomicLong(0L)

    override fun capabilities(): BackendCapabilities = BackendCapabilities(
        kind = BackendKind.PLATFORM_YUV,
        supportedCodecs = setOf(CodecType.H264, CodecType.HEVC),
        supportsCpuSampling = true, // 核心特性：提供真实的 CPU 平面读取
        supportsDirectSurface = false
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
                val existingDriver = driverRef.get()
                if (existingDriver != null && activeConfig.get() == config && activeOptions.get() == options) {
                    return ConfigureResult.Success(identity.backendInstanceId, capabilities())
                }

                existingDriver?.let {
                    try { it.stop() } catch (_: Throwable) {}
                    try { it.close() } catch (_: Throwable) {}
                }

                val driver = driverFactory(mime, options.decoderPreference)
                val format = MediaFormat.createVideoFormat(mime, config.width, config.height)

                // 强制请求 COLOR_FormatYUV420Flexible (0x7F420888)
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )

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

                // YUV 自绘路径：surface 必须传 null，才能通过 getOutputImage 获得 Image 实例
                driver.configure(format, null, 0)
                driver.start()

                driverRef.set(driver)
                activeConfig.set(config)
                activeOptions.set(options)
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

        val inputBuffer = driver.getInputBuffer(inputIndex) ?: return OfferResult.REJECTED

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
                val image = try {
                    driver.getOutputImage(output.index)
                } catch (t: Throwable) {
                    try { driver.releaseOutputBuffer(output.index, false) } catch (_: Throwable) {}
                    return BackendOutput.Failure(t)
                }

                if (image == null) {
                    try { driver.releaseOutputBuffer(output.index, false) } catch (_: Throwable) {}
                    return BackendOutput.Failure(
                        IllegalStateException("Failed to obtain Image from output buffer index ${output.index}")
                    )
                }

                if (image.planes.size < 3) {
                    try { image.close() } catch (_: Throwable) {}
                    try { driver.releaseOutputBuffer(output.index, false) } catch (_: Throwable) {}
                    return BackendOutput.Failure(
                        IllegalStateException("Invalid YUV planes count: ${image.planes.size}, expected >= 3")
                    )
                }

                val frameKey = FrameKey(
                    identity = identity,
                    accessUnitId = outputFrameSequence.incrementAndGet(),
                    codecPtsUs = output.presentationTimeUs
                )

                val yPlaneLayout = PlaneLayout(
                    rowStride = image.planes[0].rowStride,
                    pixelStride = image.planes[0].pixelStride,
                    width = image.cropWidth,
                    height = image.cropHeight,
                    cropLeft = image.cropLeft,
                    cropTop = image.cropTop,
                    bitDepth = 8
                )

                val uPlaneLayout = PlaneLayout(
                    rowStride = image.planes[1].rowStride,
                    pixelStride = image.planes[1].pixelStride,
                    width = image.cropWidth / 2,
                    height = image.cropHeight / 2,
                    cropLeft = image.cropLeft / 2,
                    cropTop = image.cropTop / 2,
                    bitDepth = 8
                )

                val vPlaneLayout = PlaneLayout(
                    rowStride = image.planes[2].rowStride,
                    pixelStride = image.planes[2].pixelStride,
                    width = image.cropWidth / 2,
                    height = image.cropHeight / 2,
                    cropLeft = image.cropLeft / 2,
                    cropTop = image.cropTop / 2,
                    bitDepth = 8
                )

                val layoutKind = if (image.planes[1].pixelStride == 1 && image.planes[2].pixelStride == 1) {
                    "YUV420_PLANAR"
                } else {
                    "YUV420_SEMIPLANAR"
                }

                val description = DecoderOutputDescription(
                    reportedRange = activeConfig.get()?.colorDeclaration?.range ?: ColorRange.UNKNOWN,
                    reportedStandard = activeConfig.get()?.colorDeclaration?.matrix ?: ColorMatrix.UNKNOWN,
                    planeLayout = layoutKind,
                    actualComponent = driver.componentName,
                    reportAvailability = true
                )

                val lease = MemoryDecodedFrameLease(
                    key = frameKey,
                    outputDescription = description,
                    yPlane = yPlaneLayout,
                    uPlane = uPlaneLayout,
                    vPlane = vPlaneLayout,
                    yBuffer = image.planes[0].buffer,
                    uBuffer = image.planes[1].buffer,
                    vBuffer = image.planes[2].buffer,
                    onRelease = {
                        var firstErr: Throwable? = null
                        try {
                            image.close()
                        } catch (t: Throwable) {
                            firstErr = t
                        }
                        try {
                            driver.releaseOutputBuffer(output.index, false)
                        } catch (t: Throwable) {
                            if (firstErr != null) {
                                firstErr.addSuppressed(t)
                            } else {
                                firstErr = t
                            }
                        }
                        firstErr?.let { throw it }
                    }
                )

                BackendOutput.Frame(lease)
            }
            is CodecDriverOutput.OutputFormatChanged -> {
                val desc = DecoderOutputDescription(
                    reportedRange = ColorRange.UNKNOWN,
                    reportedStandard = ColorMatrix.UNKNOWN,
                    planeLayout = "YUV420Flexible",
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
