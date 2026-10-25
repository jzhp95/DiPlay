package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object VideoAdaptationExport {
    fun report(value: JSONObject): String = buildString {
        appendLine("DiPlay 车机视频适配采集")
        appendLine("缺项：" + VideoAdaptationStore.gaps(value).joinToString("；").ifEmpty { "静止与动态基本采样已具备；仍需人工分析" })
        appendLine("码率是接收视频负载平均值，不是 Wi-Fi 带宽；静止画面低码率不表示故障。")
        appendLine("显示帧数是提交给输出的帧数，不等于面板实测；接收/显示差不能直接视为丢帧。")
        appendLine("CPU为本应用时间占单核的比例，多核可超过100%；电池温度不等于芯片温度。")
        appendLine("截图不包含车机面板、背光及硬件合成后的全部处理；缩小截图会明确标注。")
        appendLine("输出模式对比沿用画面颜色测试。附件历史不代表本轮已完成，不会自动修改日常设置。")
        appendLine(value.toString(2))
    }
    /** Call with a frozen JSON snapshot on a worker; never zip arbitrary paths from stored data. */
    fun zip(context: Context, value: JSONObject): File {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostic-reports").apply { mkdirs() }
        val file = File(directory, "video-adaptation-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.zip")
        val names = linkedSetOf<String>()
        val rows = value.optJSONArray("samples") ?: JSONArray()
        for (i in 0 until rows.length()) {
            val captures = rows.getJSONObject(i).optJSONArray("captures") ?: continue
            for (j in 0 until captures.length()) {
                val name = captures.optString(j)
                if (name.matches(Regex("observation-[a-zA-Z0-9-]+[.]png"))) names.add(name)
            }
        }
        try {
            ZipOutputStream(file.outputStream()).use { output ->
                fun entry(name: String, bytes: ByteArray) {
                    output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
                }
                entry("report.json", value.toString(2).toByteArray(Charsets.UTF_8))
                entry("report.txt", report(value).toByteArray(Charsets.UTF_8))
                val missingFiles = JSONArray()
                for (name in names) {
                    val capture = File(VideoAdaptationStore.directory(context), name)
                    if (!capture.isFile) { missingFiles.put(name); continue }
                    output.putNextEntry(ZipEntry("captures/$name"))
                    capture.inputStream().use { it.copyTo(output) }; output.closeEntry()
                }
                val colorRows = value.optJSONArray("separateColorTestHistory") ?: JSONArray()
                val colorNames = linkedSetOf<String>()
                for (i in 0 until colorRows.length()) {
                    val row = colorRows.optJSONObject(i) ?: continue
                    row.optJSONObject("evidence")?.let { colorNames.addAll(ColorProbeEvidence.captures(it)) }
                    row.optString("capture").takeIf { it.startsWith("observation-") }?.let(colorNames::add)
                }
                for (name in colorNames.filter { it.matches(Regex("observation-[a-zA-Z0-9-]+[.]png")) }) {
                    val capture = File(ColorProbeEvidence.directory(context), name)
                    if (!capture.isFile) { missingFiles.put("separate-color-reference/$name"); continue }
                    output.putNextEntry(ZipEntry("separate-color-reference/$name"))
                    capture.inputStream().use { it.copyTo(output) }; output.closeEntry()
                }
                entry("missing-capture-files.json", missingFiles.toString(2).toByteArray(Charsets.UTF_8))
            }
            directory.listFiles()?.filter { it != file && it.name.startsWith("video-adaptation-") && it.extension == "zip" }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }
}
