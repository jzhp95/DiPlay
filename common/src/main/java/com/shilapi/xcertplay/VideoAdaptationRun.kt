package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.media.LiveVideoCounters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class VideoAdaptationSample(
    val key: String, val nanos: Long, val counters: LiveVideoCounters,
    val cpuMillis: Long, val evidence: JSONObject,
)

/** A single uninterrupted main-screen interval. Values are payload bitrate and app CPU, not link capacity. */
internal class VideoAdaptationWindow(val phase: String, private val first: VideoAdaptationSample) {
    private var last = first
    private val points = JSONArray()
    var failure: String? = null
        private set
    fun add(next: VideoAdaptationSample): Boolean {
        if (failure != null) return false
        if (next.key != first.key) { failure = "session_or_output_changed"; return false }
        if (next.nanos <= last.nanos || next.counters.received < last.counters.received ||
            next.counters.rendered < last.counters.rendered || next.counters.receivedBytes < last.counters.receivedBytes ||
            next.counters.decodeSamples < last.counters.decodeSamples || next.counters.decodeNanos < last.counters.decodeNanos ||
            next.cpuMillis < last.cpuMillis) { failure = "counter_or_clock_reset"; return false }
        if (points.length() >= 30) { failure = "sampling_timeout"; return false }
        points.put(metrics(last, next))
        last = next
        return true
    }
    fun result(interrupted: String? = null): JSONObject {
        val missing = mutableListOf<String>()
        failure?.let(missing::add); interrupted?.let(missing::add)
        if (last.nanos - first.nanos < DURATION_NS) missing.add("sample_shorter_than_20_seconds")
        if (phase == "dynamic" && last.counters.received == first.counters.received) missing.add("no_dynamic_video_received")
        if (phase == "dynamic" && (!first.evidence.has("dragMoveEvents") || !last.evidence.has("dragMoveEvents")))
            missing.add("drag_evidence_unavailable")
        else if (phase == "dynamic" && last.evidence.optLong("dragMoveEvents") <= first.evidence.optLong("dragMoveEvents"))
            missing.add("no_drag_gesture_observed")
        return JSONObject().put("phase", phase).put("identity", first.key).put("valid", missing.isEmpty())
            .put("missing", JSONArray(missing)).put("summary", metrics(first, last))
            .put("intervals", points).put("begin", first.evidence).put("end", last.evidence)
    }
    private fun metrics(a: VideoAdaptationSample, b: VideoAdaptationSample): JSONObject {
        val seconds = (b.nanos - a.nanos) / 1e9
        val count = b.counters.decodeSamples - a.counters.decodeSamples
        val x = a.counters.pipeline; val y = b.counters.pipeline
        fun average(nanos: Long, samples: Long): Any = if (samples > 0 && nanos >= 0) nanos / samples / 1e6 else JSONObject.NULL
        val pipeline = JSONObject()
            .put("applicationWaitMillis", average(y.applicationWaitNanos - x.applicationWaitNanos, y.inputSamples - x.inputSamples))
            .put("inputAcquireAndCopyMillis", average(y.inputWaitNanos - x.inputWaitNanos, y.inputSamples - x.inputSamples))
            .put("codecQueueToDequeueMillis", average(y.codecOutputNanos - x.codecOutputNanos, y.outputSamples - x.outputSamples))
            .put("yuvCopyMillis", average(y.yuvCopyNanos - x.yuvCopyNanos, y.yuvSamples - x.yuvSamples))
            .put("yuvDrawAndSwapMillis", average(y.yuvDrawNanos - x.yuvDrawNanos, y.yuvSamples - x.yuvSamples))
            .put("surfaceReleaseCallMillis", average(y.surfaceReleaseNanos - x.surfaceReleaseNanos, y.surfaceSamples - x.surfaceSamples))
            .put("scope", "app_measurements_not_physical_display_or_touch_latency")
        return JSONObject().put("durationSeconds", seconds).put("pipeline", pipeline)
            .put("receivedFrames", b.counters.received - a.counters.received)
            .put("submittedToDisplayFrames", b.counters.rendered - a.counters.rendered)
            .put("receivedPayloadBytes", b.counters.receivedBytes - a.counters.receivedBytes)
            .put("payloadKbps", if (seconds > 0) (b.counters.receivedBytes - a.counters.receivedBytes) * 8 / 1000.0 / seconds else JSONObject.NULL)
            .put("receivedFps", if (seconds > 0) (b.counters.received - a.counters.received) / seconds else JSONObject.NULL)
            .put("submittedToDisplayFps", if (seconds > 0) (b.counters.rendered - a.counters.rendered) / seconds else JSONObject.NULL)
            .put("queueToOutputMillis", if (count > 0) (b.counters.decodeNanos - a.counters.decodeNanos) / count / 1e6 else JSONObject.NULL)
            .put("processCpuCorePercent", if (seconds > 0) (b.cpuMillis - a.cpuMillis) / (seconds * 10) else JSONObject.NULL)
    }
    companion object { const val DURATION_NS = 20_000_000_000L }
}

