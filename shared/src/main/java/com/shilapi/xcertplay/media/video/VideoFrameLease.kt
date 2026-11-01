package com.shilapi.xcertplay.media.video

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单个像素平面几何布局描述。
 */
data class PlaneLayout(
    val rowStride: Int,
    val pixelStride: Int,
    val width: Int,
    val height: Int,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val bitDepth: Int = 8
) {
    init {
        require(rowStride > 0) { "rowStride must be positive: $rowStride" }
        require(pixelStride > 0) { "pixelStride must be positive: $pixelStride" }
        require(width > 0 && height > 0) { "Dimensions must be positive: ${width}x${height}" }
        require(cropLeft >= 0 && cropTop >= 0) { "Crop origins must be non-negative" }
    }

    /**
     * 计算平面在指定行和列的缓冲区字节偏移量。
     */
    fun offsetOf(x: Int, y: Int): Int {
        val actualX = cropLeft + x
        val actualY = cropTop + y
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw IndexOutOfBoundsException("Coordinate ($x, $y) out of plane bounds (${width}x${height})")
        }
        return actualY * rowStride + actualX * pixelStride
    }
}

/**
 * 像素平面只读视图，严格受其持有帧租约生命周期约束。
 */
class PlaneView(
    val buffer: ByteBuffer,
    val layout: PlaneLayout,
    private val isLeaseActive: () -> Boolean
) {
    /**
     * 读取指定坐标像素分量值（8bit），租约失效或坐标越界抛出异常。
     */
    fun sample8(x: Int, y: Int): Int {
        if (!isLeaseActive()) {
            throw IllegalStateException("Cannot access plane buffer: underlying frame lease has been closed")
        }
        val offset = layout.offsetOf(x, y)
        if (offset < 0 || offset >= buffer.limit()) {
            throw IndexOutOfBoundsException("Calculated offset $offset exceeds buffer limit ${buffer.limit()}")
        }
        return buffer.get(offset).toInt() and 0xFF
    }
}

/**
 * 帧存储类别。
 */
enum class FrameStorageKind {
    MEDIA_CODEC_BUFFER,
    NATIVE_HANDLE,
    DIRECT_PLANES,
    OPAQUE_SURFACE
}

/**
 * 解码帧租约抽象基类，保证 releaseOnce() 严格幂等且异常向上传播。
 */
abstract class AbstractDecodedFrameLease(
    override val key: FrameKey,
    val storageKind: FrameStorageKind,
    val outputDescription: DecoderOutputDescription
) : DecodedFrameLease {

    private val attempts = AtomicInteger(0)
    private val released = AtomicBoolean(false)
    private var firstException: Throwable? = null

    override val releaseAttempts: Int
        get() = attempts.get()

    fun isReleased(): Boolean = released.get()

    /**
     * 执行底层真实的资源释放与归还。
     */
    protected abstract fun doRelease()

    override fun releaseOnce() {
        val currentAttempt = attempts.incrementAndGet()
        if (released.compareAndSet(false, true)) {
            try {
                doRelease()
            } catch (t: Throwable) {
                firstException = t
                throw t
            }
        } else {
            // 后续重复调用不再执行底层归还，但若首次失败则保留首次异常传播
            firstException?.let {
                // 不重复抛出异常阻断正常清理链，仅通过 releaseAttempts 记录
            }
        }
    }
}

/**
 * 基于 MediaCodec 输出缓冲区的帧租约。
 */
class MediaCodecDecodedFrameLease(
    key: FrameKey,
    val bufferIndex: Int,
    outputDescription: DecoderOutputDescription,
    private val onReleaseBuffer: (Int) -> Unit
) : AbstractDecodedFrameLease(key, FrameStorageKind.MEDIA_CODEC_BUFFER, outputDescription) {

    override fun doRelease() {
        onReleaseBuffer(bufferIndex)
    }
}

/**
 * 基于 CPU 可读平面视图的内存帧租约。
 */
class MemoryDecodedFrameLease(
    key: FrameKey,
    outputDescription: DecoderOutputDescription,
    val yPlane: PlaneLayout,
    val uPlane: PlaneLayout,
    val vPlane: PlaneLayout,
    yBuffer: ByteBuffer,
    uBuffer: ByteBuffer,
    vBuffer: ByteBuffer,
    private val onRelease: (() -> Unit)? = null
) : AbstractDecodedFrameLease(key, FrameStorageKind.DIRECT_PLANES, outputDescription) {

    val yView = PlaneView(yBuffer, yPlane) { !isReleased() }
    val uView = PlaneView(uBuffer, uPlane) { !isReleased() }
    val vView = PlaneView(vBuffer, vPlane) { !isReleased() }

    override fun doRelease() {
        onRelease?.invoke()
    }
}
