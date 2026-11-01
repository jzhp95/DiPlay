package com.shilapi.xcertplay.media.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.nio.Buffer

/**
 * EGL 与 GLES2 平台驱动抽象接口，剥离硬件底层便于可注入单元测试。
 */
interface EglDriver : AutoCloseable {
    fun initEgl(): Boolean
    fun createWindowSurface(surface: Surface): Boolean
    fun makeCurrent(): Boolean
    fun setSwapInterval(interval: Int): Boolean
    fun swapBuffers(): Boolean
    fun destroySurface(): Boolean
    fun terminateEgl(): Boolean

    fun createProgram(vertexShader: String, fragmentShader: String): Int
    fun useProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int
    fun getAttribLocation(program: Int, name: String): Int
    fun genTextures(n: Int): IntArray
    fun deleteTextures(textures: IntArray)
    fun deleteProgram(program: Int)
    fun bindTexture(target: Int, texture: Int)
    fun texImage2D(target: Int, level: Int, internalformat: Int, width: Int, height: Int, border: Int, format: Int, type: Int, pixels: Buffer?)
    fun uniform1i(location: Int, x: Int)
    fun uniform1f(location: Int, x: Float)
    fun uniformMatrix3fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int)
    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
}

/**
 * 基于 Android 9 / API 28 EGL14 与 GLES20 的真实实现。
 */
class AndroidEglDriver : EglDriver {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    override fun initEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            return false
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        return eglContext != EGL14.EGL_NO_CONTEXT
    }

    override fun createWindowSurface(surface: Surface): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglConfig == null) return false
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        return eglSurface != EGL14.EGL_NO_SURFACE
    }

    override fun makeCurrent(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE || eglContext == EGL14.EGL_NO_CONTEXT) {
            return false
        }
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    override fun setSwapInterval(interval: Int): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        return EGL14.eglSwapInterval(eglDisplay, interval)
    }

    override fun swapBuffers(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    override fun destroySurface(): Boolean {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            val res = EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
            return res
        }
        return true
    }

    override fun terminateEgl(): Boolean {
        destroySurface()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            val res = EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
            return res
        }
        return true
    }

    override fun createProgram(vertexShader: String, fragmentShader: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        if (vs == 0 || fs == 0) return 0

        val program = GLES20.glCreateProgram()
        if (program != 0) {
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(program)
                return 0
            }
        }
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                GLES20.glDeleteShader(shader)
                return 0
            }
        }
        return shader
    }

    override fun useProgram(program: Int) = GLES20.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String): Int = GLES20.glGetUniformLocation(program, name)
    override fun getAttribLocation(program: Int, name: String): Int = GLES20.glGetAttribLocation(program, name)

    override fun genTextures(n: Int): IntArray {
        val textures = IntArray(n)
        GLES20.glGenTextures(n, textures, 0)
        return textures
    }

    override fun deleteTextures(textures: IntArray) = GLES20.glDeleteTextures(textures.size, textures, 0)
    override fun deleteProgram(program: Int) = GLES20.glDeleteProgram(program)
    override fun bindTexture(target: Int, texture: Int) = GLES20.glBindTexture(target, texture)

    override fun texImage2D(
        target: Int, level: Int, internalformat: Int,
        width: Int, height: Int, border: Int,
        format: Int, type: Int, pixels: Buffer?
    ) = GLES20.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels)

    override fun uniform1i(location: Int, x: Int) = GLES20.glUniform1i(location, x)
    override fun uniform1f(location: Int, x: Float) = GLES20.glUniform1f(location, x)
    override fun uniformMatrix3fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) =
        GLES20.glUniformMatrix3fv(location, count, transpose, value, offset)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES20.glViewport(x, y, width, height)
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES20.glDrawArrays(mode, first, count)

    override fun close() {
        terminateEgl()
    }
}
