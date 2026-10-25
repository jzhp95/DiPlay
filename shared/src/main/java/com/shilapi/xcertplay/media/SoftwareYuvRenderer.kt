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

    fun draw(image: Image, color: YuvColorTransform): YuvDrawTiming {
        val start = System.nanoTime()
        frame.copy(image)
        val copied = System.nanoTime()
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "EGL window lost" }
        val w = IntArray(1); val h = IntArray(1)
        check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, w, 0) &&
            EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, h, 0) && w[0] > 0 && h[0] > 0)
        GLES20.glViewport(0, 0, w[0], h[0])
        GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "position")
        val coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        vertices.position(0); GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2); GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
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
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "plane$index"), index)
        }
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "conversion"), 1, false, color.coefficients, 0)
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(program, "offset"), 1, color.offset, 0)
        GLES20.glUniform2fv(GLES20.glGetUniformLocation(program, "chromaOffset"), 1, frame.chromaOffset, 0)
        GLES20.glUniform2fv(GLES20.glGetUniformLocation(program, "chromaScale"), 1, frame.chromaScale, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl()
        check(EGL14.eglSwapBuffers(display, window)) { "EGL swap failed" }
        return YuvDrawTiming(copied - start, System.nanoTime() - copied)
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
            GLES20.glAttachShader(linked, vertexShader); GLES20.glAttachShader(linked, fragmentShader); GLES20.glLinkProgram(linked)
            val status = IntArray(1); GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "YUV program: ${GLES20.glGetProgramInfoLog(linked)}" }
            return linked
        } catch (error: Exception) { if (linked != 0) GLES20.glDeleteProgram(linked); throw error }
        finally { GLES20.glDeleteShader(vertexShader); if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader) }
    }

    companion object {
        private const val VERTEX = """
            attribute vec2 position;
            attribute vec2 coordinate;
            varying vec2 uv;
            void main() { gl_Position = vec4(position, 0.0, 1.0); uv = coordinate; }
        """
        private const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 uv;
            uniform mediump sampler2D plane0;
            uniform mediump sampler2D plane1;
            uniform mediump sampler2D plane2;
            uniform mat3 conversion;
            uniform vec3 offset;
            uniform vec2 chromaOffset;
            uniform vec2 chromaScale;
            void main() {
                vec2 chroma = chromaOffset + uv * chromaScale;
                vec3 yuv = vec3(texture2D(plane0, uv).r, texture2D(plane1, chroma).r, texture2D(plane2, chroma).r);
                gl_FragColor = vec4(clamp(conversion * (yuv - offset), 0.0, 1.0), 1.0);
            }
        """
    }
}
