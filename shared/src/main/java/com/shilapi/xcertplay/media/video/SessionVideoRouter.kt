package com.shilapi.xcertplay.media.video

import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/**
 * 多屏幕角色视频路由器 (SessionVideoRouter)。
 * 将主屏 (110) 与各类仪表盘/镜像端点隔离分发，按角色惰性构造镜像端点，
 * 避免在独立主屏启用时过早创建旧解码器。
 */
class SessionVideoRouter(
    val mainEndpoint: SessionVideoEndpoint,
    private val mirrorEndpointFactory: ((Int) -> SessionVideoEndpoint)? = null
) : AutoCloseable {

    private val endpoints = ConcurrentHashMap<Int, SessionVideoEndpoint>()

    init {
        endpoints[mainEndpoint.streamRole] = mainEndpoint
    }

    fun getEndpoint(streamRole: Int): SessionVideoEndpoint {
        return endpoints.computeIfAbsent(streamRole) { role ->
            mirrorEndpointFactory?.invoke(role) ?: LegacyVideoEndpoint(role)
        }
    }

    fun onConfig(streamRole: Int, configBytes: ByteArray) {
        getEndpoint(streamRole).onConfig(configBytes)
    }

    fun onCodec(streamRole: Int, codec: Int) {
        getEndpoint(streamRole).onCodec(codec)
    }

    fun onFrame(streamRole: Int, frameBytes: ByteArray, ptsUs: Long = 0L, isKey: Boolean = false) {
        getEndpoint(streamRole).onFrame(frameBytes, ptsUs, isKey)
    }

    fun attachSurface(streamRole: Int, surface: Surface) {
        getEndpoint(streamRole).attachSurface(surface)
    }

    fun detachSurface(streamRole: Int): ReleaseTicket {
        return getEndpoint(streamRole).detachSurface()
    }

    override fun close() {
        endpoints.values.forEach {
            try { it.close() } catch (_: Throwable) {}
        }
        endpoints.clear()
    }
}
