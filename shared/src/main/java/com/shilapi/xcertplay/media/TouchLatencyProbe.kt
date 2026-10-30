package com.shilapi.xcertplay.media

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L
    @Volatile var maxSendNs = 0L

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Arrival order cannot associate a frame with a touch; physical/causal latency is unknown. */
    fun onFrame(nowNs:Long):Long {pendingTouchNs=0L;return -1L}
}
