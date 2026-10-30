package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoAdaptationExportTest {
    @Test fun exportContainsOnlyReferencedCapturesAndReportsMissingFiles() {
        val context = RuntimeEnvironment.getApplication()
        val dir = VideoAdaptationStore.directory(context)
        val capture = File(dir, "observation-test-surface-buffer.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unreferenced = File(dir, "observation-other.png").apply { writeText("unreferenced") }
        val row = JSONObject().put("captures", JSONArray(listOf(capture.name, "observation-missing.png", "../secret.txt")))
        val data = JSONObject().put("samples", JSONArray().put(row))
        val output = VideoAdaptationExport.zip(context, data)
        try {
            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue("report.json" in names && "report.txt" in names)
                assertTrue("captures/${capture.name}" in names)
                assertFalse(names.any { it.contains("secret") || it.contains("other") || it.contains("..") })
                assertArrayEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("captures/${capture.name}")).readBytes())
                val missing = zip.getInputStream(zip.getEntry("missing-capture-files.json")).bufferedReader().readText()
                assertTrue(missing.contains("observation-missing.png"))
            }
        } finally { output.delete(); capture.delete(); unreferenced.delete() }
    }
    @Test fun automaticBundleIncludesManualReferenceAndSuiteCapturesWithIntegrityManifest() {
        val c = RuntimeEnvironment.getApplication()
        val manual = File(VideoAdaptationStore.directory(c), "observation-manual-reference.png").apply { writeBytes(byteArrayOf(3, 4)) }
        val main = File(ColorProbeEvidence.directory(c), "observation-main-reference.png").apply { writeBytes(byteArrayOf(5, 6)) }
        val value = JSONObject().put("manualAdaptationSamplesReference", JSONArray().put(JSONObject().put("captures", JSONArray().put(manual.name))))
            .put("automaticSuite", JSONObject().put("results", JSONArray().put(JSONObject().put("evidence", JSONObject().put("captures", JSONArray().put(main.name))))))
        val file = VideoAdaptationExport.zip(c, value)
        try {
            ZipFile(file).use { zip ->
                assertNotNull(zip.getEntry("captures/${manual.name}"))
                assertNotNull(zip.getEntry("separate-color-reference/${main.name}"))
                val manifest = JSONArray(zip.getInputStream(zip.getEntry("bundle-manifest.json")).bufferedReader().readText())
                assertTrue(manifest.length() >= 4)
            }
        } finally { file.delete(); manual.delete(); main.delete() }
    }
    @Test fun capturesPreserveNativePixelsWithinTheBoundAndMarkLargeImagesForScaling() {
        assertEquals(1920 to 1080, ColorProbeImageCapture.captureSize(1920, 1080, true))
        assertEquals(3840 to 1080, ColorProbeImageCapture.captureSize(7680, 2160, true))
        assertEquals(640 to 360, ColorProbeImageCapture.captureSize(1920, 1080, false))
    }
    @Test fun staticAndDynamicFromDifferentSessionsCannotClaimACompletePair() {
        val value = JSONObject().put("device", JSONObject()).put("phone", JSONObject().put("model", "iPhone").put("ios", "26"))
            .put("samples", JSONArray().put(JSONObject().put("phase", "static").put("valid", true).put("identity", "a"))
                .put(JSONObject().put("phase", "dynamic").put("valid", true).put("identity", "b")))
        assertTrue(VideoAdaptationStore.gaps(value).contains("静止与动态采样不属于同一会话配置"))
    }
}
