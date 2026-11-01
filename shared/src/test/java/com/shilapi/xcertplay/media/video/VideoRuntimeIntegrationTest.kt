package com.shilapi.xcertplay.media.video

import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.Buffer
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoRuntimeIntegrationTest {

    private val identity = RuntimeIdentity(SessionVideoId("integration-s1"), 1L, 1L, 1L, "integration-inst")

    private class IntegrationFakeCodecDriver : PlatformCodecDriver {
        override val componentName: String = "OMX.MTK.VIDEO.DECODER.AVC"
        override val isHardwareAccelerated: Boolean = true
        var nextOutputIndex = 0

        override fun configure(format: MediaFormat, surface: Surface?, flags: Int) {}
        override fun start() {}
        override fun dequeueInputBuffer(timeoutUs: Long): Int = 0
        override fun getInputBuffer(index: Int): ByteBuffer = ByteBuffer.allocate(2048)
        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}

        override fun dequeueOutputBuffer(bufferInfo: android.media.MediaCodec.BufferInfo, timeoutUs: Long): CodecDriverOutput {
            nextOutputIndex++
            return CodecDriverOutput.OutputBuffer(nextOutputIndex, 0, 500, nextOutputIndex * 16_000L, 0)
        }

        override fun getOutputImage(index: Int): CodecOutputImage {
            val yBuf = ByteBuffer.allocateDirect(64 * 48)
            val uBuf = ByteBuffer.allocateDirect(32 * 24)
            val vBuf = ByteBuffer.allocateDirect(32 * 24)
            return object : CodecOutputImage {
                override val width: Int = 64
                override val height: Int = 48
                override val format: Int = 0x7F420888
                override val cropLeft: Int = 0
                override val cropTop: Int = 0
                override val cropWidth: Int = 64
                override val cropHeight: Int = 48
                override val planes: Array<CodecImagePlane> = arrayOf(
                    object : CodecImagePlane {
                        override val buffer: ByteBuffer = yBuf
                        override val rowStride: Int = 64
                        override val pixelStride: Int = 1
                    },
                    object : CodecImagePlane {
                        override val buffer: ByteBuffer = uBuf
                        override val rowStride: Int = 32
                        override val pixelStride: Int = 1
                    },
                    object : CodecImagePlane {
                        override val buffer: ByteBuffer = vBuf
                        override val rowStride: Int = 32
                        override val pixelStride: Int = 1
                    }
                )
                override fun close() {}
            }
        }

        override fun releaseOutputBuffer(index: Int, render: Boolean) {}
        override fun releaseOutputBufferAtTime(index: Int, renderTimestampNs: Long) {}
        override fun setOutputSurface(surface: Surface) {}
        override fun flush() {}
        override fun stop() {}
        override fun close() {}
    }

    private class IntegrationFakeEglDriver : EglDriver {
        var renderedFrames = 0
        override fun initEgl() = true
        override fun createWindowSurface(surface: Surface) = true
        override fun makeCurrent() = true
        override fun setSwapInterval(interval: Int) = true
        override fun swapBuffers(): Boolean {
            renderedFrames++
            return true
        }
        override fun destroySurface() = true
        override fun terminateEgl() = true
        override fun createProgram(vs: String, fs: String) = 1
        override fun useProgram(program: Int) {}
        override fun getUniformLocation(program: Int, name: String) = 1
        override fun getAttribLocation(program: Int, name: String) = 1
        override fun genTextures(n: Int) = intArrayOf(1, 2, 3)
        override fun deleteTextures(textures: IntArray) {}
        override fun deleteProgram(program: Int) {}
        override fun bindTexture(target: Int, texture: Int) {}
        override fun texImage2D(target: Int, level: Int, internalformat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: Buffer?) {}
        override fun uniform1i(location: Int, x: Int) {}
        override fun uniform1f(location: Int, x: Float) {}
        override fun uniformMatrix3fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {}
        override fun viewport(x: Int, y: Int, width: Int, height: Int) {}
        override fun drawArrays(mode: Int, first: Int, count: Int) {}
        override fun close() {}
    }

    @Test
    fun endToEndPipelineExecutesSmoothlyWithoutDrops() {
        val fakeCodec = IntegrationFakeCodecDriver()
        val fakeEgl = IntegrationFakeEglDriver()

        val backend = PlatformYuvBackend(identity) { _, _ -> fakeCodec }
        val renderer = EglFrameRenderer { fakeEgl }
        val slot = LatestFrameSlot()

        // Configure pipeline
        val config = VideoStreamConfig(SessionVideoId("s1"), CodecType.H264, 64, 48)
        val surface = Surface(SurfaceTexture(1))
        renderer.attach(OutputTarget(surface))

        backend.configure(config, OutputTarget(surface), BackendOptions())

        // Submit 3 frames
        for (i in 1..3) {
            val lease = CountingBufferLease(100)
            val unit = testUnit(lease = lease, sourcePtsUs = i * 16_000L)
            assertEquals(OfferResult.ACCEPTED, backend.trySubmit(unit))

            val output = backend.pollOutput(System.nanoTime() + 1_000_000L)
            assertTrue(output is BackendOutput.Frame)
            val frame = (output as BackendOutput.Frame).lease

            // Publish to latest frame slot
            assertTrue(slot.publish(frame))

            val toRender = slot.take()
            assertTrue(toRender != null)

            // Evidence sample before upload
            val sampleRes = ColorEvidenceSampler.sampleBeforeUpload(
                toRender!!,
                ColorDecision.Accepted(ResolvedPixelContract(), VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601)),
                SampleRequest(listOf(SamplePoint(10, 10)))
            )
            assertEquals(1, sampleRes.samples.size)

            // Render
            val receipt = renderer.render(
                toRender,
                ColorDecision.Accepted(ResolvedPixelContract(), VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601))
            )
            assertTrue(receipt.success)

            slot.finish(toRender)
        }

        assertEquals(3, fakeEgl.renderedFrames)

        slot.close()
        backend.awaitReleased(System.nanoTime() + 100_000_000L)
        renderer.stop()
    }
}
