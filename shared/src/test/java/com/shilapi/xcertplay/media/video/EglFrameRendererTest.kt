package com.shilapi.xcertplay.media.video

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.Buffer
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EglFrameRendererTest {

    private class FakeEglDriver : EglDriver {
        var initialized = false
        var windowSurfaceCreated = false
        var madeCurrent = false
        var swapInterval: Int? = null
        var swapBuffersCallCount = 0
        var swapBuffersResult = true
        var surfaceDestroyed = false
        var terminated = false

        var programCreated = 0
        var activeProgram = 0
        var texturesGenerated = 0
        var texturesDeleted = false
        var programDeleted = false
        val texImage2DCalls = mutableListOf<Int>() // internalFormat list
        val uniformFloatCalls = mutableMapOf<Int, Float>()
        var uniformMatrixCalled = false
        var drawArraysCalled = false

        override fun initEgl(): Boolean {
            initialized = true
            return true
        }

        override fun createWindowSurface(surface: Surface): Boolean {
            windowSurfaceCreated = true
            return true
        }

        override fun makeCurrent(): Boolean {
            madeCurrent = true
            return true
        }

        override fun setSwapInterval(interval: Int): Boolean {
            swapInterval = interval
            return true
        }

        override fun swapBuffers(): Boolean {
            swapBuffersCallCount++
            return swapBuffersResult
        }

        override fun destroySurface(): Boolean {
            surfaceDestroyed = true
            return true
        }

        override fun terminateEgl(): Boolean {
            terminated = true
            return true
        }

        override fun createProgram(vertexShader: String, fragmentShader: String): Int {
            programCreated = 101
            return programCreated
        }

        override fun useProgram(program: Int) {
            activeProgram = program
        }

        override fun getUniformLocation(program: Int, name: String): Int = name.hashCode()
        override fun getAttribLocation(program: Int, name: String): Int = name.hashCode()

        override fun genTextures(n: Int): IntArray {
            texturesGenerated += n
            return IntArray(n) { it + 1 }
        }

        override fun deleteTextures(textures: IntArray) {
            texturesDeleted = true
        }

        override fun deleteProgram(program: Int) {
            programDeleted = true
        }

        override fun bindTexture(target: Int, texture: Int) {}

        override fun texImage2D(
            target: Int, level: Int, internalformat: Int,
            width: Int, height: Int, border: Int,
            format: Int, type: Int, pixels: Buffer?
        ) {
            texImage2DCalls.add(internalformat)
        }

        override fun uniform1i(location: Int, x: Int) {}
        override fun uniform1f(location: Int, x: Float) {
            uniformFloatCalls[location] = x
        }

        override fun uniformMatrix3fv(
            location: Int, count: Int, transpose: Boolean,
            value: FloatArray, offset: Int
        ) {
            uniformMatrixCalled = true
        }

        override fun viewport(x: Int, y: Int, width: Int, height: Int) {}
        override fun drawArrays(mode: Int, first: Int, count: Int) {
            drawArraysCalled = true
        }

        override fun close() {
            terminateEgl()
        }
    }

    private fun createFrame(): MemoryDecodedFrameLease {
        val yBuf = ByteBuffer.allocateDirect(64 * 48)
        val uBuf = ByteBuffer.allocateDirect(32 * 24)
        val vBuf = ByteBuffer.allocateDirect(32 * 24)
        return MemoryDecodedFrameLease(
            key = FrameKey(
                identity = RuntimeIdentity(
                    stream = SessionVideoId("s1"),
                    configGeneration = 1L,
                    decoderGeneration = 1L,
                    surfaceGeneration = 1L,
                    backendInstanceId = "egl-test-1"
                ),
                accessUnitId = 1L,
                codecPtsUs = 10_000L
            ),
            outputDescription = DecoderOutputDescription(actualComponent = "TEST_CODEC"),
            yPlane = PlaneLayout(64, 1, 64, 48),
            uPlane = PlaneLayout(32, 1, 32, 24),
            vPlane = PlaneLayout(32, 1, 32, 24),
            yBuffer = yBuf,
            uBuffer = uBuf,
            vBuffer = vBuf
        )
    }

    @Test
    fun attachInitializesEglAndRequestsSwapIntervalZero() {
        val fakeDriver = FakeEglDriver()
        val renderer = EglFrameRenderer { fakeDriver }

        // Create a mock surface
        val surface = Surface(android.graphics.SurfaceTexture(1))
        renderer.attach(OutputTarget(surface = surface))

        assertTrue(fakeDriver.initialized)
        assertTrue(fakeDriver.windowSurfaceCreated)
        assertTrue(fakeDriver.madeCurrent)
        assertEquals(0, fakeDriver.swapInterval) // eglSwapInterval(0) requested
        assertEquals(101, fakeDriver.programCreated)
        assertEquals(3, fakeDriver.texturesGenerated)
    }

    @Test
    fun renderUploadsThreePlanesReleasesFrameEarlyAndSwaps() {
        val fakeDriver = FakeEglDriver()
        val renderer = EglFrameRenderer { fakeDriver }

        val surface = Surface(android.graphics.SurfaceTexture(1))
        renderer.attach(OutputTarget(surface = surface))
        fakeDriver.surfaceDestroyed = false // Reset after attach

        val frame = createFrame()
        val transform = VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601)
        val decision = ColorDecision.Accepted(
            ResolvedPixelContract(effectiveRange = ColorRange.LIMITED, effectiveMatrix = ColorMatrix.BT601),
            transform
        )

        assertEquals(0, frame.releaseAttempts)

        val receipt = renderer.render(frame, decision)
        assertTrue(receipt.success)
        assertEquals(3, fakeDriver.texImage2DCalls.size) // Y, U, V uploaded
        assertTrue(fakeDriver.uniformMatrixCalled)
        assertTrue(fakeDriver.drawArraysCalled)
        assertEquals(1, fakeDriver.swapBuffersCallCount)

        // Verify early lease release: released immediately after texture upload, before draw/swap finishes
        assertEquals(1, frame.releaseAttempts)
    }

    @Test
    fun swapFailureReportsFailedReceiptWithoutReleasingExternalSurface() {
        val fakeDriver = FakeEglDriver()
        fakeDriver.swapBuffersResult = false // Swap fails

        val renderer = EglFrameRenderer { fakeDriver }
        val surface = Surface(android.graphics.SurfaceTexture(1))
        renderer.attach(OutputTarget(surface = surface))
        fakeDriver.surfaceDestroyed = false // Reset after attach

        val frame = createFrame()
        val decision = ColorDecision.Accepted(
            ResolvedPixelContract(),
            VideoColorTransform(ColorRange.LIMITED, ColorMatrix.BT601)
        )

        val receipt = renderer.render(frame, decision)
        assertFalse(receipt.success)
        assertEquals("SWAP_FAILED", receipt.errorStage)
        assertNotNull(receipt.error)

        // External surface is NOT destroyed by swap failure
        assertFalse(fakeDriver.surfaceDestroyed)
    }

    @Test
    fun stopCleansOwnedResourcesAndTerminates() {
        val fakeDriver = FakeEglDriver()
        val renderer = EglFrameRenderer { fakeDriver }
        val surface = Surface(android.graphics.SurfaceTexture(1))
        renderer.attach(OutputTarget(surface = surface))

        val ticket = renderer.stop()
        assertEquals(ReleaseState.RELEASED, ticket.state())
        assertTrue(fakeDriver.texturesDeleted)
        assertTrue(fakeDriver.programDeleted)
        assertTrue(fakeDriver.surfaceDestroyed)
        assertTrue(fakeDriver.terminated)
    }
}
