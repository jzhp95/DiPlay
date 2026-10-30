package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object DecoderDiagnosticsBundle {
    fun seal(c: Context, run: JSONObject): File {
        val directory = DecoderDiagnosticsRun.directory(c, run.getString("id"))
        val results = run.optJSONArray("results") ?: JSONArray()
        val executed = (0 until results.length()).map { results.getJSONObject(it).optString("caseId") }.toSet()
        val plan = run.optJSONArray("plan") ?: JSONArray()
        val missing = JSONArray()
        for (i in 0 until plan.length()) if (plan.getJSONObject(i).getString("caseId") !in executed)
            missing.put(JSONObject().put("caseId", plan.getJSONObject(i).getString("caseId")).put("reason", run.optString("reason", "not_reached")))
        DecoderDiagnosticsRun.atomic(File(directory,"unexecuted.json"), missing.toString(2).toByteArray())
        DecoderDiagnosticsRun.atomic(File(directory,"summary.txt"), buildString {
            appendLine("DiPlay 解码系统诊断 ${run.optString("id")}")
            appendLine("执行=${run.optString("execution")} 测量=${run.optString("measurementValidity")} 证据=${run.optString("evidenceCompleteness")}")
            appendLine("终止原因=${run.optString("reason")}; 原状态恢复=${run.optString("restoration")}")
            appendLine("真实主题=${run.optString("themeActual")}; ${run.optString("themeBasis")}")
            appendLine("本包记录事实；发布路径与哈希见页面及独立 receipt.json。")
            appendLine("H1：重点检查浅色近白灰阶与重复有限范围扩展模型；深色正常不能判颜色通过。")
            for (i in 0 until results.length()) {
                val r=results.getJSONObject(i)
                appendLine("${r.optString("caseId")}: ${r.optString("execution")}; validity=${r.optString("measurementValidity")}; ${r.optString("reason")}")
                val h1=r.optJSONObject("highlights")
                if (h1 != null) appendLine("  H1=${h1.optString("h1Priority")}; correct MAE=${h1.opt("correctModelMeanAbsoluteError")}; expanded MAE=${h1.opt("expandedLimitedModelMeanAbsoluteError")}; evidence=cases/${r.optString("caseId")}.json")
            }
            appendLine("未执行=${missing.length()}; 缺口=${run.optJSONArray("gaps")}")
            appendLine("同源比较需实际路径、输入哈希、尺寸与负载条件一致；回退不算请求路径成功。")
            appendLine("没有校准的阈值不做颜色或面板通过判定；逐帧时序不是物理扫描或完整触摸延迟。")
            appendLine("真实 CarPlay 静态内容不代表滑动测试完成；iPhone 时钟未映射，不计算单向网络延迟。")
        }.toByteArray())
        freezeReferences(c,directory)
        val revision=run.optLong("storageRevision",-1)
        val entries = directory.walkTopDown().filter { file ->
            val path=file.relativeTo(directory).invariantSeparatorsPath
            val selectedStorageRevision=path == "revisions/$revision.json" || path == "sealed/$revision.json"
            file.isFile && !(file.parentFile == directory && file.name in setOf("receipt.json","download-index.json","manifest.json","run.json","sealed-run.json")) &&
                !file.name.endsWith(".writing") &&
                (!(path.startsWith("revisions/") || path.startsWith("sealed/")) || selectedStorageRevision)
        }.toList()
        check(entries.sumOf { it.length() } <= DecoderDiagnosticsRun.BUDGET) { "Evidence exceeds 256 MiB budget; source preserved" }
        val frozenRun=run.toString(2).toByteArray()
        val manifest=JSONArray().put(JSONObject().put("name","run.json").put("bytes",frozenRun.size)
            .put("sha256",MessageDigest.getInstance("SHA-256").digest(frozenRun).joinToString(""){"%02x".format(it.toInt() and 255)}))
        entries.forEach { manifest.put(JSONObject().put("name",it.relativeTo(directory).invariantSeparatorsPath).put("bytes",it.length()).put("sha256",DecoderDiagnosticsRun.sha(it))) }
        val destination=File(c.filesDir,"diagnostic-reports").apply { mkdirs() }
        val file=File(destination,"decoder-diagnostics-${run.getString("id")}.zip")
        val partial=File(destination,file.name+".writing")
        ZipOutputStream(partial.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("run.json"));out.write(frozenRun);out.closeEntry()
            for (f in entries) { out.putNextEntry(ZipEntry(f.relativeTo(directory).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(out) }; out.closeEntry() }
            out.putNextEntry(ZipEntry("manifest.json"));out.write(manifest.toString(2).toByteArray());out.closeEntry()
        }
        verify(partial)
        check(partial.renameTo(file));return file
    }
    fun freezeReferences(c:Context,directory:File) {
        val reference=File(directory,"reference").apply{mkdirs()}
        for(name in listOf("manifest.json","decoder-reference-manifest.json","h1-reference.json")) {
            val target=File(reference,name)
            if(!target.exists())c.assets.open("color-test/$name").use{DecoderDiagnosticsRun.atomic(target,it.readBytes())}
        }
    }
    fun verify(file: File) {
        ZipFile(file).use { zip ->
            val manifest=JSONArray(zip.getInputStream(checkNotNull(zip.getEntry("manifest.json"))).bufferedReader().use { it.readText() })
            val names=mutableSetOf<String>()
            for (i in 0 until manifest.length()) {
                val row=manifest.getJSONObject(i);val name=row.getString("name")
                check(!name.startsWith("/") && name.split('/').none { it==".." } && names.add(name))
                val entry=checkNotNull(zip.getEntry(name));check(entry.size==row.getLong("bytes"))
                val digest=MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input -> val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)} }
                check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==row.getString("sha256")) { "ZIP checksum mismatch: $name" }
            }
            check(zip.size()==names.size+1) { "Unindexed ZIP entries" }
        }
    }
}
