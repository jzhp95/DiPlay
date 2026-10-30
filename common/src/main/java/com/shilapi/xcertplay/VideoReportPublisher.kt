package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Publication belongs to the process, never to an Activity or its timers. */
internal object VideoReportPublisher {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "decoder-report").apply { isDaemon = true } }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pending = mutableSetOf<String>()
    private fun prefs(c: Context) = c.getSharedPreferences("video_report_publication_v1", 0)
    @Synchronized fun busy() = pending.isNotEmpty()
    @Synchronized fun latest(c: Context): JSONObject {
        val value = runCatching { JSONObject(prefs(c).getString("latest", "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (value.optString("status") == "writing" && value.optString("key") !in pending) {
            value.put("status", "failed").put("error", "进程中断，请重新保存；无需重新采样")
            prefs(c).edit().putString("latest", value.toString()).apply()
        }
        return value
    }
    @Synchronized private fun store(c: Context, value: JSONObject, onlyCurrent: Boolean = false) {
        if (!onlyCurrent || latest(c).optString("key") == value.optString("key"))
            prefs(c).edit().putString("latest", value.toString()).apply()
    }
    fun snapshot(c: Context, automatic: Boolean = false): JSONObject {
        val value = VideoAdaptationStore.read(c).put("schema", 2).put("exportedAt", System.currentTimeMillis())
        val suite = AutomaticVideoSuite.read(c)
        value.put("sessionConfiguration", JSONObject()
            .put("hardwareYuv", AirPlayPersistence.loadHardwareYuvEnabled(c))
            .put("softwareYuv", AirPlayPersistence.loadSoftwareYuvEnabled(c))
            .put("softwareDirect", AirPlayPersistence.loadSoftwareDirectVideoOutput(c))
            .put("smooth", AirPlayPersistence.loadSmoothVideo(c)).put("direct", AirPlayPersistence.loadDirectVideoOutput(c))
            .put("hevc", AirPlayPersistence.loadHevcEnabled(c)).put("mtkTuning", AirPlayPersistence.loadMtkDecoderTuning(c))
            .put("lowLatency", AirPlayPersistence.loadLowLatencyDecoder(c)))
        value.put("automaticSuite", suite).put("reportOrigin", if (automatic) "automatic_decoder_suite" else "manual_or_unified_export")
        if (automatic) value.put("manualAdaptationSamplesReference", value.remove("samples") ?: JSONArray())
        val history = runCatching { JSONArray(c.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).getString("history", "[]")) }.getOrDefault(JSONArray())
        val colors = JSONArray()
        for (i in 0 until history.length()) {
            val row = history.getJSONObject(i)
            if (!automatic || row.optJSONObject("evidence")?.optString("auto_run_id") == suite.optString("id")) colors.put(row)
        }
        val result = runCatching { JSONObject(c.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).getString("pending_result", "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (result.optJSONObject("evidence")?.optString("auto_run_id") == suite.optString("id") && result.has("evidence")) {
            value.put("pendingMainResult", result)
            colors.put(JSONObject().put("index", result.optInt("index")).put("evidence", result.getJSONObject("evidence")))
        }
        value.put("separateColorTestHistory", colors)
        val executed = mutableSetOf<Int>()
        val results = suite.optJSONArray("results") ?: JSONArray()
        for (i in 0 until results.length()) executed.add(results.getJSONObject(i).optInt("index"))
        val sequence = suite.optJSONArray("sequence") ?: JSONArray()
        value.put("unexecutedCases", JSONArray().apply {
            for (i in 0 until sequence.length()) {
                val index = sequence.getInt(i)
                if (index !in executed && index in ColorTestRun.cases.indices)
                    put(JSONObject().put("index", index).put("title", ColorTestRun.cases[index].title)
                        .put("reason", suite.optString("reason", "not_reached")))
            }
        })
        return JSONObject(value.toString())
    }
    fun liveSnapshot(c: Context): JSONObject {
        val value = snapshot(c)
        for (key in listOf("automaticSuite", "separateColorTestHistory", "pendingMainResult",
            "unexecutedCases", "manualAdaptationSamplesReference")) value.remove(key)
        val source = value.optJSONArray("samples") ?: JSONArray()
        val rows = JSONArray()
        for (i in 0 until source.length()) {
            val row = source.optJSONObject(i) ?: continue
            if (row.optJSONObject("begin")?.optString("source_kind") == "video_adaptation_current_main") rows.put(row)
        }
        return value.put("samples", rows).put("excludedUnverifiedSourceRows", source.length() - rows.length())
            .put("sourceScope", "live_carplay_only").put("reportOrigin", "live_carplay_only")
    }
    fun live(c: Context, done: (JSONObject) -> Unit = {}): Boolean = publish(c, liveSnapshot(c), null, done)
    fun manual(c: Context, done: (JSONObject) -> Unit = {}): Boolean = publish(c, snapshot(c), null, done)
    fun automatic(c: Context, id: String, done: (JSONObject) -> Unit = {}): Boolean {
        val value = snapshot(c, true)
        if (value.optJSONObject("automaticSuite")?.optString("id") != id || id.isEmpty() ||
            value.optJSONObject("automaticSuite")?.has("exportPath") == true) return false
        return publish(c, value, id, done)
    }
    private fun publish(c: Context, frozen: JSONObject, autoId: String?, done: (JSONObject) -> Unit): Boolean {
        val context = c.applicationContext
        val key = autoId ?: UUID.randomUUID().toString()
        synchronized(this) {
            if (!pending.add(key)) return false
            store(context, JSONObject().put("key", key).put("status", "writing").put("startedAt", System.currentTimeMillis()))
        }
        worker.execute {
            var source: File? = null
            val value = JSONObject().put("key", key).put("endedAt", System.currentTimeMillis())
            try {
                check(VideoAdaptationStore.flush(context)) { "sample_checkpoint_failed" }
                frozen.put("samplePersistence", "completed_rows_commit_confirmed")
                source = VideoAdaptationExport.zip(context, frozen)
                val saved = DiagnosticExportStore.saveFileWithoutPicker(context, source, "application/zip")
                value.put("status", if (saved.savedInApp) "private" else "public").put("path", saved.savedPath ?: source.absolutePath)
                    .put("uri", saved.uri.toString()).put("bytes", source.length())
                    .put("error", saved.publicFailure ?: JSONObject.NULL)
                    .put("downloadId", saved.downloadId ?: JSONObject.NULL).put("downloadIndexError", saved.downloadIndexFailure ?: JSONObject.NULL)
                val includedSuite = frozen.optJSONObject("automaticSuite")
                val representedId = autoId ?: includedSuite?.optString("id")?.takeIf {
                    it.isNotEmpty() && includedSuite.optString("status") in listOf("completed", "interrupted", "interrupted_legacy") }
                representedId?.let { AutomaticVideoSuite.exported(context, it, value.getString("path"), !saved.savedInApp) }
            } catch (error: Exception) {
                value.put("status", "failed").put("error", error.javaClass.simpleName + ": " + (error.message ?: "").take(200))
                    .put("path", source?.absolutePath ?: JSONObject.NULL)
                autoId?.let { AutomaticVideoSuite.exportFailed(context, it, value.optString("error")) }
            } finally {
                synchronized(this) { store(context, value, onlyCurrent = true); pending.remove(key) }
                main.post { done(JSONObject(value.toString())) }
            }
        }
        return true
    }
    /** Terminal main-screen results must be published even if the test page cannot reopen. */
    fun finishPendingMain(c: Context, token: String): Boolean {
        val prefs = c.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0)
        val data = runCatching { JSONObject(prefs.getString("pending_result", "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (data.optString("token") != token || !data.optBoolean("teardown_complete")) return false
        val evidence = data.optJSONObject("evidence") ?: return false
        val id = evidence.optString("auto_run_id")
        val suite=AutomaticVideoSuite.read(c)
        if(id.isEmpty() || suite.optString("id")!=id)return false
        return automatic(c, id)
    }
    fun description(c: Context): String {
        val value = latest(c)
        return when (value.optString("status")) {
            "writing" -> "正在写入 Download，请稍候…"
            "public" -> "已写入 Download：\n${value.optString("path")}\n${value.optLong("bytes")} 字节" +
                (if (!value.isNull("downloadIndexError")) "\n文件已写入，但系统下载列表登记失败；请按路径查找。" else "")
            "private" -> "未写入 Download，仅保留应用副本。\n${value.optString("path")}\n原因：${value.optString("error", "公共目录不可写")}。允许存储后可重新保存，无需重测。"
            "failed" -> "报告导出失败：${value.optString("error")}。采集数据仍保留，请重新保存。"
            else -> "尚未导出报告。"
        }
    }
}
