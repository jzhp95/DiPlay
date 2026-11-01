package com.shilapi.xcertplay.media.video

import android.view.Surface

/**
 * 单个视频流角色端点抽象接口 (SessionVideoEndpoint)。
 * 支持主屏 (type=110) 及扩展镜像/仪表盘等角色，区分 LEGACY 与 INDEPENDENT 运行实现。
 */
interface SessionVideoEndpoint : AutoCloseable {
    val streamRole: Int
    val implementation: VideoImplementation

    fun onConfig(configBytes: ByteArray)
    fun onCodec(codec: Int)
    fun onFrame(frameBytes: ByteArray, ptsUs: Long = 0L, isKey: Boolean = false)
    fun attachSurface(surface: Surface)
    fun detachSurface(): ReleaseTicket
    fun snapshot(): RuntimeSnapshot
    override fun close()
}
