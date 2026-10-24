package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.TextureView
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.json.JSONObject

/** A diagnostic overlay on the actual host; the production video viewport stays full size below it. */
internal class ColorMainProbeOverlay(private val activity: Activity, private val request: ColorMainProbeRegistry.Request,
    private val ready: () -> Boolean, private val connection: () -> String,
    private val outputs: () -> Pair<TextureView?, SurfaceView?>,
    private val evidence: (List<String>) -> JSONObject,
    private val completed: (String, JSONObject, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var capturing = false
    private var unobstructedObserved = false
    private val palette = DiPlayPalette.of((activity as? AppAppearanceOwner)?.currentAppNight ?: false)
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = FlymeAutoStyle.card(activity, palette)
        val pad = FlymeAutoStyle.dp(activity, FlymeAutoStyle.CONTROL_GAP_DP)
        setPadding(pad, pad, pad, pad)
        isClickable = true
    }
    private val status = TextView(activity).apply {
        FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECONDARY)
        setTextColor(palette.primaryText)
    }
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            status.text = if (ready()) activity.getString(R.string.color_test_rendered)
                else activity.getString(R.string.color_main_waiting_short)
            main.postDelayed(this, 500)
        }
    }
    init {
        root.addView(TextView(activity).apply {
            text = request.profile.title
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECTION_TITLE)
            setTextColor(palette.primaryText)
        })
        root.addView(status)
        root.addView(Button(activity).apply {
            setText(R.string.color_main_observe); FlymeAutoStyle.button(this, palette, compact = true)
            setOnClickListener {
                if (capturing || closed || !ready()) { status.setText(R.string.color_test_not_rendered); return@setOnClickListener }
                val before = evidence(emptyList())
                unobstructedObserved = false
                root.visibility = View.INVISIBLE
                main.postDelayed({
                    if (!closed && !capturing) {
                        val after = evidence(emptyList())
                        unobstructedObserved = ready() && listOf("configuration_sha256", "output_instance", "output_generation").all { before.opt(it) == after.opt(it) }
                        root.visibility = View.VISIBLE
                    }
                }, 8_000)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(activity).apply {
            setText(R.string.color_main_record); FlymeAutoStyle.button(this, palette, primary = true, compact = true)
            setOnClickListener {
                if (capturing || closed) return@setOnClickListener
                val choices = arrayOf("正常", "整体发白", "浅灰与白色混在一起", "黑色发灰", "偏色", "隐藏测试控件时颜色不同", "无法显示", "跳过")
                activity.appDialogBuilder().setTitle(R.string.color_test_record).setItems(choices) { _, i ->
                    val verdict = choices[i]
                    val technical = verdict == "无法显示" || verdict == "跳过"
                    if (!ready() && !technical) { status.setText(R.string.color_test_not_rendered); return@setItems }
                    if (technical) { completed(verdict, evidence(emptyList()), true); return@setItems }
                    if (!unobstructedObserved) { status.setText(R.string.color_main_observe_required); return@setItems }
                    val before = evidence(emptyList())
                    capturing = true; root.visibility = View.INVISIBLE
                    root.postOnAnimation { root.postOnAnimation {
                        if (closed) return@postOnAnimation
                        val (texture, surface) = outputs()
                        ColorProbeImageCapture.capture(activity, texture, surface) { files, captureStatus ->
                            if (closed) return@capture
                            val data = evidence(files).put("capture_status", captureStatus)
                                .put("unobstructed_observation_millis", 8_000)
                                .put("diagnostic_controls_hidden_during_capture", true)
                            val changed = listOf("configuration_sha256", "output_instance", "output_generation").any { before.opt(it) != data.opt(it) }
                            if (changed || !data.optBoolean("rendered")) {
                                data.put("capture_context_changed", true).put("before_capture", before)
                                data.getJSONArray("missing").put("capture_context_changed")
                            }
                            val gaps = data.getJSONArray("missing")
                            if (texture != null && !captureStatus.has("composited-window")) gaps.put("composited_capture")
                            if (texture != null && !captureStatus.has("decoded-texture")) gaps.put("decoded_texture_capture")
                            completed(verdict, data, true)
                        }
                    } }
                }.setNegativeButton(R.string.cancel, null).show()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(activity).apply {
            setText(R.string.color_main_cancel); FlymeAutoStyle.button(this, palette, compact = true)
            setOnClickListener { completed("跳过", evidence(emptyList()).put("interrupted", true), false) }
        }, LinearLayout.LayoutParams(-1, -2))
        poll.run()
    }
    fun close() { closed = true; main.removeCallbacksAndMessages(null) }
}
