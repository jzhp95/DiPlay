package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object DecoderDiagnosticsRun {
    const val PLAN_VERSION = 3
    const val BUDGET = 256L * 1024 * 1024
    val busyStates = setOf("preflight", "inventory", "live_capture", "standard", "replay", "restoring", "sealing", "publishing")
    @Volatile private var state: com.shilapi.xcertplay.diagnostics.DiagnosticRunCoordinator? = null
    fun coordinator(c: Context): com.shilapi.xcertplay.diagnostics.DiagnosticRunCoordinator = state ?: synchronized(this) {
        state ?: run {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val store = com.shilapi.xcertplay.diagnostics.DiagnosticRunStore(
                File(c.applicationContext.filesDir, "decoder-diagnostics-v3"), { callback -> handler.post { callback() } })
            com.shilapi.xcertplay.diagnostics.DiagnosticRunCoordinator(store) { delay, callback ->
                val timer = Runnable { callback() }
                handler.postDelayed(timer, delay)
                val cancel: () -> Unit = { handler.removeCallbacks(timer) }
                cancel
            }.also { state = it }
        }
    }

    fun root(c: Context) = File(c.filesDir, "decoder-diagnostics-v3").apply { mkdirs() }
    fun directory(c: Context, id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(root(c), id).apply { mkdirs() }
    }
    fun read(file: File) = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    fun latest(c: Context): JSONObject {
        val id = read(File(root(c), "latest.json")).optString("id")
        return if (id.matches(Regex("[a-f0-9-]{36}"))) read(File(directory(c, id), "run.json")) else JSONObject()
    }
    fun atomic(file: File, bytes: ByteArray) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val temporary = File(file.parentFile, file.name + ".writing")
        temporary.outputStream().use { out -> out.write(bytes); out.fd.sync() }
        check(temporary.renameTo(file)) { "Atomic write failed: ${file.name}" }
    }
    fun json(file: File, value: JSONObject) = atomic(file, value.toString(2).toByteArray())
    fun save(c: Context, run: JSONObject) = json(File(directory(c, run.getString("id")), "run.json"), run)
    /** Legacy writer retained for old records/tools; production supervision uses the coordinator. */
    fun create(c: Context, screen: JSONObject): JSONObject {
        val value = fresh(screen)
        save(c, value); json(File(root(c), "latest.json"), JSONObject().put("id", value.getString("id")))
        return value
    }
    fun fresh(screen: JSONObject): JSONObject {
        val id = UUID.randomUUID().toString()
        val value = JSONObject().put("id", id).put("planVersion", PLAN_VERSION).put("status", "preflight")
            .put("startedAt", System.currentTimeMillis()).put("startedElapsedMs", android.os.SystemClock.elapsedRealtime())
            .put("screen", screen).put("position", 0).put("results", JSONArray()).put("gaps", JSONArray())
            .put("execution", "running").put("measurementValidity", "pending").put("evidenceCompleteness", "pending")
            .put("publication", "not_started").put("themeRequested", "unchanged")
            .put("themeActual", "unknown").put("themeBasis", "no_verified_iPhone_theme_feedback")
            .put("settingsPolicy", "diagnostic_instances_only_no_daily_settings_written")
            .put("plan", plan()).put("total", plan().length())
        return value
    }
    fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun result(case: JSONObject, execution: String, reason: String = "") = JSONObject()
        .put("caseId", case.getString("caseId")).put("sourceId", case.optString("sourceId"))
        .put("requested", JSONObject(case.toString())).put("execution", execution)
        .put("measurementValidity", "invalid").put("evidenceCompleteness", "partial")
        .put("reason", reason).put("colorVerdict", "not_determined").put("time", System.currentTimeMillis())
    fun plan(replay: Boolean = false): JSONArray = JSONArray().apply {
        val assets = if (replay) listOf("real-source") else listOf(
            "h264_light_full", "h264_light_limited", "h264_dark_full", "h264_dark_limited",
            "h264_clarity1080", "h264_motion1080", "h264_full", "h264_limited", "hevc_full", "hevc_limited")
        for (asset in assets) for (path in listOf("P1", "P2", "P3", "P4", "P5", "P6")) {
            put(JSONObject().put("caseId", "$asset-$path").put("sourceId", asset).put("asset", asset)
                .put("path", path).put("software", path in listOf("P3", "P4", "P5"))
                .put("surface", path != "P1" && path != "P3")
                .put("softwareYuv", path == "P5").put("hardwareYuv", path == "P6")
                .put("replay", replay).put("fromSps", false).put("colorPolicy", "decoder_default").put("comparisonGroup", "$asset-decoder_default")
                .put("contentMode", when { asset.contains("light") -> "light_reference"; asset.contains("dark") -> "dark_reference"; else -> "not_a_theme_comparison" })
                .put("warmupMs", 2000).put("measureMs", 4000))
        }
        if (!replay) for (asset in listOf("h264_light_full", "h264_light_limited", "h264_dark_full", "h264_dark_limited"))
            for (path in listOf("P2", "P4")) {
                put(JSONObject().put("caseId", "$asset-$path-sps").put("sourceId", asset).put("asset", asset).put("path", path)
                    .put("software", path == "P4").put("surface", true).put("softwareYuv", false).put("hardwareYuv", false)
                    .put("replay", false).put("fromSps", true).put("colorPolicy", "sps_hints").put("comparisonGroup", "$asset-sps_hints")
                    .put("contentMode", if(asset.contains("light"))"light_reference"else"dark_reference").put("warmupMs",2000).put("measureMs",4000))
            }
    }
    fun caseDeadlineMs(spec:JSONObject):Long = 5_000L + 5_000L + spec.getLong("warmupMs") + spec.getLong("measureMs") +
        5_000L + 5_000L + 5_000L + 5_000L + 5_000L + 2_000L
    fun description(c: Context): String {
        val owner = coordinator(c)
        owner.recover()
        if (owner.recovering) return "正在恢复诊断记录，请稍候"
        return describe(owner.snapshot(), DecoderDiagnosticsService.alive)
    }
    fun describe(r: JSONObject, serviceAlive: Boolean): String {
        if (r.optString("status") == "storage_unavailable") return "报告未保存：诊断存储不可用，已停止新增测试。\n${r.optString("publicationError")}"
        if (!r.has("id")) return "尚未运行系统诊断"
        val status = r.optString("status")
        if(status in busyStates && !serviceAlive) return "上次诊断进程已中断；点击重新发布保留的证据，无需重新测试。"
        val stage = mapOf("preflight" to "交付预检查", "inventory" to "软硬件与解码能力", "live_capture" to "真实 CarPlay 输入捕获",
            "standard" to "标准片颜色／清晰度／运动", "replay" to "真实输入同源回放", "restoring" to "恢复核验",
            "sealing" to "封存与校验", "publishing" to "写入 Download", "completed" to "执行结束", "interrupted" to "部分完成", "failed" to "执行失败")[status] ?: status
        return buildString {
            append("$stage · ${r.optInt("position")}/${r.optInt("total")}")
            if (r.optString("currentCase").isNotEmpty()) append("\n${r.optString("currentCase")}")
            val results=r.optJSONArray("results")?:JSONArray()
            val rows=(0 until results.length()).map{results.getJSONObject(it)}
            append("\n完成 ${rows.count{it.optString("execution")=="completed"}}；失败 ${rows.count{it.optString("execution")=="failed"}}；不支持 ${rows.count{it.optString("execution")=="unsupported"}}")
            fun label(value:String)=when(value){"valid"->"有效";"partial"->"部分";"pending"->"待定";"complete"->"完整";else->value}
            append("\n测量：${label(r.optString("measurementValidity"))}；证据：${label(r.optString("evidenceCompleteness"))}")
            val gaps=r.optJSONArray("gaps")?:JSONArray()
            if(gaps.length()>0){
                append("\n证据缺口 ${gaps.length()} 项，详见结果包。")
                if((0 until gaps.length()).any{gaps.optString(it).startsWith("real_input_missing")})append("\n本轮未取得真实 CarPlay 输入，同源回放未执行。")
            }
            if (r.optString("publication") == "public_verified") append("\n已保存并校验：${r.optString("publicPath")}\n${r.optLong("publicBytes")} 字节")
            else if(r.optString("publication")=="private_only")append("\n仅应用内副本：${r.optString("publicPath")}\n公共 Download 尚未保存，请点击重新发布。")
            else append("\n交付：${when(r.optString("publication")){"writing"->"正在写入";"failed"->"失败，原始证据保留，可重新发布";else->"尚未发布"}}")
            if (r.optString("reason").isNotBlank()) append("\n${r.optString("reason")}")
            if (r.optString("publicationError").isNotBlank()) append("\n${r.optString("publicationError")}")
        }
    }
}
