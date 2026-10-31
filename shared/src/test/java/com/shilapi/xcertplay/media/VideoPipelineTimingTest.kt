package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoPipelineTimingTest {
    @Test fun separatesApplicationWaitCodecAndPresentationWork() {
        val timing = VideoPipelineTiming()
        timing.input(1_000_000, 5_000_000, 8_000_000)
        timing.output(8_000_000, 19_000_000)
        timing.draw(2_000_000, 4_000_000, 1_000_000, 1_500_000, 1_500_000, 6_000_000)
        timing.surfaceRelease(1_000_000)
        val v = timing.snapshot()
        assertEquals(4_000_000L, v.applicationWaitNanos)
        assertEquals(3_000_000L, v.inputWaitNanos)
        assertEquals(11_000_000L, v.codecOutputNanos)
        assertEquals(2_000_000L, v.yuvCopyNanos)
        assertEquals(4_000_000L, v.yuvDrawNanos)
        assertEquals(1_000_000L, v.textureUploadNanos)
        assertEquals(1_500_000L, v.drawOnlyNanos)
        assertEquals(1_500_000L, v.swapNanos)
        assertEquals(6_000_000L, v.totalYuvNanos)
        assertEquals(1L, v.inputSamples)
        assertEquals(1L, v.outputSamples)
    }
    @Test fun invalidClockValuesDoNotPolluteTheAverages() {
        val timing = VideoPipelineTiming()
        timing.input(10, 9, 11)
        timing.output(20, 19)
        assertEquals(0L, timing.snapshot().inputSamples)
        assertEquals(0L, timing.snapshot().outputSamples)
    }
}
