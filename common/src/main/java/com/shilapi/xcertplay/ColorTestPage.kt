package com.shilapi.xcertplay

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.VideoColorOverride
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** All color experiments are local to this page. Opening it never starts decoding or changes settings. */
internal class ColorTestPage(private val activity: Activity, private val palette: DiPlayPalette) {
    val run = ColorTestRun()
    private val prefs = activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, Activity.MODE_PRIVATE)
    private val player = ColorTestPlayer(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val pendingProbePoll = Runnable { if (visible && !disposed) consumeMainResult() }
    private val exportWorker = Executors.newSingleThreadExecutor()
    private val lines = prefs.getString("log", "")!!.lines().filter { it.isNotBlank() }.toMutableList()
    private val caseLines = mutableListOf<String>()
    private var active = false
    private var visible = false
    private var disposed = false
    private var epoch = 0
    private var texture: TextureView? = null
    private var surfaceView: SurfaceView? = null
    private var wrapper: Surface? = null
    private var standard: Int? = null
    private var range: Int? = null
    private var savedPicture = false
    private var exporting = false
    private var capturing = false
    private var captureName: String? = null
    private lateinit var preview: FrameLayout
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var coverage: TextView
    private lateinit var details: TextView
    private lateinit var observation: Button
    private lateinit var start: Button
    private lateinit var matrixButton: Button
    private lateinit var rangeButton: Button
    private lateinit var pictureButton: Button
    val root: ScrollView

    init {
        run.select(prefs.getInt("index", 0))
        runCatching {
            val history = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until history.length()) {
                val item = history.getJSONObject(i)
                run.history.add(ColorTestRun.Observation(item.getInt("index"), item.getString("verdict"),
                    if (item.isNull("range")) null else item.getInt("range"),
                    if (item.isNull("standard")) null else item.getInt("standard"),
                    item.getBoolean("savedPicture"), if (item.isNull("capture")) null else item.getString("capture"), item.getLong("time"), item.optJSONObject("evidence")?.toString() ?: "{}"))
            }
        }
        ColorTestRun.cases.indices.forEach { i -> prefs.getString("result_$i", null)?.let { run.results[i] = it } }
        val padding = dp(FlymeAutoStyle.CONTROL_GAP_DP)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun label(id: Int, role: FlymeAutoStyle.TextRole = FlymeAutoStyle.TextRole.BODY) = TextView(activity).apply {
            setText(id); FlymeAutoStyle.text(this, role); setTextColor(palette.primaryText)
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = padding })
        }
        fun action(id: Int, primary: Boolean = false, callback: () -> Unit) = Button(activity).apply {
            setText(id); FlymeAutoStyle.button(this, palette, primary)
            setOnClickListener { callback() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = padding })
        }
        label(R.string.color_test_title, FlymeAutoStyle.TextRole.PAGE_TITLE)
        label(R.string.color_test_description, FlymeAutoStyle.TextRole.SECONDARY)
        progress = label(R.string.color_test_ready)
        coverage = label(R.string.color_evidence_pending, FlymeAutoStyle.TextRole.SECONDARY)
        start = action(R.string.color_test_start, true) {
            if (prefs.getString("pending_token", null) != null) { status.setText(R.string.color_main_closing); return@action }
            if (active) stop() else { active = true; playCurrent() }
            update()
        }
        action(R.string.color_test_choose) {
            if (prefs.getString("pending_token", null) != null) { status.setText(R.string.color_main_closing); return@action }
            activity.appDialogBuilder().setTitle(R.string.color_test_choose)
                .setItems(ColorTestRun.cases.mapIndexed { i, case -> "${i + 1}. ${case.title} · ${coverageState(i)}" }.toTypedArray()) { _, i ->
                    run.select(i); range = run.current.color.range; standard = null; savedPicture = false
                    persist(); if (active) playCurrent() else update()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        preview = object : FrameLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = View.MeasureSpec.getSize(widthMeasureSpec)
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.EXACTLY))
            }
        }.apply { setBackgroundColor(Color.BLACK) }
        content.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = padding })
        label(R.string.color_test_reference)
        content.addView(ColorReferenceView(activity), LinearLayout.LayoutParams(-1, dp(FlymeAutoStyle.CONTROL_HEIGHT_DP * 2)).apply {
            bottomMargin = padding
        })
        status = label(R.string.color_test_ready)
        observation = action(R.string.color_test_record, true) {
            val values = arrayOf("正常", "整体发白", "浅灰与白色混在一起", "黑色发灰", "偏色", "无法显示", "跳过")
            activity.appDialogBuilder().setTitle(R.string.color_test_record)
                .setItems(values) { _, i -> record(values[i]) }.setNegativeButton(R.string.cancel, null).show()
        }
        action(R.string.color_test_previous) {
            if (prefs.getString("pending_token", null) != null) { status.setText(R.string.color_main_closing); return@action }
            run.select((run.index - 1).coerceAtLeast(0)); range = run.current.color.range
            standard = null; savedPicture = false; persist(); if (active) playCurrent() else update()
        }
        rangeButton = action(R.string.color_test_range) {
            activity.appDialogBuilder().setTitle(R.string.color_test_range)
                .setItems(arrayOf("自动（采用视频流）", "请求全范围 0–255", "请求有限范围 16–235")) { _, i ->
                    range = listOf(null, 1, 2)[i]; if (active) playCurrent() else update()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        matrixButton = action(R.string.color_test_matrix) {
            activity.appDialogBuilder().setTitle(R.string.color_test_matrix)
                .setItems(arrayOf("自动（采用视频流）", "请求 BT.709", "请求 BT.601 PAL", "请求 BT.601 NTSC")) { _, i ->
                    standard = listOf(null, 1, 2, 4)[i]; if (active) playCurrent() else update()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        pictureButton = action(R.string.color_test_picture) {
            savedPicture = !savedPicture
            if (active) playCurrent() else update()
        }
        label(R.string.color_test_reference_hint, FlymeAutoStyle.TextRole.SECONDARY)
        action(R.string.color_test_export, true, ::export)
        action(R.string.color_test_clear) {
            if (exporting || capturing) return@action
            activity.appDialogBuilder().setTitle(R.string.color_test_clear)
                .setMessage(R.string.color_test_clear_confirm)
                .setPositiveButton(R.string.color_test_clear) { _, _ ->
                    stop(); run.results.clear(); run.history.clear(); run.select(0); lines.clear(); range = null; standard = null; savedPicture = false
                    prefs.edit().clear().apply(); captureDirectory().listFiles()?.forEach { it.delete() }; update()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        details = label(R.string.color_test_ready, FlymeAutoStyle.TextRole.SECONDARY).apply { setTextIsSelectable(true) }
        root = ScrollView(activity).apply { isFillViewport = true; addView(content) }
        range = run.current.color.range
        update()
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (!value) { handler.removeCallbacks(pendingProbePoll); stop() } else consumeMainResult()
    }
    private fun consumeMainResult() {
        val token = prefs.getString("pending_token", null) ?: return
        val raw = prefs.getString("pending_result", null)
        if (raw == null) {
            if (ColorMainProbeRegistry.lookup(token) == null) {
                status.setText(R.string.color_main_interrupted)
                log("正式主屏采集未完成：进程重启或测试令牌已失效；本项需要补采")
                prefs.edit().remove("pending_token").remove("pending_index").apply()
            }
            return
        }
        val data = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (data.optString("token") != token) return
        if (!data.optBoolean("teardown_complete")) {
            if (ColorMainProbeRegistry.isClosing(token)) {
                status.setText(R.string.color_main_closing)
                handler.removeCallbacks(pendingProbePoll); handler.postDelayed(pendingProbePoll, 200)
                return
            }
            data.put("continue", false).put("teardown_complete", true)
            data.optJSONObject("evidence")?.apply {
                put("teardown_interrupted", true)
                optJSONArray("missing")?.put("connection_teardown_unconfirmed")
            }
        }
        val index = data.optInt("index", -1)
        if (index !in ColorTestRun.cases.indices || ColorTestRun.cases[index].mainProfile == null) return
        val evidence = data.optJSONObject("evidence") ?: JSONObject()
        run.select(index); run.rendered = evidence.optBoolean("rendered")
        val capture = data.optString("capture").takeIf { it.isNotEmpty() && it != "null" }
        val verdict = data.optString("verdict", "跳过")
        val accepted = run.record(verdict, range = ColorTestRun.cases[index].color.range,
            savedPicture = evidence.optJSONObject("requested")?.optBoolean("neutralPicture") == false,
            capture = capture, evidence = evidence.toString())
        if (!accepted) {
            evidence.put("observer_verdict", verdict).put("observation_failed", "stream_changed_during_capture")
            evidence.optJSONArray("missing")?.put("observation_context_changed")
            run.record("无法显示", capture = capture, evidence = evidence.toString())
        }
        persist()
        prefs.edit().remove("pending_token").remove("pending_result").remove("pending_index").apply()
        if (accepted && data.optBoolean("continue") && !evidence.optBoolean("capture_context_changed") && index < ColorTestRun.cases.lastIndex) {
            run.select(index + 1); range = run.current.color.range; standard = null; savedPicture = false
            persist(); active = true
            root.post { if (visible && !disposed) playCurrent() }
        } else {
            status.setText(if (!accepted || evidence.optBoolean("capture_context_changed") || evidence.optBoolean("teardown_interrupted")) R.string.color_main_interrupted else R.string.color_test_finished)
            update()
        }
    }
    private fun launchMainProbe() {
        if (prefs.getString("pending_token", null) != null) {
            status.setText(R.string.color_main_closing); return
        }
        val request = ColorMainProbeRegistry.begin(run.index, run.current.color)
        prefs.edit().putString("pending_token", request.token).putInt("pending_index", run.index)
            .remove("pending_result").apply()
        // Finish an existing host before starting a temporary host; never race singleTask recreation with its old shutdown.
        CarPlayBackgroundSession.stop {
            activity.runOnUiThread {
                if (!visible || disposed || ColorMainProbeRegistry.lookup(request.token) == null) {
                    ColorMainProbeRegistry.finish(request.token)
                    ColorMainProbeRegistry.teardownFinished(request.token)
                    prefs.edit().remove("pending_token").remove("pending_index").apply()
                    return@runOnUiThread
                }
                activity.startActivity(Intent(activity, CarPlayHostActivity::class.java)
                    .putExtra(ColorMainProbeRegistry.EXTRA_TOKEN, request.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    fun stop() {
        epoch++; active = false; capturing = false; run.rendered = false
        player.stop(); preview.removeAllViews(); texture = null; surfaceView = null
        wrapper?.let { old -> player.stop { old.release() } }; wrapper = null
        if (::status.isInitialized) status.setText(R.string.color_test_stopped)
        update()
    }
    fun close() {
        if (disposed) return
        stop(); disposed = true; player.close(); exportWorker.shutdown()
    }
    private fun playCurrent() {
        if (!active || !visible || disposed) return
        val token = ++epoch
        caseLines.clear()
        run.rendered = false; capturing = false
        player.stop()
        wrapper?.let { old -> player.stop { old.release() } }; wrapper = null
        preview.removeAllViews(); texture = null; surfaceView = null
        status.setText(R.string.color_test_waiting)
        if (run.current.mainProfile != null) {
            launchMainProbe(); update(); return
        }
        log("CASE ${run.index + 1}: ${run.current.title}; range=$range standard=$standard savedPicture=$savedPicture")
        fun begin(surface: Surface) {
            if (token != epoch || !active) return
            player.start(run.current, VideoColorOverride(range, standard), surface) { message ->
                if (token != epoch || !active) return@start
                log(message)
                if (message.contains("first frame rendered")) run.rendered = true
                if (message.startsWith("错误：") || message.contains("configuration failed")) run.rendered = false
                when {
                    message.contains("first frame rendered") -> status.setText(R.string.color_test_rendered)
                    message.startsWith("错误：") || message.startsWith("提示：") -> status.text = message
                    message.contains("diagnostic software decoder unavailable") -> status.setText(R.string.color_software_unavailable)
                    message.contains("configuration failed") -> status.setText(R.string.color_test_not_rendered)
                }
                update()
            }
        }
        if (run.current.surface) {
            val output = SurfaceView(activity)
            surfaceView = output
            output.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = begin(holder.surface)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    player.detachSurface(holder.surface)
                    if (token == epoch) run.rendered = false
                }
            })
            preview.addView(output, FrameLayout.LayoutParams(-1, -1))
        } else {
            val output = TextureView(activity).apply { isOpaque = false }
            texture = output
            if (savedPicture) CarPlayPicture.apply(output, CarPlayPicture.preferences(activity))
            output.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    if (token != epoch || !active) return
                    val surface = Surface(st); wrapper = surface; begin(surface)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    // Keep the owned texture alive until the serial decoder cleanup finishes.
                    if (token == epoch) player.stop { st.release() } else player.afterCleanup { st.release() }
                    return false
                }
            }
            preview.addView(output, FrameLayout.LayoutParams(-1, -1))
        }
        update()
        root.post { if (token == epoch && active) root.smoothScrollTo(0, preview.top) }
    }
    private fun record(verdict: String) {
        if (capturing || exporting) return
        // "无法显示" and "跳过" remain available even with no surface or decoder.
        val technical = verdict == "无法显示" || verdict == "跳过"
        if (!technical && !run.rendered) { status.setText(R.string.color_test_not_rendered); return }
        val token = epoch
        val beforeCapture = player.evidence()
        capturing = true; captureName = null; update()
        fun complete(bitmap: Bitmap?) {
            if (token != epoch || disposed) { bitmap?.recycle(); return }
            capturing = false
            if (bitmap != null) {
                val file = File(captureDirectory(), "observation-${UUID.randomUUID()}.png")
                runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    .onSuccess {
                        captureName = file.name
                        log("capture=${file.name}; software pixels only (not panel measurements)")
                        if (run.current.asset != null) {
                            val samples = ColorReferenceView.colors.indices.joinToString(" ") { i ->
                                val color = bitmap.getPixel(((i % 8 + .5f) * bitmap.width / 8).toInt(), ((i / 8 + .5f) * bitmap.height / 3).toInt())
                                "%06x".format(color and 0xffffff)
                            }
                            log("RGB patch samples: $samples")
                        }
                    }.onFailure { log("截图保存失败：${it.javaClass.simpleName}") }
                bitmap.recycle()
            } else log("本项未获取截图，观察结果仍保留")
            val requested = JSONObject().apply {
                put("asset", run.current.asset); put("range", range ?: JSONObject.NULL)
                put("standard", standard ?: JSONObject.NULL); put("savedPicture", savedPicture)
                put("softwareRequired", run.current.forceSoftware)
                put("actualView", if (run.current.surface) "SurfaceView" else "TextureView")
            }
            val afterCapture = player.evidence()
            val evidence = ColorProbeEvidence.capture(afterCapture, run.rendered, run.current.forceSoftware,
                listOfNotNull(captureName), "production_decoder_fixture", requested).put("logs", JSONArray(caseLines.toList()))
            if (!technical && (beforeCapture?.sourceHash != afterCapture?.sourceHash ||
                    beforeCapture?.output?.instance != afterCapture?.output?.instance ||
                    beforeCapture?.output?.generation != afterCapture?.output?.generation)) {
                evidence.put("capture_context_changed", true).put("before_capture", ColorProbeEvidence.capture(beforeCapture,
                    beforeCapture?.output?.firstFrameRendered == true, run.current.forceSoftware, emptyList(), "before_capture", requested))
                evidence.getJSONArray("missing").put("capture_context_changed")
            }
            if (run.record(verdict, range, standard, savedPicture, captureName, evidence.toString())) {
                log("OBSERVATION ${run.index + 1}: $verdict; range=$range standard=$standard savedPicture=$savedPicture")
                persist()
                val retained = observationCaptures().toSet()
                captureDirectory().listFiles()?.filter { it.name.startsWith("observation-") && it.name !in retained }?.forEach { it.delete() }
                if (evidence.optBoolean("capture_context_changed")) {
                    stop(); status.setText(R.string.color_main_interrupted)
                } else if (run.index < ColorTestRun.cases.lastIndex) {
                    run.select(run.index + 1); range = run.current.color.range; standard = null; savedPicture = false
                    persist(); if (active) playCurrent() else update()
                } else { stop(); status.setText(R.string.color_test_finished) }
            }
            update()
        }
        if (technical) { complete(null); return }
        val videoTexture = texture
        if (videoTexture != null) {
            if (!savedPicture) { complete(videoTexture.getBitmap(640, 360)); return }
            // A window copy includes the view's color filter, but only when the entire preview is visible.
            root.scrollTo(0, preview.top)
            videoTexture.postOnAnimation {
                videoTexture.postOnAnimation {
                    if (token != epoch || disposed) { capturing = false; update(); return@postOnAnimation }
                    val visibleRect = Rect()
                    if (!videoTexture.getGlobalVisibleRect(visibleRect) || visibleRect.width() != videoTexture.width || visibleRect.height() != videoTexture.height) {
                        log("调整后截图未获取：窗口不能完整容纳预览，未用局部图冒充完整视频")
                        complete(null)
                        return@postOnAnimation
                    }
                    val location = IntArray(2); videoTexture.getLocationInWindow(location)
                    val rect = Rect(location[0], location[1], location[0] + videoTexture.width, location[1] + videoTexture.height)
                    val windowBitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                    try {
                        PixelCopy.request(activity.window, rect, windowBitmap, { result ->
                            if (result == PixelCopy.SUCCESS) complete(windowBitmap) else { windowBitmap.recycle(); complete(null) }
                        }, handler)
                    } catch (_: Exception) { windowBitmap.recycle(); complete(null) }
                }
            }
            return
        }
        val videoSurface = surfaceView
        if (videoSurface == null || !videoSurface.holder.surface.isValid) { complete(null); return }
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(videoSurface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) complete(bitmap) else { bitmap.recycle(); complete(null) }
            }, handler)
        } catch (_: Exception) { bitmap.recycle(); complete(null) }
    }
    private fun update() {
        if (!::progress.isInitialized) return
        progress.text = activity.getString(R.string.color_test_progress, run.index + 1, ColorTestRun.cases.size,
            run.current.title, run.results.size)
        start.setText(if (active) R.string.color_test_stop else R.string.color_test_start)
        val pendingMain = prefs.getString("pending_token", null) != null
        start.isEnabled = !pendingMain
        observation.isEnabled = !capturing && !exporting && !pendingMain
        rangeButton.text = activity.getString(R.string.color_test_range_value, when(range) { 1 -> "全范围"; 2 -> "有限范围"; else -> "自动" })
        matrixButton.text = activity.getString(R.string.color_test_matrix_value, when(standard) { 1 -> "BT.709"; 2 -> "BT.601 PAL"; 4 -> "BT.601 NTSC"; else -> "自动" })
        pictureButton.text = activity.getString(R.string.color_test_picture_value, if (savedPicture) "已保存参数" else "原始画面")
        pictureButton.isEnabled = !run.current.surface && run.current.mainProfile == null
        rangeButton.isEnabled = run.current.mainProfile == null && !pendingMain
        matrixButton.isEnabled = run.current.mainProfile == null && !pendingMain
        val states = ColorTestRun.cases.indices.map(::coverageState)
        coverage.text = activity.getString(R.string.color_evidence_summary, states.count { it == "齐全" },
            ColorTestRun.cases.size, states.count { it == "未测试" }, states.count { it.startsWith("缺") }, states.count { it == "不支持" }, states.count { it == "平台未报告" })
        if (::details.isInitialized) details.text = run.report() + "\n\n" + lines.takeLast(12).joinToString("\n")
    }
    private fun log(message: String) {
        if (active && run.current.mainProfile == null) {
            caseLines.add(message.take(1200))
            while (caseLines.size > 64) caseLines.removeAt(24)
        }
        lines.add("${System.currentTimeMillis()} $message")
        while (lines.size > 240) lines.removeAt(0)
        persist()
    }
    private fun historyJson(): JSONArray = JSONArray().apply {
        run.history.forEach { observation -> put(JSONObject().apply {
            put("index", observation.index); put("case", ColorTestRun.cases[observation.index].title)
            put("software_required", ColorTestRun.cases[observation.index].forceSoftware)
            put("main_profile", ColorTestRun.cases[observation.index].mainProfile ?: JSONObject.NULL)
            put("verdict", observation.verdict); put("range", observation.range ?: JSONObject.NULL)
            put("standard", observation.standard ?: JSONObject.NULL); put("savedPicture", observation.savedPicture)
            put("capture", observation.capture ?: JSONObject.NULL); put("time", observation.time)
            put("evidence", evidenceWithFiles(observation.evidence))
        }) }
    }
    private fun evidenceWithFiles(raw: String): JSONObject {
        val evidence = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val missing = evidence.optJSONArray("missing") ?: JSONArray().put("evidence_snapshot").also { evidence.put("missing", it) }
        ColorProbeEvidence.captures(evidence).forEach { name ->
            val key = "capture_file_missing:$name"
            if (!File(captureDirectory(), name).isFile && (0 until missing.length()).none { missing.optString(it) == key }) missing.put(key)
        }
        return evidence
    }
    private fun coverageState(index: Int): String {
        val record = run.history.lastOrNull { it.index == index } ?: return "未测试"
        val evidence = evidenceWithFiles(record.evidence)
        if (evidence.optString("unsupported_reason").isNotEmpty()) return "不支持"
        val missing = evidence.optJSONArray("missing") ?: return "缺证据"
        if (missing.length() > 0 && (0 until missing.length()).all { missing.optString(it).startsWith("output_color-") }) return "平台未报告"
        return if (missing.length() == 0 && record.verdict !in listOf("跳过", "无法显示")) "齐全" else "缺 ${missing.length()} 项"
    }
    private fun observationCaptures(): List<String> = run.history.flatMap { item ->
        listOfNotNull(item.capture) + ColorProbeEvidence.captures(runCatching { JSONObject(item.evidence) }.getOrDefault(JSONObject()))
    }.distinct()
    private fun persist() {
        val editor = prefs.edit().putString("history", historyJson().toString()).putInt("index", run.index).putString("log", lines.joinToString("\n"))
        run.results.forEach { (i, result) -> editor.putString("result_$i", result) }; editor.apply()
    }
    private fun captureDirectory() = ColorProbeEvidence.directory(activity)
    private fun export() {
        if (exporting || disposed || capturing) return
        if (prefs.getString("pending_token", null) != null) { status.setText(R.string.color_main_closing); return }
        exporting = true
        update()
        val observations = historyJson().toString(2)
        val captures = observationCaptures().map { File(captureDirectory(), it) }
        val report = buildString {
            appendLine("DiPlay 画面颜色测试；主观观察并非自动校色结果")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}")
            appendLine("app=${activity.packageName}; hardwareAccelerated=${activity.window.decorView.isHardwareAccelerated}; hardware=${Build.HARDWARE}; board=${Build.BOARD}; build=${Build.DISPLAY}")
            appendLine("注意：请求颜色参数不保证车机采纳；请结合输出格式日志。截图不包含面板亮度、背光和显示后处理。")
            appendLine(run.report())
            appendLine("\n采集完整性（不支持和缺项不计为通过）")
            ColorTestRun.cases.forEachIndexed { index, case -> appendLine("${index + 1}. ${case.title}: ${coverageState(index)}") }
            appendLine("\n本机解码器清单（API28 软件识别基于已知平台名称前缀）")
            com.shilapi.xcertplay.media.diagnosticSoftwareCandidates().forEach { appendLine("${it.name}; types=${it.types}; softwareReported=${it.softwareReported}") }
            appendLine("\n逐次观察（含实际参数与唯一截图）\n$observations"); appendLine("\n日常画面参数（测试不会修改）")
            CarPlayPicture.keys.forEach { appendLine("$it=${CarPlayPicture.value(CarPlayPicture.preferences(activity), it)}") }
            appendLine("\n正式 CarPlay 协商记录\n${DisplayDiagnosticSnapshot.report(activity)}")
            appendLine("\n测试日志\n${lines.joinToString("\n")}")
        }
        exportWorker.execute {
            val result = runCatching {
                val name = "color-test-${System.currentTimeMillis()}"
                val saved = DiagnosticExportStore.saveWithoutPicker(activity, "$name.txt", report)
                val directory = File(activity.getExternalFilesDir(null) ?: activity.filesDir, "diagnostic-reports").apply { mkdirs() }
                val zip = File(directory, "$name.zip")
                ZipOutputStream(zip.outputStream()).use { output ->
                    output.putNextEntry(ZipEntry("report.txt")); output.write(report.toByteArray(Charsets.UTF_8)); output.closeEntry()
                    output.putNextEntry(ZipEntry("observations.json")); output.write(observations.toByteArray(Charsets.UTF_8)); output.closeEntry()
                    output.putNextEntry(ZipEntry("fixtures.json")); activity.assets.open("color-test/manifest.json").use { it.copyTo(output) }; output.closeEntry()
                    captures.filter { it.isFile }.forEach { file ->
                        output.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(output) }; output.closeEntry()
                    }
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.diagnostic-reports", zip)
                Triple(saved.savedPath, zip.absolutePath, uri)
            }
            handler.post {
                exporting = false
                update()
                if (disposed) return@post
                result.onSuccess { (textPath, zipPath, uri) ->
                    status.text = activity.getString(R.string.color_test_saved, textPath ?: zipPath, zipPath)
                    val share = Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = ClipData.newRawUri("画面颜色测试报告", uri) }
                    runCatching { activity.startActivity(Intent.createChooser(share, activity.getString(R.string.color_test_export))) }
                }.onFailure { status.text = activity.getString(R.string.color_test_export_failed, it.javaClass.simpleName) }
            }
        }
    }
    private fun dp(value: Int) = FlymeAutoStyle.dp(activity, value)
}
