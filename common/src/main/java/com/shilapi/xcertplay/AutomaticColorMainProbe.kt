package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.TextureView
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.json.JSONObject
import java.util.Locale

/** Bounded automatic evidence capture; no synthetic touch, settings writes, or visual pass claims. */
internal class AutomaticColorMainProbe(private val activity: Activity, private val request: ColorMainProbeRegistry.Request,
    private val sample: () -> VideoAdaptationSample?,
    private val outputs: () -> Pair<TextureView?, SurfaceView?>,
    private val evidence: (List<String>) -> JSONObject,
    private val completed: (String, JSONObject, Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val deadline = SystemClock.elapsedRealtime() + 75_000
    private var window: VideoAdaptationWindow? = null
    private var firstNanos = 0L
    private var closed = false
    private var capturing = false
    private val palette = DiPlayPalette.LIGHT
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; background = FlymeAutoStyle.card(activity, palette)
        val pad = FlymeAutoStyle.dp(activity, FlymeAutoStyle.CONTROL_GAP_DP); setPadding(pad, pad, pad, pad)
    }
    private val status = TextView(activity).apply {
        FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECONDARY); setTextColor(palette.primaryText)
        root.addView(this)
    }
    init {
        root.addView(Button(activity).apply {
            setText(R.string.video_auto_stop); FlymeAutoStyle.button(this, palette, compact = true)
            setOnClickListener {
                AutomaticVideoSuite.stop(activity, "user_cancelled")
                finish("跳过", evidence(emptyList()).put("automatic_interrupted", true), false)
            }
        })
        root.post { if (!closed) handler.post(poll) }
    }
    private val tick: Runnable get() = poll
    private val poll = object : Runnable {
        override fun run() {
            if (closed || capturing) return
            val state = AutomaticVideoSuite.read(activity)
            if (!AutomaticVideoSuite.active(activity) || state.optString("id") != request.autoRunId || state.optInt("index", -1) != request.index) {
                finish("跳过", evidence(emptyList()).put("automatic_interrupted", true), false); return
            }
            val next = runCatching(sample).getOrNull()
            if (SystemClock.elapsedRealtime() >= deadline) {
                finish("无法显示", evidence(emptyList()).put("automatic_error", "main_screen_timeout_75s")
                    .put("automatic_window", window?.result("deadline") ?: JSONObject()), true)
                return
            }
            if (window == null && next != null) {
                window = VideoAdaptationWindow("automatic_observation", next); firstNanos = next.nanos
            } else if (window != null) {
                if (next == null || !window!!.add(next)) {
                    finish("无法显示", evidence(emptyList()).put("automatic_error", "output_changed_or_unavailable")
                        .put("automatic_window", window!!.result("output_changed_or_unavailable")), true); return
                }
            }
            val summary = window?.result()?.optJSONObject("summary")
            status.text = activity.getString(R.string.video_auto_main_progress, request.index + 1, ColorTestRun.cases.size,
                request.profile.title) + "\n" + if (next == null) activity.getString(R.string.video_adapt_not_ready)
                else String.format(Locale.US, "%.0f kbps · %.1f fps · %.1f ms",
                    summary?.optDouble("payloadKbps", 0.0) ?: 0.0,
                    summary?.optDouble("submittedToDisplayFps", 0.0) ?: 0.0,
                    summary?.optDouble("queueToOutputMillis", 0.0) ?: 0.0)
            if (next != null && next.nanos - firstNanos >= VideoAdaptationWindow.DURATION_NS) capture(next.key)
            else handler.postDelayed(this, 1000)
        }
    }
    private fun capture(key: String) {
        capturing = true; root.visibility = View.INVISIBLE
        root.postOnAnimation { root.postOnAnimation {
            if (closed) return@postOnAnimation
            val (texture, surface) = outputs()
            try {
                ColorProbeImageCapture.capture(activity, texture, surface, nativeSize = true) { files, captureStatus ->
                    if (closed) return@capture
                    val data = evidence(files).put("capture_status", captureStatus)
                        .put("automatic_window", window?.result() ?: JSONObject())
                        .put("visual_verdict", "not_automatically_measured")
                        .put("real_map_motion", "not_automatically_controlled")
                    val after = runCatching(sample).getOrNull()
                    if (after == null || after.key != key) {
                        data.put("capture_context_changed", true); data.optJSONArray("missing")?.put("capture_context_changed")
                    }
                    finish("自动采集（未人工判色）", data, true)
                }
            } catch (error: Exception) { finish("无法显示", evidence(emptyList()).put("automatic_error", error.javaClass.simpleName), true) }
            catch (error: OutOfMemoryError) { finish("无法显示", evidence(emptyList()).put("automatic_error", "capture_memory_unavailable"), true) }
        } }
    }
    private fun finish(verdict: String, data: JSONObject, next: Boolean) {
        if (closed) return
        closed = true; handler.removeCallbacksAndMessages(null)
        data.put("auto_run_id", request.autoRunId).put("automatic", true)
        completed(verdict, data, next)
    }
    fun close() { closed = true; handler.removeCallbacksAndMessages(null) }
}
