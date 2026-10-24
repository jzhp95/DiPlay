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
    private val prefs = activity.getSharedPreferences("color_test_results", Activity.MODE_PRIVATE)
    private val player = ColorTestPlayer(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val exportWorker = Executors.newSingleThreadExecutor()
    private val lines = prefs.getString("log", "")!!.lines().filter { it.isNotBlank() }.toMutableList()
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
                    item.getBoolean("savedPicture"), if (item.isNull("capture")) null else item.getString("capture"), item.getLong("time")))
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
        start = action(R.string.color_test_start, true) {
            if (active) stop() else { active = true; playCurrent() }
            update()
        }
        action(R.string.color_test_choose) {
            activity.appDialogBuilder().setTitle(R.string.color_test_choose)
                .setItems(ColorTestRun.cases.mapIndexed { i, case -> "${i + 1}. ${case.title}" }.toTypedArray()) { _, i ->
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

    fun setVisible(value: Boolean) { visible = value; if (!value) stop() }
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
        run.rendered = false; capturing = false
        player.stop()
        wrapper?.let { old -> player.stop { old.release() } }; wrapper = null
        preview.removeAllViews(); texture = null; surfaceView = null
        status.setText(R.string.color_test_waiting)
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
            if (run.record(verdict, range, standard, savedPicture, captureName)) {
                log("OBSERVATION ${run.index + 1}: $verdict; range=$range standard=$standard savedPicture=$savedPicture")
                persist()
                val retained = run.history.mapNotNull { it.capture }.toSet()
                captureDirectory().listFiles()?.filter { it.name.startsWith("observation-") && it.name !in retained }?.forEach { it.delete() }
                if (run.index < ColorTestRun.cases.lastIndex) {
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
        observation.isEnabled = !capturing && !exporting
        rangeButton.text = activity.getString(R.string.color_test_range_value, when(range) { 1 -> "全范围"; 2 -> "有限范围"; else -> "自动" })
        matrixButton.text = activity.getString(R.string.color_test_matrix_value, when(standard) { 1 -> "BT.709"; 2 -> "BT.601 PAL"; 4 -> "BT.601 NTSC"; else -> "自动" })
        pictureButton.text = activity.getString(R.string.color_test_picture_value, if (savedPicture) "已保存参数" else "原始画面")
        pictureButton.isEnabled = !run.current.surface
        if (::details.isInitialized) details.text = run.report() + "\n\n" + lines.takeLast(12).joinToString("\n")
    }
    private fun log(message: String) {
        lines.add("${System.currentTimeMillis()} $message")
        while (lines.size > 240) lines.removeAt(0)
        persist()
    }
    private fun historyJson(): JSONArray = JSONArray().apply {
        run.history.forEach { observation -> put(JSONObject().apply {
            put("index", observation.index); put("case", ColorTestRun.cases[observation.index].title)
            put("verdict", observation.verdict); put("range", observation.range ?: JSONObject.NULL)
            put("standard", observation.standard ?: JSONObject.NULL); put("savedPicture", observation.savedPicture)
            put("capture", observation.capture ?: JSONObject.NULL); put("time", observation.time)
        }) }
    }
    private fun persist() {
        val editor = prefs.edit().putString("history", historyJson().toString()).putInt("index", run.index).putString("log", lines.joinToString("\n"))
        run.results.forEach { (i, result) -> editor.putString("result_$i", result) }; editor.apply()
    }
    private fun captureDirectory() = File(activity.filesDir, "color-test-captures").apply { mkdirs() }
    private fun export() {
        if (exporting || disposed || capturing) return
        exporting = true
        update()
        val observations = historyJson().toString(2)
        val captures = run.history.mapNotNull { it.capture }.distinct().map { File(captureDirectory(), it) }
        val report = buildString {
            appendLine("DiPlay 画面颜色测试；主观观察并非自动校色结果")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}")
            appendLine("app=${activity.packageName}; hardwareAccelerated=${activity.window.decorView.isHardwareAccelerated}")
            appendLine("注意：请求颜色参数不保证车机采纳；请结合输出格式日志。截图不包含面板亮度、背光和显示后处理。")
            appendLine(run.report()); appendLine("\n逐次观察（含实际参数与唯一截图）\n$observations"); appendLine("\n日常画面参数（测试不会修改）")
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
