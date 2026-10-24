package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.VideoColorOverride

/** Results describe observations, never an automatic claim about a physical display. */
internal class ColorTestRun {
    data class Case(val title: String, val asset: String?, val surface: Boolean,
        val color: VideoColorOverride = VideoColorOverride(), val forceSoftware: Boolean = false, val mainProfile: String? = null)
    var index = 0
        private set
    var rendered = false
    val results = linkedMapOf<Int, String>()
    data class Observation(val index: Int, val verdict: String, val range: Int?, val standard: Int?,
        val savedPicture: Boolean, val capture: String?, val time: Long, val evidence: String = "{}")
    val history = mutableListOf<Observation>()
    val current get() = cases[index]
    fun select(value: Int) {
        index = value.coerceIn(cases.indices)
        rendered = false
    }
    fun record(verdict: String, range: Int? = null, standard: Int? = null, savedPicture: Boolean = false, capture: String? = null, evidence: String = "{}"): Boolean {
        if (!rendered && verdict !in listOf("无法显示", "跳过")) return false
        results[index] = verdict
        history.add(Observation(index, verdict, range, standard, savedPicture, capture, System.currentTimeMillis(), evidence))
        while (history.size > 100) history.removeAt(0)
        return true
    }
    fun report() = cases.mapIndexed { i, case ->
        val latest = history.lastOrNull { it.index == i }
        "${i + 1}. ${case.title}: ${results[i] ?: "未测试"}" +
            (latest?.let { " (实际请求 range=${it.range ?: "auto"} standard=${it.standard ?: "auto"} savedPicture=${it.savedPicture})" } ?: "")
    }.joinToString("\n")
    companion object {
        val cases = buildList {
            for (codec in listOf("h264", "hevc")) for (range in listOf("full", "limited"))
                for (surface in listOf(false, true)) {
                    val path = if (surface) "直接输出" else "纹理输出"
                    add(Case("${codec.uppercase()} · ${if (range == "full") "全范围" else "有限范围"} · $path",
                        "${codec}_$range", surface))
                }
            val hardwareCases = toList()
            hardwareCases.forEach { add(it.copy(title = "软件解码 · ${it.title}", forceSoftware = true)) }
            ColorMainProbeRegistry.profiles.forEach { profile ->
                add(Case(profile.title, null, profile.direct == true || profile.smooth == true,
                    VideoColorOverride(profile.range), profile.software, profile.id))
            }
        }
    }
}
