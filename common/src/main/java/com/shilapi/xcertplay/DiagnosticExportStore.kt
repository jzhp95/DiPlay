package com.shilapi.xcertplay

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    private const val TAG = "DiagnosticExport"

    data class SavedReport(
        val uri: Uri,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
        val publicFailure: String? = null,
        val downloadId: Long? = null, val downloadIndexFailure: String? = null,
    )

    fun saveWithoutPicker(context: Context, fileName: String, report: String): SavedReport {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return SavedReport(saveToDownloads(context.contentResolver, fileName, report))
            } catch (error: Exception) {
                // Preserve the report even when the OEM's public storage provider is absent.
                Log.w(TAG, "MediaStore Downloads unavailable; trying the public folder directly", error)
            }
        }
        if (canWritePublicDownloads(context)) {
            try {
                return saveToPublicDownloads(context, fileName, report)
            } catch (error: Exception) {
                Log.w(TAG, "Public Downloads folder unavailable; saving in app storage", error)
            }
        }
        try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                return saveInDirectory(context, File(externalFiles, "diagnostic-reports"), fileName, report)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent export.
        }
        return saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
    }

    /** Keep the private source as a shareable fallback; public files are never pruned. */
    fun saveFileWithoutPicker(context: Context, source: File, mime: String, indexDownload: Boolean = true): SavedReport {
        require(source.isFile && source.length() > 0) { "Report file is empty" }
        var publicFailure = if (needsStoragePermission(context)) "未获得存储权限" else "公共下载目录不可写"
        val fallback = { FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", source) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var created: Uri? = null
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Apple CarPlay")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Downloads could not create the archive")
                created = uri
                val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("Downloads is not writable")
                stream.use { out -> source.inputStream().use { it.copyTo(out) } }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                checkNotNull(resolver.openInputStream(uri)).use { input ->
                    val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}
                }
                check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == DecoderDiagnosticsRun.sha(source)) { "Downloads readback verification failed" }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
                // MediaStore may rename duplicate names; never advertise the requested name as the actual file.
                val actualPath = runCatching {
                    resolver.query(uri,arrayOf(MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.RELATIVE_PATH),null,null,null)?.use { cursor ->
                        if(!cursor.moveToFirst())null else {
                            val name=cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
                            val relative=cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH))
                            if(name.isNullOrBlank() || relative.isNullOrBlank())null else relative.trimEnd('/')+"/"+name
                        }
                    }
                }.getOrNull()
                return SavedReport(uri, savedPath = actualPath ?: "Download/Apple CarPlay（系统未提供文件名，请使用报告链接或下载列表）",
                    publicFailure = if(actualPath==null)"文件已校验写入，系统未返回实际文件名"else null)
            } catch (error: Exception) {
                publicFailure = error.javaClass.simpleName + ": " + (error.message ?: "").take(160)
                created?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            }
        }
        if (canWritePublicDownloads(context)) {
            var target: File? = null
            try {
                val directory = publicDownloadsDirectory()
                check(directory.isDirectory || directory.mkdirs())
                val destination = File(directory, source.name)
                val published = if (destination.exists()) File.createTempFile("video-report-", ".zip", directory) else destination
                target = published
                val temporary = File(directory, published.name + ".writing")
                try {
                    source.inputStream().use { input -> temporary.outputStream().use { out -> input.copyTo(out); out.fd.sync() } }
                    check(temporary.length() == source.length() && DecoderDiagnosticsRun.sha(temporary) == DecoderDiagnosticsRun.sha(source)) { "Archive copy verification failed" }
                    check(temporary.renameTo(published)) { "Archive publication rename failed" }
                } finally { temporary.delete() }
                runCatching { MediaScannerConnection.scanFile(context.applicationContext, arrayOf(published.absolutePath), arrayOf(mime), null) }
                val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", published) }
                    .getOrElse { runCatching { fallback() }.getOrDefault(Uri.EMPTY) }
                val index = if (indexDownload) registerCompletedDownload(context, published, mime) else (null to null)
                return SavedReport(uri, savedPath = published.absolutePath, downloadId = index.first, downloadIndexFailure = index.second)
            } catch (error: Exception) {
                publicFailure = error.javaClass.simpleName + ": " + (error.message ?: "").take(160)
                target?.delete()
            }
        }
        return SavedReport(runCatching { fallback() }.getOrDefault(Uri.EMPTY), savedInApp = true, savedPath = source.absolutePath, publicFailure = publicFailure)
    }

    @Suppress("DEPRECATION")
    internal fun registerCompletedDownload(context: Context, file: File, mime: String): Pair<Long?, String?> {
        if (Build.VERSION.SDK_INT >= 29) return null to null
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val id = manager.addCompletedDownload(file.name, "DiPlay 解码诊断报告", true, mime, file.absolutePath, file.length(), false)
            if (id >= 0) id to null else null to "系统未接受下载列表登记"
        } catch (error: Exception) { null to (error.javaClass.simpleName + ": " + (error.message ?: "").take(160)) }
    }

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
    ): SavedReport {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            // Retain only the newest eight reports; never prune the export being returned.
            directory.listFiles()?.filter { it != file && it.isFile && it.extension == "txt" }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return SavedReport(uri, savedInApp = savedInApp, savedPath = if (savedInApp) null else file.absolutePath)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /** Android 11+ lets any app create files in Downloads; older versions need the permission. */
    fun canWritePublicDownloads(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.R ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * True only on Android 9, the one version where storage access is what puts the report in
     * the visible Downloads folder. Android 10+ saves there through MediaStore without asking.
     */
    fun needsStoragePermission(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt < Build.VERSION_CODES.Q && !canWritePublicDownloads(context, sdkInt)

    @Suppress("DEPRECATION")
    fun publicDownloadsDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Apple CarPlay")

    internal fun saveToPublicDownloads(context: Context, fileName: String, report: String): SavedReport {
        val directory = publicDownloadsDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Downloads/Apple CarPlay is unavailable")
        val base = fileName.removeSuffix(".txt")
        var file = File(directory, fileName)
        var copy = 1
        while (file.exists()) file = File(directory, "$base-${copy++}.txt")
        try {
            file.writeText(report, Charsets.UTF_8)
            if (file.length() == 0L && report.isNotEmpty()) throw IOException("Downloads/Apple CarPlay did not keep the report")
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        // The report is saved; nothing below may undo that. The user's Downloads folder is never pruned.
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
        } catch (error: IllegalArgumentException) {
            // Some storage layouts resolve Downloads outside the provider's external root.
            // Share a private copy instead; the visible file in Downloads/DiPlay stays.
            Log.w(TAG, "Downloads/Apple CarPlay is not shareable through the provider; sharing a private copy", error)
            privateShareCopy(context, fileName, report)
        }
        return SavedReport(uri, savedPath = file.absolutePath)
    }

    private fun privateShareCopy(context: Context, fileName: String, report: String): Uri {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (external != null) {
            runCatching { return saveInDirectory(context, File(external, "diagnostic-reports"), fileName, report).uri }
        }
        return saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true).uri
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Apple CarPlay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}
