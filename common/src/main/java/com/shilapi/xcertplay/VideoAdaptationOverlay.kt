package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.SurfaceView
import android.view.TextureView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.json.JSONArray

/** Attached above the real host without resizing it. It owns no connection or audio resources. */
internal class VideoAdaptationOverlay(private val activity: Activity, private val token: String,
    private val sample: () -> VideoAdaptationSample?,
    private val outputs: () -> Pair<TextureView?, SurfaceView?>, private val back: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var window: VideoAdaptationWindow? = null
    private var startedNs = 0L
    private var closed = false
    private var capturing = false
    private var pendingCapture: org.json.JSONObject? = null
    private var captureDeadline: Runnable? = null
    private val buttons = mutableListOf<Button>()
    private val palette = DiPlayPalette.of((activity as? AppAppearanceOwner)?.currentAppNight ?: false)
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = FlymeAutoStyle.card(activity, palette)
        val pad = FlymeAutoStyle.dp(activity, FlymeAutoStyle.CONTROL_GAP_DP)
        setPadding(pad, pad, pad, pad)
    }
    private val status = TextView(activity).apply {
        setText(R.string.video_adapt_overlay_ready)
        FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECONDARY)
        setTextColor(palette.primaryText); root.addView(this)
    }
    private fun button(text: Int, action: () -> Unit) = Button(activity).apply {
        setText(text); FlymeAutoStyle.button(this, palette, compact = true)
        setOnClickListener { if (!closed && !capturing) action() }
        root.addView(this, LinearLayout.LayoutParams(-1, -2)); buttons.add(this)
    }
    init {
        button(R.string.video_adapt_static) { start("static") }
        button(R.string.video_adapt_dynamic) { start("dynamic") }
        button(R.string.video_adapt_return) { close(); back() }
    }
    private fun start(phase: String) {
        if (window != null || !VideoAdaptationRegistry.accepts(token)) return
        val first = runCatching(sample).getOrNull()
        if (first == null) { status.setText(R.string.video_adapt_not_ready); return }
        window = VideoAdaptationWindow(phase, first); startedNs = first.nanos
        buttons.forEach { it.visibility = View.GONE }
        status.text = activity.getString(if (phase == "static") R.string.video_adapt_static_running else R.string.video_adapt_dynamic_running, 20)
        handler.postDelayed(tick, 1000)
    }
    private val tick = object : Runnable {
        override fun run() {
            val active = window ?: return
            if (closed || !VideoAdaptationRegistry.accepts(token)) { stop("collector_expired"); return }
            val next = runCatching(sample).getOrNull()
            if (next == null) { stop("host_or_decoder_not_ready"); return }
            if (!active.add(next)) { stop(null); return }
            val remaining = ((VideoAdaptationWindow.DURATION_NS - (next.nanos - startedNs) + 999_999_999) / 1_000_000_000).coerceAtLeast(0)
            status.text = activity.getString(if (active.phase == "static") R.string.video_adapt_static_running else R.string.video_adapt_dynamic_running, remaining)
            if (remaining == 0L) stop(null) else handler.postDelayed(this, 1000)
        }
    }
    private fun stop(reason: String?) {
        val active = window ?: return
        window = null; handler.removeCallbacks(tick)
        val result = active.result(reason).put("collectedAt", System.currentTimeMillis()).put("collectorToken", token).put("sampleId", java.util.UUID.randomUUID().toString())
        if (!result.optBoolean("valid") || closed) {
            persistAndPublish(result)
            if (!closed) { status.setText(R.string.video_adapt_interrupted); buttons.forEach { it.visibility = View.VISIBLE } }
            return
        }
        pendingCapture = result
        capturing = true; root.visibility = View.INVISIBLE
        captureDeadline = Runnable {
            if (pendingCapture === result) finishCapture(result, emptyList(), org.json.JSONObject().put("captureError", "capture_deadline_5s"))
        }.also { handler.postDelayed(it, 5_000) }
        root.postOnAnimation { root.postOnAnimation {
            if (closed) { capturing = false; return@postOnAnimation }
            val before = runCatching(sample).getOrNull()
            if (before == null || before.key != result.optString("identity")) {
                result.put("valid", false).getJSONArray("missing").put("capture_context_changed")
                finishCapture(result, emptyList(), org.json.JSONObject())
                return@postOnAnimation
            }
            val (texture, surface) = outputs()
            try {
                ColorProbeImageCapture.capture(activity, texture, surface, nativeSize = true,
                    directory = VideoAdaptationStore.directory(activity)) { files, captureStatus ->
                    finishCapture(result, files, captureStatus)
                }
            } catch (error: Exception) {
                finishCapture(result, emptyList(), org.json.JSONObject().put("captureError", error.javaClass.simpleName))
            } catch (error: OutOfMemoryError) {
                finishCapture(result, emptyList(), org.json.JSONObject().put("captureError", "out_of_memory"))
            }
        } }
    }
    private fun finishCapture(result: org.json.JSONObject, files: List<String>, captureStatus: org.json.JSONObject) {
        if (pendingCapture !== result) {
            files.forEach { java.io.File(VideoAdaptationStore.directory(activity), it).delete() }; return
        }
        captureDeadline?.let(handler::removeCallbacks); captureDeadline = null
        capturing = false; pendingCapture = null
        if (closed) {
            files.forEach { java.io.File(VideoAdaptationStore.directory(activity), it).delete() }
            return
        }
        val after = runCatching(sample).getOrNull()
        if (after == null || after.key != result.optString("identity") || !VideoAdaptationRegistry.accepts(token)) {
            result.put("valid", false); result.getJSONArray("missing").put("capture_context_changed")
        }
        if (files.isEmpty()) result.getJSONArray("missing").put("capture_unavailable")
        result.put("captures", JSONArray(files)).put("captureStatus", captureStatus)
        persistAndPublish(result)
        root.visibility = View.VISIBLE; buttons.forEach { it.visibility = View.VISIBLE }
        status.setText(R.string.video_report_writing)
    }
    private fun persistAndPublish(result: org.json.JSONObject) {
        VideoAdaptationStore.append(activity, result)
        VideoReportPublisher.manual(activity.applicationContext) {
            if (!closed) status.text = VideoReportPublisher.description(activity)
        }
    }
    fun close() {
        if (closed) return
        closed = true
        stop("host_backgrounded_or_closed")
        pendingCapture?.let {
            it.put("valid", false).getJSONArray("missing").put("capture_interrupted")
            persistAndPublish(it)
        }
        pendingCapture = null
        handler.removeCallbacksAndMessages(null)
        VideoAdaptationRegistry.finish(token)
        (root.parent as? android.view.ViewGroup)?.removeView(root)
    }
}
