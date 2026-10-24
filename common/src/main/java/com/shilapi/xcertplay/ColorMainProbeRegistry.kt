package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.VideoColorOverride
import java.util.UUID

/** Tokens are process-local capabilities. An exported host Intent alone cannot enable a diagnostic. */
internal object ColorMainProbeRegistry {
    const val EXTRA_TOKEN = "color_probe_token"
    const val PREFS = "color_test_results_v2"
    data class Profile(val id: String, val title: String, val smooth: Boolean?, val direct: Boolean?,
        val software: Boolean = false, val range: Int? = null, val neutral: Boolean = true) {
        fun resolve(savedSmooth: Boolean, savedDirect: Boolean) = (smooth ?: savedSmooth) to (direct ?: savedDirect)
    }
    val profiles = listOf(
        Profile("current", "正式主屏 · 当前实际配置", null, null, neutral = false),
        Profile("texture", "正式主屏 · 纹理输出", false, false),
        Profile("direct", "正式主屏 · 直接输出", false, true),
        Profile("smooth", "正式主屏 · 平滑视频", true, false),
        Profile("texture-full", "正式主屏 · 纹理 · 请求全范围", false, false, range = 1),
        Profile("direct-full", "正式主屏 · 直接 · 请求全范围", false, true, range = 1),
        Profile("texture-limited", "正式主屏 · 纹理 · 请求有限范围", false, false, range = 2),
        Profile("direct-limited", "正式主屏 · 直接 · 请求有限范围", false, true, range = 2),
        Profile("software-texture", "正式主屏 · 软件解码 · 纹理", false, false, software = true),
        Profile("software-direct", "正式主屏 · 软件解码 · 直接", false, true, software = true))
    data class Request(val token: String, val index: Int, val profile: Profile,
        val color: VideoColorOverride, val startedMillis: Long)
    private var current: Request? = null
    private val closing = mutableSetOf<String>()
    @Synchronized fun begin(index: Int, color: VideoColorOverride): Request {
        val profile = profiles.single { it.id == ColorTestRun.cases[index].mainProfile }
        return Request(UUID.randomUUID().toString(), index, profile, color, System.currentTimeMillis()).also { current = it }
    }
    @Synchronized fun lookup(token: String?): Request? = current?.takeIf {
        token != null && token == it.token && System.currentTimeMillis() - it.startedMillis in 0..900_000
    }
    @Synchronized fun finish(token: String): Request? = current?.takeIf { it.token == token }?.also { current = null; closing.add(token) }
    @Synchronized fun isClosing(token: String): Boolean = token in closing
    @Synchronized fun teardownFinished(token: String) { closing.remove(token) }
}
