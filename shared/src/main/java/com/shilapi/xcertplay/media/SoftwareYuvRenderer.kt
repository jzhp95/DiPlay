package com.shilapi.xcertplay.media

import android.media.Image
import android.opengl.*
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoder-worker-owned EGL output. The framework owns Surface; this class never releases it. */
internal class SoftwareYuvRenderer(surface: Surface) : Closeable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val textures = IntArray(3)
    private val sizes = IntArray(6)
    private val frame = Yuv420Frame()
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }

    // Cached locations to prevent per-frame OpenGL shader state lookups
    private var locPosition = -1
    private var locCoordinate = -1
    private val locPlanes = IntArray(3) { -1 }
    private var locConversion = -1
    private var locOffset = -1
    private var locChromaOffset = -1
    private var locChromaScale = -1

    // Reusable buffers for EGL queries
    private val queryW = IntArray(1)
    private val queryH = IntArray(1)
    private var cachedSurfaceW = 0
    private var cachedSurfaceH = 0

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialize failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) { "8-bit EGL window config unavailable" }
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL context unavailable" }
            window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context)) { "EGL window unavailable" }
            program = link(VERTEX, FRAGMENT)

            // Cache attributes & uniforms
            locPosition = GLES20.glGetAttribLocation(program, "position")
            locCoordinate = GLES20.glGetAttribLocation(program, "coordinate")
            for (i in 0..2) {
                locPlanes[i] = GLES20.glGetUniformLocation(program, "plane$i")
            }
            locConversion = GLES20.glGetUniformLocation(program, "conversion")
            locOffset = GLES20.glGetUniformLocation(program, "offset")
            locChromaOffset = GLES20.glGetUniformLocation(program, "chromaOffset")
            locChromaScale = GLES20.glGetUniformLocation(program, "chromaScale")

            GLES20.glGenTextures(3, textures, 0)
            for (texture in textures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            checkGl()
        } catch (error: Exception) { close(); throw error }
    }

    @Volatile var latestRawSamples: List<Yuv420Frame.RawSample> = emptyList()
        private set
    @Volatile var diagnosticSamplePoints: List<Pair<Int, Int>>? = null
    @Volatile var diagnosticPatchIndices: List<Int?> = emptyList()
    @Volatile var diagnosticNominals: List<Int?> = emptyList()
    @Volatile var diagnosticFrameId: String? = null
    @Volatile var diagnosticInstanceId: String? = null

    fun configureDiagnosticSampling(
        points: List<Pair<Int, Int>>,
        patchIndices: List<Int?> = emptyList(),
        nominals: List<Int?> = emptyList(),
        frameId: String? = null,
        instanceId: String? = null,
    ) {
        diagnosticSamplePoints = points
        diagnosticPatchIndices = patchIndices
        diagnosticNominals = nominals
        diagnosticFrameId = frameId
        diagnosticInstanceId = instanceId
    }

    fun sampleCurrent(points: List<Pair<Int, Int>>): List<Yuv420Frame.RawSample> = frame.sample(points)

    fun draw(image: Image, color: YuvColorTransform): YuvDrawTiming {
        val start = System.nanoTime()
        frame.copy(image)
        val copied = System.nanoTime()
        if (frame.width > 0 && frame.height > 0) {
            val customPoints = diagnosticSamplePoints
            if (customPoints != null && customPoints.isNotEmpty()) {
                latestRawSamples = frame.sample(
                    points = customPoints,
                    patchIndices = diagnosticPatchIndices,
                    nominals = diagnosticNominals,
                    frameId = diagnosticFrameId,
                    instanceId = diagnosticInstanceId,
                )
            } else {
                latestRawSamples = frame.sample(listOf(
                    Pair(frame.width / 2, frame.height / 2),
                    Pair(frame.width / 4, frame.height / 4),
                    Pair(3 * frame.width / 4, 3 * frame.height / 4)
                ))
            }
        }
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "EGL window lost" }

        check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, queryW, 0) &&
            EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, queryH, 0) && queryW[0] > 0 && queryH[0] > 0)
        val sw = queryW[0]
        val sh = queryH[0]
        if (sw != cachedSurfaceW || sh != cachedSurfaceH) {
            GLES20.glViewport(0, 0, sw, sh)
            cachedSurfaceW = sw
            cachedSurfaceH = sh
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glUseProgram(program)

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(locPosition)
        GLES20.glVertexAttribPointer(locPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(locCoordinate)
        GLES20.glVertexAttribPointer(locCoordinate, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        val beforeUpload = System.nanoTime()
        for (index in 0..2) {
            val width = if (index == 0) frame.width else frame.chromaWidth
            val height = if (index == 0) frame.height else frame.chromaHeight
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
            if (sizes[index * 2] != width || sizes[index * 2 + 1] != height) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, frame.plane(index))
                sizes[index * 2] = width; sizes[index * 2 + 1] = height
            } else GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, frame.plane(index))
            GLES20.glUniform1i(locPlanes[index], index)
        }
        val uploaded = System.nanoTime()

        GLES20.glUniformMatrix3fv(locConversion, 1, false, color.coefficients, 0)
        GLES20.glUniform3fv(locOffset, 1, color.offset, 0)
        GLES20.glUniform2fv(locChromaOffset, 1, frame.chromaOffset, 0)
        GLES20.glUniform2fv(locChromaScale, 1, frame.chromaScale, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl()
        val drawn = System.nanoTime()

        check(EGL14.eglSwapBuffers(display, window)) { "EGL swap failed" }
        val swapped = System.nanoTime()

        val copyNanos = copied - start
        val uploadNanos = uploaded - beforeUpload
        val drawOnlyNanos = drawn - uploaded
        val swapNanos = swapped - drawn
        val drawNanos = swapped - copied
        val totalNanos = swapped - start

        return YuvDrawTiming(copyNanos, drawNanos, uploadNanos, drawOnlyNanos, swapNanos, totalNanos)
    }

    override fun close() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
            if (EGL14.eglMakeCurrent(display, window, window, context)) {
                if (program != 0) GLES20.glDeleteProgram(program)
                GLES20.glDeleteTextures(3, textures, 0)
            }
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display); EGL14.eglReleaseThread()
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; window = EGL14.EGL_NO_SURFACE; program = 0
    }

    private fun checkGl() { check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "YUV GLES operation failed" } }
    private fun link(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val result = GLES20.glCreateShader(type)
            GLES20.glShaderSource(result, source); GLES20.glCompileShader(result)
            val status = IntArray(1); GLES20.glGetShaderiv(result, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) { val log = GLES20.glGetShaderInfoLog(result); GLES20.glDeleteShader(result); error("YUV shader: $log") }
            return result
        }
        val vertexShader = shader(GLES20.GL_VERTEX_SHADER, vertex)
        var fragmentShader = 0
        var linked = 0
        try {
            fragmentShader = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
            linked = GLES20.glCreateProgram()
            check(linked != 0) { "YUV program create failed" }
            GLES20.glAttachShader(linked, vertexShader)
            GLES20.glAttachShader(linked, fragmentShader)
            GLES20.glLinkProgram(linked)
            val status = IntArray(1); GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "YUV program link: ${GLES20.glGetProgramInfoLog(linked)}" }
            return linked
        } finally {
            if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
            if (linked == 0) close()
        }
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 position;
            attribute vec2 coordinate;
            varying vec2 textureCoordinate;
            void main() {
                gl_Position = position;
                textureCoordinate = coordinate;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            varying vec2 textureCoordinate;
            uniform sampler2D plane0;
            uniform sampler2D plane1;
            uniform sampler2D plane2;
            uniform mat3 conversion;
            uniform vec3 offset;
            uniform vec2 chromaOffset;
            uniform vec2 chromaScale;
            void main() {
                vec2 chroma = chromaOffset + textureCoordinate * chromaScale;
                vec3 yuv = vec3(
                    texture2D(plane0, textureCoordinate).r,
                    texture2D(plane1, chroma).r,
                    texture2D(plane2, chroma).r
                ) - offset;
                gl_FragColor = vec4(clamp(conversion * yuv, 0.0, 1.0), 1.0);
            }
        """
    }
}
