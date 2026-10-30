package com.shilapi.xcertplay

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Opening the tab is read-only; enumeration, collection and sharing require explicit button actions. */
internal class VideoAdaptationPage(private val activity: Activity, private val palette: DiPlayPalette,
    private val openColors: () -> Unit, private val startAutomatic: () -> Unit = {},
    private val requestStorage: () -> Unit = {},
    private val requestCollectionStorage: () -> Unit = {}, private val combined: Boolean = false) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var disposed = false
    private var visible = false
    private var busy = false
    private val controls = mutableListOf<Button>()
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var saveStatus: TextView
    private var publicationVersion = ""
    private val pollPublication = object : Runnable {
        override fun run() {
            if (disposed || !visible) return
            val suite = AutomaticVideoSuite.read(activity)
            val diagnostic=DecoderDiagnosticsRun.coordinator(activity)
            val version = VideoReportPublisher.latest(activity).toString() + suite.optString("status") + suite.optInt("position") +
                diagnostic.recovering + diagnostic.snapshot().toString()
            if (version != publicationVersion) { publicationVersion = version; refresh() }
            handler.postDelayed(this, 1000)
        }
    }
    private lateinit var model: EditText
    private lateinit var ios: EditText
    val root: ScrollView
    init {
        val pad = FlymeAutoStyle.dp(activity, FlymeAutoStyle.CONTROL_GAP_DP)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun label(id: Int, role: FlymeAutoStyle.TextRole = FlymeAutoStyle.TextRole.BODY) = TextView(activity).apply {
            setText(id); FlymeAutoStyle.text(this, role); setTextColor(palette.primaryText)
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
        }
        fun edit(id: Int, value: String) = EditText(activity).apply {
            hint = activity.getString(id); contentDescription = hint
            setText(value); setSingleLine(true)
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.BODY)
            setTextColor(palette.primaryText); setHintTextColor(palette.secondaryText)
            minimumHeight = FlymeAutoStyle.dp(activity, FlymeAutoStyle.CONTROL_HEIGHT_DP)
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
        }
        fun action(id: Int, primary: Boolean = false, run: () -> Unit) = Button(activity).apply {
            setText(id); FlymeAutoStyle.button(this, palette, primary)
            setOnClickListener { if (!disposed && !busy && !(AutomaticVideoSuite.active(activity) || DecoderDiagnosticsService.active(activity))) run() }
            controls.add(this); content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
        }
        label(R.string.video_adapt_title, if (combined) FlymeAutoStyle.TextRole.SECTION_TITLE else FlymeAutoStyle.TextRole.PAGE_TITLE)
        if (!combined) {
            label(R.string.video_adapt_description, FlymeAutoStyle.TextRole.SECONDARY)
            label(R.string.video_auto_description, FlymeAutoStyle.TextRole.SECONDARY)
            action(R.string.video_auto_start, true) { savePhone(); startAutomatic() }
        }
        label(R.string.video_adapt_guide, FlymeAutoStyle.TextRole.SECONDARY)
        val phone = VideoAdaptationStore.read(activity).optJSONObject("phone")
        model = edit(R.string.video_adapt_phone_model, phone?.optString("model").orEmpty())
        ios = edit(R.string.video_adapt_phone_ios, phone?.optString("ios").orEmpty())
        status = label(R.string.video_adapt_idle)
        saveStatus = label(R.string.video_report_idle, FlymeAutoStyle.TextRole.SECONDARY).apply { setTextIsSelectable(true) }
        action(R.string.video_adapt_collect_device, true) { collectDevice() }
        action(R.string.video_adapt_start, true) { launch() }
        action(R.string.video_adapt_observation, false, ::observation)
        action(R.string.video_adapt_cancel_pending) {
            VideoAdaptationRegistry.current()?.let(VideoAdaptationRegistry::finish)
            status.setText(R.string.video_adapt_interrupted); refresh()
        }
        if (!combined) {
            action(R.string.video_adapt_modes) { savePhone(); openColors() }
            label(R.string.video_adapt_modes_hint, FlymeAutoStyle.TextRole.SECONDARY)
        }
        action(R.string.video_adapt_export, true) { export() }
        action(R.string.video_report_open_downloads) {
            runCatching { activity.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                .onFailure { status.setText(R.string.video_report_downloads_unavailable) }
        }
        action(R.string.video_adapt_clear) {
            if (VideoAdaptationRegistry.current() != null || VideoReportPublisher.busy() || (AutomaticVideoSuite.active(activity) || DecoderDiagnosticsService.active(activity))) { status.setText(R.string.video_adapt_active); return@action }
            activity.appDialogBuilder().setTitle(R.string.video_adapt_clear)
                .setMessage(R.string.video_adapt_clear_confirm)
                .setPositiveButton(R.string.video_adapt_clear) { _, _ ->
                    VideoAdaptationStore.clear(activity); model.setText(""); ios.setText(""); refresh()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        if (combined) action(R.string.decoder_details) { details.visibility = if (details.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        details = label(R.string.video_adapt_idle, FlymeAutoStyle.TextRole.SECONDARY).apply { setTextIsSelectable(true) }
        if (combined) details.visibility = android.view.View.GONE
        root = ScrollView(activity).apply { isFillViewport = true; addView(content) }
        refresh()
    }
    fun setVisible(value: Boolean) {
        val wasVisible = visible
        visible = value
        handler.removeCallbacks(pollPublication)
        if (!value && wasVisible) savePhone() else if (value) { refresh(); handler.post(pollPublication) }
    }
    private fun safe(text: String) = DiagnosticRedactor.redact(text.trim().take(100)) ?: ""
    private fun savePhone() {
        if (disposed) return
        VideoAdaptationStore.update(activity) { value ->
            val phone = value.optJSONObject("phone") ?: JSONObject()
            value.put("phone", phone.put("model", safe(model.text.toString())).put("ios", safe(ios.text.toString())))
        }
    }
    private fun observation() {
        activity.appDialogBuilder().setTitle(R.string.video_adapt_observation)
            .setItems(arrayOf("画面清晰", "静止文字也模糊", "仅移动时模糊", "画面发白", "画面卡顿", "手机明显发热", "手机未明显发热")) { _, index ->
                val choices = listOf("clear", "static_blur", "motion_blur", "washed_out", "stutter", "phone_hot", "phone_not_hot")
                VideoAdaptationStore.update(activity) { value ->
                    val notes = value.optJSONArray("observations") ?: JSONArray()
                    val bounded = JSONArray()
                    for (i in maxOf(0, notes.length() - 15) until notes.length()) bounded.put(notes.get(i))
                    bounded.put(JSONObject().put("observation", choices[index]).put("time", System.currentTimeMillis()))
                    value.put("observations", bounded)
                }; refresh()
            }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun collectDevice(after: (() -> Unit)? = null) {
        savePhone()
        val screen = runCatching { VideoAdaptationDevice.screen(activity) }.getOrElse {
            status.text = activity.getString(R.string.video_adapt_failed, it.javaClass.simpleName)
            return
        }
        val context = activity.applicationContext
        busy = true; status.setText(R.string.video_adapt_busy); refresh()
        worker.execute {
            val result = runCatching { VideoAdaptationDevice.collect(context, screen) { partial -> VideoAdaptationStore.update(context) { it.put("device", partial) } } }
            result.onSuccess { device -> VideoAdaptationStore.update(context) { it.put("device", device) } }
            handler.post {
                if (disposed) return@post
                busy = false
                status.text = result.fold({ activity.getString(R.string.video_adapt_device_saved) },
                    { activity.getString(R.string.video_adapt_failed, it.javaClass.simpleName) })
                refresh()
                if (result.isSuccess && visible) after?.invoke()
            }
        }
    }
    fun continueCollection(allowPrivate: Boolean = false) { if (!disposed) launch(allowPrivate) }
    private fun launch(allowPrivate: Boolean = false) {
        savePhone()
        if ((AutomaticVideoSuite.active(activity) || DecoderDiagnosticsService.active(activity))) { status.setText(R.string.video_adapt_active); return }
        if (!CarPlayBackgroundSession.hasSession()) { status.setText(R.string.video_adapt_no_session); return }
        if (VideoAdaptationRegistry.current() != null) { status.setText(R.string.video_adapt_active); return }
        val color = activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, Activity.MODE_PRIVATE)
        val pendingColor = color.getString("pending_token", null)
        if (ColorMainProbeRegistry.lookup(pendingColor) != null ||
            (pendingColor != null && ColorMainProbeRegistry.isClosing(pendingColor))) {
            status.setText(R.string.video_adapt_conflict); return
        }
        if (!allowPrivate && DiagnosticExportStore.needsStoragePermission(activity)) { requestCollectionStorage(); return }
        if (!VideoAdaptationStore.read(activity).has("device")) { collectDevice { launch(allowPrivate) }; return }
        val token=runCatching{VideoAdaptationRegistry.begin()}.getOrElse{
            status.setText(R.string.video_adapt_conflict);return
        }
        try {
            activity.startActivity(Intent(activity, CarPlayHostActivity::class.java)
                .putExtra(VideoAdaptationRegistry.EXTRA, token)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            VideoAdaptationRegistry.finish(token)
            status.text = activity.getString(R.string.video_adapt_failed, e.javaClass.simpleName)
        }
    }
    private fun refresh() {
        if (disposed) return
        controls.forEach { it.isEnabled = !busy && !(AutomaticVideoSuite.active(activity) || DecoderDiagnosticsService.active(activity)) }
        saveStatus.text = VideoReportPublisher.description(activity)
        val value = VideoAdaptationStore.read(activity)
        val rows = value.optJSONArray("samples") ?: JSONArray()
        val missing = VideoAdaptationStore.gaps(value)
        details.text = buildString {
            appendLine(DecoderDiagnosticsRun.description(activity))
            appendLine(VideoAdaptationSummary.device(value.optJSONObject("device")))
            val suite = AutomaticVideoSuite.read(activity)
            appendLine(VideoAdaptationSummary.latest(suite))
            if (suite.optString("id").isNotEmpty()) {
                appendLine("自动测试：${suite.optString("status")} · ${suite.optInt("position", suite.optJSONArray("results")?.length() ?: 0)}/${suite.optInt("total")}")
                if (suite.has("exportPath")) appendLine("报告：${suite.optString("exportPath")}")
                val autoRows = suite.optJSONArray("results")
                val latest = autoRows?.optJSONObject(autoRows.length() - 1)?.optJSONObject("evidence")
                if (latest != null) {
                    appendLine("最新解码器：${latest.opt("selected_decoder")}")
                    appendLine("最新输出格式：${latest.opt("output_format")}")
                    appendLine("主屏采样：${latest.optJSONObject("automatic_window")?.optJSONObject("summary")}")
                }
            }
            appendLine(activity.getString(R.string.video_adapt_records, rows.length()))
            appendLine(if (missing.isEmpty()) activity.getString(R.string.video_adapt_basic_ready)
                else activity.getString(R.string.video_adapt_missing, missing.joinToString("；")))
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val summary = row.optJSONObject("summary")
                appendLine("${i + 1}. ${if (row.optString("phase") == "static") "静止" else "动态"} · ${if (row.optBoolean("valid")) "区间连续" else "已中断"}")
                appendLine("kbps=${summary?.opt("payloadKbps")} fps=${summary?.opt("submittedToDisplayFps")} queue→output ms=${summary?.opt("queueToOutputMillis")}")
                val mode = row.optJSONObject("begin")?.optJSONObject("requested")
                appendLine("输出：${mode?.optString("actualView")} 软件解码=${mode?.opt("softwareRequired")} YUV=${mode?.opt("softwareYuv")}")
                appendLine("缺项：${row.optJSONArray("missing") ?: JSONArray()}")
            }
        }
    }
    fun continueExport(allowPrivate: Boolean = false) { if (!disposed) export(allowPrivate) }
    private fun export(allowPrivate: Boolean = false) {
        if (!allowPrivate && DiagnosticExportStore.needsStoragePermission(activity)) { requestStorage(); return }
        if (VideoAdaptationRegistry.current() != null) { status.setText(R.string.video_adapt_active); return }
        savePhone()
        busy = true; status.setText(R.string.video_report_writing); refresh()
        VideoReportPublisher.manual(activity.applicationContext) { saved ->
            if (disposed) return@manual
            busy = false; refresh()
            status.text = VideoReportPublisher.description(activity)
            if (saved.optString("status") in listOf("public", "private") && saved.optString("uri").startsWith("content:")) runCatching {
                val uri = android.net.Uri.parse(saved.getString("uri"))
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("decoder-report", uri) }, activity.getString(R.string.video_adapt_export)))
            }
        }
    }

    fun close() {
        if (disposed) return
        if (visible) savePhone()
        disposed = true; handler.removeCallbacksAndMessages(null); worker.shutdown()
    }
}
