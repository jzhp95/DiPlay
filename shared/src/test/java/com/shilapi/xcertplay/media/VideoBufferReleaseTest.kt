package com.shilapi.xcertplay.media

import android.media.MediaCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [VideoBufferReleaseTest.FailingMediaCodec::class])
class VideoBufferReleaseTest {

    @Implements(MediaCodec::class)
    class FailingMediaCodec {
        companion object {
            var calls = 0
            var shouldThrow = false
        }
        @Implementation
        fun releaseOutputBuffer(index: Int, render: Boolean) {
            calls++
            if (shouldThrow) {
                throw IllegalStateException("codec was not in executing state")
            }
        }
        @Implementation
        fun releaseOutputBuffer(index: Int, renderTimestampNs: Long) {
            calls++
            if (shouldThrow) {
                throw IllegalStateException("codec was not in executing state")
            }
        }
    }

    @Test
    fun outputItemPropagatesExceptionAndPreservesIdempotency() {
        FailingMediaCodec.calls = 0
        FailingMediaCodec.shouldThrow = true
        val codec = MediaCodec.createDecoderByType("video/avc")
        val item = OutputItem(
            index = 2, flags = 0, size = 1000, offset = 0,
            presentationTimeUs = 1000L, dequeuedNs = 500L, surfaceGen = 1L
        )
        assertFalse(item.released)

        // First attempt throws
        var caught: Throwable? = null
        try {
            item.release(codec, render = true)
        } catch (e: Throwable) {
            caught = e
        }
        assertNotNull(caught)
        assertTrue(caught is IllegalStateException)
        assertEquals(1, FailingMediaCodec.calls)
        assertTrue(item.released)

        // Subsequent release call in finally must be idempotent and not call codec again
        item.release(codec, render = false)
        assertEquals(1, FailingMediaCodec.calls)
    }

    @Test
    fun yuvReleaseFailurePreventsSuccessAndStillClosesImage() {
        FailingMediaCodec.calls = 0
        FailingMediaCodec.shouldThrow = true
        val codec = MediaCodec.createDecoderByType("video/avc")
        val item = OutputItem(2, 0, 1000, 0, 1000L, 500L, 1L)
        var imageClosed = false
        var successfulFrames = 0
        val failure = runCatching {
            withYuvOutputRelease(codec, item, closeImage = { imageClosed = true }) { Unit }
            successfulFrames++
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(imageClosed)
        assertEquals(0, successfulFrames)
        item.release(codec, false)
        assertEquals(1, FailingMediaCodec.calls)
    }

    @Test
    fun yuvCleanupPreservesRenderingFailureAndAttemptsEveryRelease() {
        FailingMediaCodec.calls = 0
        FailingMediaCodec.shouldThrow = true
        val codec = MediaCodec.createDecoderByType("video/avc")
        val item = OutputItem(2, 0, 1000, 0, 1000L, 500L, 1L)
        val renderingError = IllegalArgumentException("draw failed")
        val imageError = IllegalStateException("image close failed")
        val failure = runCatching {
            withYuvOutputRelease(codec, item, closeImage = { throw imageError }) { throw renderingError }
        }.exceptionOrNull()
        assertSame(renderingError, failure)
        assertSame(imageError, renderingError.suppressed.single())
        assertTrue(imageError.suppressed.single() is IllegalStateException)
        assertEquals(1, FailingMediaCodec.calls)
    }

    @Test
    fun diagnosticSamplingIsSavedOnProductionDecoderBeforeRendererCreation() {
        val type = Class.forName("com.shilapi.xcertplay.media.VideoDecoder")
        val decoder = type.declaredConstructors.single { !it.isSynthetic && it.parameterCount == 21 }
            .apply { isAccessible = true }.newInstance(
                110, null, 1920, 1080, false, {}, { _: String -> },
                VideoColorOverride(), false, SoftwareYuvConfig(), false, null,
                VideoPurpose.DIAGNOSTIC, 0L, { _: Any -> }, { _: VideoDecoderColorEvidence -> },
                0, false, false, false, false,
            )
        try {
            val points = mutableListOf(10 to 20, 30 to 40)
            val patches = mutableListOf<Int?>(0, 1)
            val nominals = mutableListOf<Int?>(16, 235)
            type.getDeclaredMethod("configureDiagnosticSampling", List::class.java, List::class.java,
                List::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(decoder, points, patches, nominals, "frame_1", "capture")
            points.clear(); patches.clear(); nominals.clear()
            val stored = type.getDeclaredField("diagnosticSamplingConfig")
                .apply { isAccessible = true }.get(decoder) as DiagnosticSamplingConfig
            assertEquals(listOf(10 to 20, 30 to 40), stored.points)
            assertEquals(listOf(0, 1), stored.patchIndices)
            assertEquals(listOf(16, 235), stored.nominals)
            assertEquals("frame_1", stored.frameId)
            assertEquals("capture", stored.instanceId)
            assertNull(type.getDeclaredField("softwareYuvRenderer").apply { isAccessible = true }.get(decoder))
            // Exercise the production lazy-creation path without constructing a platform EGL context.
            val renderer = Any()
            var applied: DiagnosticSamplingConfig? = null
            val created = type.declaredMethods.single { it.name == "createDiagnosticRenderer" }
                .apply { isAccessible = true }.invoke(
                    decoder, { renderer },
                    { target: Any, config: DiagnosticSamplingConfig ->
                        assertSame(renderer, target)
                        applied = config
                    },
                )
            assertSame(renderer, created)
            assertSame(stored, applied)
        } finally {
            (decoder as java.io.Closeable).close()
        }
    }

}
