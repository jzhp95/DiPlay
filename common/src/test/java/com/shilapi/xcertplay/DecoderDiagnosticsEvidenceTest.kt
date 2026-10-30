package com.shilapi.xcertplay

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DecoderDiagnosticsEvidenceTest {
    @Test fun highlightModelsDistinguishCorrectFromRepeatedExpansionWithoutInventingPass() {
        val c=RuntimeEnvironment.getApplication()
        val ref=c.assets.open("color-test/h1-reference.json").bufferedReader().use{JSONObject(it.readText())}.getJSONObject("files").getJSONObject("h264_light_full")
        fun image(expand:Boolean):Bitmap {
            val b=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888)
            val patches=ref.getJSONArray("patches")
            for(i in 0 until patches.length()){
                var v=patches.getJSONObject(i).getDouble("referenceMean")
                if(expand)v=((v-16)*255/219).coerceIn(0.0,255.0)
                val gray=v.toInt();val color=android.graphics.Color.rgb(gray,gray,gray)
                for(y in 0 until 320)for(x in i*96 until (i+1)*96)b.setPixel(x,y,color)
            };return b
        }
        val correct=DecoderDiagnosticsMetrics.highlights(image(false),ref)
        val expanded=DecoderDiagnosticsMetrics.highlights(image(true),ref)
        assertEquals("not_supported_by_model_comparison",correct.getString("h1Priority"))
        assertEquals("repeat_limited_expansion_shape_supported",expanded.getString("h1Priority"))
        assertTrue(correct.isNull("colorPass"));assertEquals("not_localized_by_screenshot_alone",expanded.getString("responsibleLayer"))
        assertFalse(DecoderDiagnosticsMetrics.highlights(Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888),ref).getBoolean("available"))
    }
    @Test fun distributionUsesTailAndNotJustAverage() {
        val d=DecoderDiagnosticsMetrics.distribution((1..100).map{it.toDouble()})
        assertEquals(50.0,d.getDouble("p50"),0.0);assertEquals(99.0,d.getDouble("p99"),0.0)
        assertEquals(100.0,d.getDouble("max"),0.0)
    }
    @Test fun manifestRejectsDamagedEntry() {
        val c=RuntimeEnvironment.getApplication();val file=File(c.cacheDir,"damaged.zip")
        ZipOutputStream(file.outputStream()).use{out->
            out.putNextEntry(ZipEntry("run.json"));out.write("damaged".toByteArray());out.closeEntry()
            out.putNextEntry(ZipEntry("manifest.json"));out.write(JSONArray().put(JSONObject().put("name","run.json").put("bytes",7).put("sha256","wrong")).toString().toByteArray());out.closeEntry()
        }
        assertThrows(IllegalStateException::class.java){DecoderDiagnosticsBundle.verify(file)}
    }
    @Test fun republishingKeepsTheRunsOriginalReferenceEvenAfterAnAppUpdate() {
        val c=RuntimeEnvironment.getApplication();val r=DecoderDiagnosticsRun.create(c,JSONObject())
        val directory=DecoderDiagnosticsRun.directory(c,r.getString("id"))
        DecoderDiagnosticsBundle.freezeReferences(c,directory)
        val oldReference=File(directory,"reference/h1-reference.json")
        val frozen="{\"version\":0,\"originalRunReference\":true}"
        oldReference.writeText(frozen)
        val zip=DecoderDiagnosticsBundle.seal(c,r)
        java.util.zip.ZipFile(zip).use{z->assertEquals(frozen,z.getInputStream(z.getEntry("reference/h1-reference.json")).bufferedReader().use{it.readText()})}
    }
    @Test fun runSurvivesPageLossAndCanSealPartialEvidence() {
        val c=RuntimeEnvironment.getApplication();val r=DecoderDiagnosticsRun.create(c,JSONObject())
        r.put("execution","interrupted").put("reason","process_restarted").put("plan",DecoderDiagnosticsRun.plan())
        DecoderDiagnosticsRun.save(c,r)
        assertEquals(r.getString("id"),DecoderDiagnosticsRun.latest(c).getString("id"))
        val zip=DecoderDiagnosticsBundle.seal(c,r);DecoderDiagnosticsBundle.verify(zip)
        java.util.zip.ZipFile(zip).use{z->
            assertNotNull(z.getEntry("summary.txt"));assertNotNull(z.getEntry("unexecuted.json"))
            assertNotNull(z.getEntry("reference/manifest.json"));assertNotNull(z.getEntry("reference/h1-reference.json"))
            assertEquals(68,JSONArray(z.getInputStream(z.getEntry("unexecuted.json")).bufferedReader().use{it.readText()}).length())
        }
    }
}
