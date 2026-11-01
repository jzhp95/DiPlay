package com.shilapi.xcertplay.media.video

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单元测试用可控单调时钟。
 */
class FakeClock(startNs: Long = 0L) : MonotonicClock {
    private var currentNs: Long = startNs
    override fun nowNs(): Long = currentNs

    fun advanceNs(deltaNs: Long) {
        require(deltaNs >= 0) { "Time cannot move backwards: $deltaNs" }
        currentNs += deltaNs
    }

    fun advance(deltaNs: Long) = advanceNs(deltaNs)
}

/**
 * 单元测试用可计数缓冲租约。
 */
class CountingBufferLease(
    override val sizeBytes: Int,
    initialBuffer: ByteBuffer = ByteBuffer.allocate(sizeBytes)
) : BufferLease {
    private val closed = AtomicBoolean(false)
    var closeCalls = 0
        private set

    override val buffer: ByteBuffer = initialBuffer

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeCalls++
        }
    }
}

/**
 * 单元测试用解码帧租约。
 */
class TestDecodedFrameLease(
    override val key: FrameKey,
    private val onRelease: (() -> Unit)? = null
) : DecodedFrameLease {
    private val closed = AtomicBoolean(false)
    override var releaseAttempts = 0
        private set

    override fun releaseOnce() {
        releaseAttempts++
        if (closed.compareAndSet(false, true)) {
            onRelease?.invoke()
        }
    }
}

/**
 * 构造测试用访问单元。
 */
fun testUnit(
    lease: BufferLease = CountingBufferLease(16),
    streamId: SessionVideoId = SessionVideoId("test-session"),
    configGeneration: Long = 1L,
    arrivalMonoNs: Long = 1_000_000L,
    isKeyFrame: Boolean = false,
    accessUnitId: Long = 1L,
    sourcePtsUs: Long? = null
): EncodedAccessUnit = EncodedAccessUnit(
    accessUnitId = accessUnitId,
    streamId = streamId,
    configGeneration = configGeneration,
    lease = lease,
    sourcePtsUs = sourcePtsUs,
    arrivalMonoNs = arrivalMonoNs,
    isKeyFrame = isKeyFrame
)

/**
 * 构造测试用解码帧。
 */
fun testFrame(
    key: FrameKey = FrameKey(
        RuntimeIdentity(
            SessionVideoId("test-session"),
            configGeneration = 1L,
            decoderGeneration = 1L,
            surfaceGeneration = 1L,
            backendInstanceId = "backend-test-01"
        ),
        accessUnitId = 1L,
        codecPtsUs = 1000L
    ),
    onRelease: (() -> Unit)? = null
): TestDecodedFrameLease = TestDecodedFrameLease(key, onRelease)
