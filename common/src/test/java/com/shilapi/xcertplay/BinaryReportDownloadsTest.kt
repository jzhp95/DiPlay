package com.shilapi.xcertplay

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FileProviderPathTestShadow::class, CompletedDownloadTestShadow::class])
class BinaryReportDownloadsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var source: File
    @Before fun setup() {
        CompletedDownloadTestShadow.fail = false; CompletedDownloadTestShadow.path = null
        shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().also { it.attachInfo(context, info) })
        source = File(context.getExternalFilesDir(null), "diagnostic-reports/test-${java.util.UUID.randomUUID()}.zip")
        source.parentFile!!.mkdirs(); source.writeBytes(byteArrayOf(80, 75, 3, 4, 1, 2))
    }
    @Test fun androidNinePublishesTheWholeArchiveWithoutOverwritingExistingPublicReports() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val dir = DiagnosticExportStore.publicDownloadsDirectory().apply { mkdirs() }
        val existing = File(dir, source.name).apply { writeText("old report") }
        val saved = DiagnosticExportStore.saveFileWithoutPicker(context, source, "application/zip")
        val result = File(saved.savedPath!!)
        try {
            assertFalse(saved.savedInApp)
            assertEquals(dir.canonicalFile, result.parentFile.canonicalFile)
            assertArrayEquals(source.readBytes(), result.readBytes())
            assertEquals("old report", existing.readText())
            assertTrue(source.isFile)
        } finally { result.delete(); existing.delete(); source.delete() }
    }
    @Test fun api28RegistersTheActualWrittenArchiveInTheSystemDownloadList() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val saved = DiagnosticExportStore.saveFileWithoutPicker(context, source, "application/zip")
        try {
            assertEquals(77L, saved.downloadId)
            assertEquals(saved.savedPath, CompletedDownloadTestShadow.path)
            assertEquals(source.length(), File(saved.savedPath!!).length())
        } finally { File(saved.savedPath!!).delete() }
    }
    @Test fun downloadListFailureDoesNotDeleteAnAlreadyWrittenPublicArchive() {
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        CompletedDownloadTestShadow.fail = true
        val saved = DiagnosticExportStore.saveFileWithoutPicker(context, source, "application/zip")
        try {
            assertFalse(saved.savedInApp)
            assertTrue(File(saved.savedPath!!).isFile)
            assertNotNull(saved.downloadIndexFailure)
        } finally { File(saved.savedPath!!).delete() }
    }
    @Test fun deniedPublicStorageIsExplicitAndKeepsTheShareableSource() {
        val saved = DiagnosticExportStore.saveFileWithoutPicker(context, source, "application/zip")
        assertTrue(saved.savedInApp)
        assertEquals(source.absolutePath, saved.savedPath)
        assertEquals("content", saved.uri.scheme)
        assertTrue(source.isFile)
        source.delete()
    }
    @Test @Config(sdk = [29])
    fun scopedDownloadsPublishesBinaryDataOnlyAfterClosingTheStream() {
        val provider = BinaryDownloads(File(context.cacheDir, "public.zip"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val saved = DiagnosticExportStore.saveFileWithoutPicker(context, source, "application/zip")
        assertFalse(saved.savedInApp)
        assertEquals("Download/Apple CarPlay/${source.name}", saved.savedPath)
        assertArrayEquals(source.readBytes(), provider.file.readBytes())
        assertEquals("application/zip", provider.inserted!!.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals(0, provider.published!!.getAsInteger(MediaStore.Downloads.IS_PENDING))
        source.delete(); provider.file.delete()
    }
    @Test @Config(sdk = [29])
    fun failedPublishDeletesTheNewMediaEntryAndPreservesThePrivateArchive() {
        val provider = BinaryDownloads(File(context.cacheDir, "failed-public.zip")).apply { failPublish = true }
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val saved = DiagnosticExportStore.saveFileWithoutPicker(context, source, "application/zip")
        assertTrue(saved.savedInApp)
        assertTrue(provider.deleted); assertTrue(source.isFile)
        source.delete()
    }
    @Test @Config(sdk = [29])
    fun checksumMismatchNeverPublishesTheMediaEntry() {
        val provider = BinaryDownloads(File(context.cacheDir, "corrupt-public.zip")).apply { corruptRead = true }
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val saved = DiagnosticExportStore.saveFileWithoutPicker(context, source, "application/zip", indexDownload = false)
        assertTrue(saved.savedInApp); assertTrue(provider.deleted); assertNull(provider.published)
        assertTrue(source.isFile); assertTrue(saved.publicFailure!!.contains("verification"))
    }
    @Test @Config(sdk = [29])
    fun duplicateNamesUseTheActualProviderFileName() {
        val provider=BinaryDownloads(File(context.cacheDir,"renamed.zip")).apply{actualName="report (1).zip"}
        provider.attachInfo(context,ProviderInfo().apply{authority="media"})
        ShadowContentResolver.registerProviderInternal("media",provider)
        val saved=DiagnosticExportStore.saveFileWithoutPicker(context,source,"application/zip",indexDownload=false)
        assertFalse(saved.savedInApp);assertEquals("Download/Apple CarPlay/report (1).zip",saved.savedPath)
        assertArrayEquals(source.readBytes(),provider.file.readBytes());assertFalse(provider.deleted)
    }
    @Test @Config(sdk = [29])
    fun missingProviderMetadataDoesNotDeleteAVerifiedFileOrInventItsName() {
        val provider=BinaryDownloads(File(context.cacheDir,"metadata-missing.zip")).apply{missingMetadata=true}
        provider.attachInfo(context,ProviderInfo().apply{authority="media"})
        ShadowContentResolver.registerProviderInternal("media",provider)
        val saved=DiagnosticExportStore.saveFileWithoutPicker(context,source,"application/zip",indexDownload=false)
        assertFalse(saved.savedInApp);assertFalse(provider.deleted)
        assertTrue(saved.savedPath!!.contains("未提供文件名"));assertTrue(provider.file.isFile)
    }
    private class BinaryDownloads(val file: File) : ContentProvider() {
        var inserted: ContentValues? = null
        var published: ContentValues? = null
        var failPublish = false
        var deleted = false
        var corruptRead = false
        var actualName: String? = null
        var missingMetadata = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values); return Uri.parse("content://media/external/downloads/100")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertTrue(mode == "w" || mode == "r")
            if(mode == "r") {
                if(corruptRead) file.writeBytes(byteArrayOf(0,1,2))
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            published = ContentValues(values); return if (failPublish) 0 else 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { deleted = true; file.delete(); return 1 }
        override fun getType(uri: Uri) = "application/zip"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sortOrder: String?): Cursor? {
            if(missingMetadata)return null
            return android.database.MatrixCursor(arrayOf(MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.RELATIVE_PATH)).apply {
                addRow(arrayOf(actualName ?: inserted!!.getAsString(MediaStore.Downloads.DISPLAY_NAME),"Download/Apple CarPlay/"))
            }
        }
    }
}


@org.robolectric.annotation.Implements(android.app.DownloadManager::class)
class CompletedDownloadTestShadow {
    companion object { var fail = false; var path: String? = null }
    @org.robolectric.annotation.Implementation
    protected fun addCompletedDownload(title: String, description: String, scan: Boolean, mime: String,
        filePath: String, length: Long, notify: Boolean): Long {
        if (fail) throw IllegalStateException("downloads provider unavailable")
        check(mime == "application/zip" && java.io.File(filePath).length() == length)
        path = filePath
        return 77L
    }
}
