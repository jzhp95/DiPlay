package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer

internal data class UsbReadQueueResult(val queued: Boolean, val firstBytes: Int, val fallbackBytes: Int? = null)

/**
 * The false-return fallback is a compatibility hypothesis for explicit vendor queue rejection.
 * Android 9 UsbRequest.queue(ByteBuffer) accepts any size and clears its queued state on false:
 * https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/hardware/usb/UsbRequest.java
 * Large read regions may retry at smaller sizes down to 2 KiB, even when a cached successful size reduced the first submission. Short regions are never retried.
 * Call under the pipe's state lock, including publication, queueing and the open-state checks.
 */
internal class UsbReadQueuePolicy {
    private var successfulLimit: Int? = null

    fun queue(buffer: ByteBuffer, checkOpen: () -> Unit, submit: (ByteBuffer) -> Boolean): UsbReadQueueResult {
        require(buffer.isDirect && !buffer.isReadOnly) { "USB read requires a writable direct buffer" }
        checkOpen()
        val position = buffer.position()
        val originalLimit = buffer.limit()
        val originalRemaining = buffer.remaining()
        val firstBytes = minOf(
            originalRemaining, successfulLimit ?: originalRemaining,
        )
        buffer.limit(position + firstBytes)
        if (submitUnchanged(buffer, position, firstBytes, submit)) {
            return UsbReadQueueResult(true, firstBytes)
        }
        if (originalRemaining <= FALLBACK_BYTES || firstBytes <= MIN_FALLBACK_BYTES) {
            buffer.limit(originalLimit)
            return UsbReadQueueResult(false, firstBytes)
        }

        var size = if (firstBytes > FALLBACK_BYTES) FALLBACK_BYTES else {
            maxOf(MIN_FALLBACK_BYTES, firstBytes / 2)
        }
        var lastAttempt = firstBytes
        while (size >= MIN_FALLBACK_BYTES) {
            checkOpen()
            buffer.limit(position + size)
            lastAttempt = size
            if (submitUnchanged(buffer, position, size, submit)) {
                successfulLimit = size
                return UsbReadQueueResult(true, firstBytes, size)
            }
            size /= 2
        }
        buffer.limit(originalLimit)
        return UsbReadQueueResult(false, firstBytes, lastAttempt)
    }

    /** A false queue result is retryable only while its buffer range remains unchanged. */
    private fun submitUnchanged(
        buffer: ByteBuffer,
        position: Int,
        size: Int,
        submit: (ByteBuffer) -> Boolean,
    ): Boolean {
        if (submit(buffer)) return true
        check(buffer.position() == position && buffer.limit() == position + size) {
            "Rejected USB queue changed its buffer state"
        }
        return false
    }

    companion object {
        private const val FALLBACK_BYTES = 16 * 1024
        private const val MIN_FALLBACK_BYTES = 2 * 1024
    }
}
