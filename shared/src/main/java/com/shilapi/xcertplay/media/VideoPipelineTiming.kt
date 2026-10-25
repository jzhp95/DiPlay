package com.shilapi.xcertplay.media

/** Cumulative app measurements, never physical scan-out or touch-to-photon latency. */
data class VideoPipelineCounters(
    val applicationWaitNanos: Long = 0, val inputWaitNanos: Long = 0, val inputSamples: Long = 0,
    val codecOutputNanos: Long = 0, val outputSamples: Long = 0,
    val yuvCopyNanos: Long = 0, val yuvDrawNanos: Long = 0, val yuvSamples: Long = 0,
    val surfaceReleaseNanos: Long = 0, val surfaceSamples: Long = 0,
)
internal class VideoPipelineTiming {
    private var value = VideoPipelineCounters()
    @Synchronized fun input(received: Long, acquire: Long, queued: Long) {
        if (received <= 0 || acquire < received || queued < acquire) return
        value = value.copy(applicationWaitNanos = value.applicationWaitNanos + acquire - received,
            inputWaitNanos = value.inputWaitNanos + queued - acquire, inputSamples = value.inputSamples + 1)
    }
    @Synchronized fun output(queued: Long, dequeued: Long) {
        if (queued <= 0 || dequeued < queued) return
        value = value.copy(codecOutputNanos = value.codecOutputNanos + dequeued - queued, outputSamples = value.outputSamples + 1)
    }
    @Synchronized fun draw(copy: Long, draw: Long) {
        value = value.copy(yuvCopyNanos = value.yuvCopyNanos + copy.coerceAtLeast(0),
            yuvDrawNanos = value.yuvDrawNanos + draw.coerceAtLeast(0), yuvSamples = value.yuvSamples + 1)
    }
    @Synchronized fun surfaceRelease(nanos: Long) {
        value = value.copy(surfaceReleaseNanos = value.surfaceReleaseNanos + nanos.coerceAtLeast(0),
            surfaceSamples = value.surfaceSamples + 1)
    }
    @Synchronized fun snapshot() = value
}
internal data class YuvDrawTiming(val copyNanos: Long, val drawNanos: Long)