/** Intent extras alone cannot create a collector or silently replace a running diagnostic. */
internal object VideoAdaptationRegistry {
    const val EXTRA = "video_adaptation_token"
    private var token: String? = null
    private var started = 0L
    private var lease: com.shilapi.xcertplay.diagnostics.DiagnosticLease.Ticket? = null
    @Synchronized fun begin(now: Long = SystemClock.elapsedRealtime()): String {
        check(current(now) == null) { "collection already active" }
        return UUID.randomUUID().toString().also {
            lease=checkNotNull(com.shilapi.xcertplay.diagnostics.DiagnosticLease.acquire(it,DiPlaySessionService.liveRuntime?.currentConnection?.controller)){"diagnostic already active"}
            token=it;started=now
        }
    }
    @Synchronized fun current(now: Long = SystemClock.elapsedRealtime()): String? {
        if(token!=null && now-started !in 0..900_000)finish(checkNotNull(token))
        return token
    }
    @Synchronized fun accepts(value: String?): Boolean = value != null && value == current()
    @Synchronized fun finish(value: String) { if (token == value) {
        token=null;lease?.let{com.shilapi.xcertplay.diagnostics.DiagnosticLease.release(it,true)};lease=null
    } }
}

internal object VideoAdaptationStore {
    private const val PREFS = "video_adaptation_results_v1"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Synchronized fun read(c: Context): JSONObject =
        runCatching { JSONObject(prefs(c).getString("report", "{}") ?: "{}") }.getOrDefault(JSONObject())
    @Synchronized fun update(c: Context, edit: (JSONObject) -> Unit) {
        val value = read(c); edit(value)
        prefs(c).edit().putString("report", value.toString()).apply()
    }
    @Synchronized fun append(c: Context, row: JSONObject) = update(c) { value ->
        val previous = value.optJSONArray("samples") ?: JSONArray()
        val rows = JSONArray()
        for (i in maxOf(0, previous.length() - 7) until previous.length()) rows.put(previous.get(i))
        rows.put(row); value.put("samples", rows)
        // Prune only this collector's captures, and only those no longer referenced by retained rows.
        val retained = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val captures = rows.getJSONObject(i).optJSONArray("captures") ?: continue
            for (j in 0 until captures.length()) retained.add(captures.getString(j))
        }
        directory(c).listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
    }
    fun directory(c: Context) = File(c.filesDir, "video-adaptation-captures").apply { mkdirs() }
    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
        directory(c).listFiles()?.forEach { it.delete() }
    }
    fun gaps(value: JSONObject): List<String> {
        val missing = mutableListOf<String>()
        if (!value.has("device")) missing.add("设备能力未采集")
        val rows = value.optJSONArray("samples") ?: JSONArray()
        for (phase in listOf("static", "dynamic")) if ((0 until rows.length()).none {
            val row = rows.getJSONObject(it); row.optString("phase") == phase && row.optBoolean("valid")
        }) missing.add(if (phase == "static") "缺少有效静止采样" else "缺少有效动态采样")
        if ((value.optJSONObject("phone")?.optString("model") ?: "").isBlank()) missing.add("iPhone 型号未填写")
        if ((value.optJSONObject("phone")?.optString("ios") ?: "").isBlank()) missing.add("iOS 版本未填写")
        val validRows = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optBoolean("valid") }
        val staticKeys = validRows.filter { it.optString("phase") == "static" }.map { it.optString("identity") }.toSet()
        val dynamicKeys = validRows.filter { it.optString("phase") == "dynamic" }.map { it.optString("identity") }.toSet()
        if (staticKeys.isNotEmpty() && dynamicKeys.isNotEmpty() && staticKeys.intersect(dynamicKeys).isEmpty())
            missing.add("静止与动态采样不属于同一会话配置")
        if (validRows.any { (it.optJSONArray("captures")?.length() ?: 0) == 0 }) missing.add("部分采样缺少原尺寸截图")
        if (validRows.any { (it.optJSONObject("begin")?.optJSONArray("missing")?.length() ?: 0) > 0 })
            missing.add("部分视频与颜色证据缺失，详见报告")
        val deviceGaps = value.optJSONObject("device")?.optJSONArray("missing")
        if (deviceGaps != null && deviceGaps.length() > 0) missing.add("设备存在不可读项，详见报告")
        val extended = value.optJSONObject("device")?.optJSONObject("systemInformation")?.optJSONArray("unavailable")
        if (extended != null && extended.length() > 0) missing.add("部分扩展硬件信息不可读，报告含原因")
        if (value.optJSONObject("device")?.optBoolean("collectionIncomplete") == true) missing.add("设备信息采集未完成，报告含已读部分")
        return missing
    }
}
