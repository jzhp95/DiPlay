package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.VideoColorOverride

/** Results describe observations, never an automatic claim about a physical display. */
internal class ColorTestRun {
    data class Case(val title: String, val asset: String?, val surface: Boolean,
        val color: VideoColorOverride = VideoColorOverride())
    var index = 0
        private set
    var rendered = false
    val results = linkedMapOf<Int, String>()
    data class Observation(val index: Int, val verdict: String, val range: Int?, val standard: Int?,
        val savedPicture: Boolean, val capture: String?, val time: Long)
    val history = mutableListOf<Observation>()
    val current get() = cases[index]
    fun select(value: Int) {
        index = value.coerceIn(cases.indices)
        rendered = false
    }
    fun record(verdict: String, range: Int? = null, standard: Int? = null, savedPicture: Boolean = false, capture: String? = null): Boolean {
        if (!rendered && verdict !in listOf("无法显示", "跳过")) return false
        results[index] = verdict
        history.add(Observation(index, verdict, range, standard, savedPicture, capture, System.currentTimeMillis()))
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
            for (range in listOf<Int?>(null, 1, 2)) for (surface in listOf(false, true)) {
                add(Case("CarPlay · ${when(range) { 1 -> "请求全范围"; 2 -> "请求有限范围"; else -> "自动范围" }} · ${if(surface) "直接输出" else "纹理输出"}",
                    null, surface, VideoColorOverride(range)))
            }
        }
    }
}
