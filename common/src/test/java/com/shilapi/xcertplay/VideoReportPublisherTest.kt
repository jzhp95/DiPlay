package com.shilapi.xcertplay

import android.Manifest
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
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
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FileProviderPathTestShadow::class])
class VideoReportPublisherTest {
    private val c get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        val authority = "${c.packageName}.diagnostic-reports"
        val info = c.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().also { it.attachInfo(c, info) })
        c.getSharedPreferences("video_report_publication_v1", 0).edit().clear().commit()
        VideoAdaptationStore.clear(c)
    }
    private fun waitForReport(): JSONObject {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (VideoReportPublisher.busy() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(VideoReportPublisher.busy())
        return VideoReportPublisher.latest(c)
    }
    @Test fun finalHostResultPublishesWithoutCreatingOrReturningToATestPage() {
        shadowOf(c).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val id="legacy-completed"
        val sequence=(0..15).toList()+(28..35).toList()+listOf(41,42)+(43..50).toList()+listOf(17,26,25,36,40)
        val rows=JSONArray()
        sequence.forEach{rows.put(JSONObject().put("index",it).put("evidence",JSONObject()).put("verdict","fixture"))}
        val suite=JSONObject().put("id",id).put("status","completed").put("sequence",JSONArray(sequence))
            .put("results",rows).put("total",39).put("position",39)
        c.getSharedPreferences("automatic_video_suite_v1",0).edit().putString("state",suite.toString()).commit()
        val evidence = JSONObject().put("auto_run_id", id).put("probe_token", "terminal").put("rendered", false)
        val result = JSONObject().put("token", "terminal").put("index", 40).put("verdict", "无法显示")
            .put("teardown_complete", true).put("teardown_confirmed", true).put("evidence", evidence)
        c.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).edit().putString("pending_result", result.toString()).commit()
        assertTrue(VideoReportPublisher.finishPendingMain(c, "terminal"))
        val saved = waitForReport()
        try {
            assertEquals("completed", AutomaticVideoSuite.read(c).getString("status"))
            assertEquals("public", saved.getString("status"))
            ZipFile(File(saved.getString("path"))).use { zip ->
                val data = JSONObject(zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText())
                assertEquals(39, data.getJSONObject("automaticSuite").getJSONArray("results").length())
                assertEquals(0, data.getJSONArray("unexecutedCases").length())
                assertNotNull(zip.getEntry("bundle-manifest.json"))
            }
        } finally { File(saved.getString("path")).delete(); c.getSharedPreferences(ColorMainProbeRegistry.PREFS, 0).edit().clear().commit() }
    }
    @Test fun ioFailureDoesNotPermanentlyLockTheReportAndRetryKeepsTheOriginalSamples() {
        shadowOf(c).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val blocker = File(c.cacheDir, "blocked-volume").apply { writeText("not a directory") }
        val unavailable = object : android.content.ContextWrapper(c) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getExternalFilesDir(type: String?): File = blocker
        }
        VideoAdaptationStore.append(c, JSONObject().put("phase", "static"))
        VideoReportPublisher.manual(unavailable)
        assertEquals("failed", waitForReport().getString("status"))
        VideoReportPublisher.manual(c)
        val saved = waitForReport()
        assertEquals("public", saved.getString("status"))
        File(saved.getString("path")).delete(); blocker.delete()
    }
    @Test fun publicCompletionMeansANonemptyReadableZipActuallyExistsInDownloads() {
        shadowOf(c).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        VideoAdaptationStore.append(c, JSONObject().put("phase", "static").put("valid", true))
        VideoReportPublisher.manual(c)
        val state = waitForReport()
        assertEquals("public", state.getString("status"))
        val file = File(state.getString("path"))
        try {
            assertEquals(DiagnosticExportStore.publicDownloadsDirectory().canonicalFile, file.parentFile.canonicalFile)
            ZipFile(file).use { zip ->
                val data = JSONObject(zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText())
                assertEquals("static", data.getJSONArray("samples").getJSONObject(0).getString("phase"))
            }
            assertTrue(state.getLong("bytes") > 0)
        } finally { file.delete() }
    }
    @Test fun deniedPermissionIsPrivateAndTheSameDataCanBeRepublishedWithoutRepeatingSampling() {
        shadowOf(c).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        VideoAdaptationStore.append(c, JSONObject().put("phase", "static"))
        VideoReportPublisher.manual(c)
        val first = waitForReport()
        assertEquals("private", first.getString("status"))
        shadowOf(c).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        VideoReportPublisher.manual(c)
        val next = waitForReport()
        assertEquals("public", next.getString("status"))
        File(next.getString("path")).delete()
    }
}
