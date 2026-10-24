package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorTestPageTest {
    @Test fun fullAndCompactColorPagesKeepControlsReadableAtLargeFontScale() {
        for (width in listOf(900, 400)) for (rtl in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("${if (rtl) "ldrtl" else "ldltr"}-w${width}dp-h800dp-mdpi")
            val app = RuntimeEnvironment.getApplication()
            val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale = 1.5f }
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(config, app.resources.displayMetrics)
            val controller = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
            val activity = controller.get()
            try {
                val buttons = all(activity.window.decorView).filterIsInstance<Button>().toList()
                if (width >= 600) buttons.single { it.text == "画面颜色" }.performClick()
                else {
                    buttons.single { it.text == "方控测试" && it.visibility == View.VISIBLE }.performClick()
                    ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 3, 3L)
                }
                val page = ReflectionHelpers.getField<ColorTestPage>(activity, "colorPage")
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 800)
                assertTrue(all(page.root).filterIsInstance<Button>().any { it.text == "开始／继续测试" })
                assertFalse(page.run.rendered)
                assertTrue(page.run.results.isEmpty())
                val settings = CarPlayPicture.preferences(activity).all.toMap()
                for (position in listOf(0, 900, 1800)) {
                    page.root.scrollTo(0, position)
                    val bitmap = Bitmap.createBitmap(width, 800, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    val directory = File("build/reports/color-test-ui").apply { mkdirs() }
                    File(directory, "$width-${if (rtl) "rtl" else "ltr"}-$position.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                all(page.root).filterIsInstance<TextView>().forEach { text ->
                    text.layout?.let { layout ->
                        assertTrue("Clipped text height: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                        for (line in 0 until layout.lineCount) {
                            assertEquals("Ellipsized: ${text.text}", 0, layout.getEllipsisCount(line))
                            assertTrue("Clipped text width: ${text.text}", layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1)
                        }
                    }
                }
                page.setVisible(false)
                assertFalse(page.run.rendered)
                assertEquals(settings, CarPlayPicture.preferences(activity).all)
            } finally { controller.pause().stop().destroy() }
        }
    }
    @Test fun fixturesCanBeReadOfflineAndContainBothCodecConfigurationTypes() {
        val assets = RuntimeEnvironment.getApplication().assets
        val fixture = org.json.JSONObject(assets.open("color-test/manifest.json").bufferedReader().use { it.readText() })
        val patches = fixture.getJSONArray("patch_rgb")
        assertEquals(24, patches.length())
        for (i in 0 until patches.length()) {
            val rgb = patches.getJSONArray(i)
            assertEquals("Native reference must match encoded patch $i", android.graphics.Color.rgb(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2)), ColorReferenceView.colors[i])
        }
        for (name in listOf("h264_full", "h264_limited", "hevc_full", "hevc_limited")) {
            assets.openFd("color-test/$name.mp4").use { assertTrue(it.length > 500) }
            val csd = assets.open("color-test/$name.csd").use { it.readBytes() }
            assertEquals(1, csd[0].toInt())
            if (name.startsWith("h264")) {
                val (sps, pps) = com.shilapi.xcertplay.media.MediaCodecSupport.avcParameterSets(csd)
                assertTrue(sps.isNotEmpty()); assertTrue(pps.isNotEmpty())
            } else assertTrue(com.shilapi.xcertplay.media.MediaCodecSupport.hevcCodecSpecificData(csd).isNotEmpty())
        }
    }
    @Test fun aDisconnectedCaptureKeepsTheObservationAsIncompleteInsteadOfDroppingIt() {
        val controller = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        val activity = controller.get()
        val page = ReflectionHelpers.getField<ColorTestPage>(activity, "colorPage")
        val prefs = activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, android.content.Context.MODE_PRIVATE)
        try {
            val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "current" }
            val result = org.json.JSONObject().apply {
                put("token", "capture-context-changed"); put("index", index); put("verdict", "正常"); put("continue", true); put("teardown_complete", true)
                put("evidence", org.json.JSONObject().apply { put("rendered", false); put("missing", org.json.JSONArray().put("first_frame")) })
            }
            prefs.edit().putString("pending_token", "capture-context-changed").putString("pending_result", result.toString()).commit()
            page.setVisible(true)
            assertEquals(index, page.run.index)
            assertEquals("无法显示", page.run.history.last().verdict)
            val saved = org.json.JSONObject(page.run.history.last().evidence)
            assertEquals("正常", saved.getString("observer_verdict"))
            assertEquals("stream_changed_during_capture", saved.getString("observation_failed"))
            assertNull(prefs.getString("pending_token", null))
        } finally { controller.pause().stop().destroy(); prefs.edit().clear().commit() }
    }
    @Test fun aSavedObservationCannotAdvanceBeforeItsConnectionFinishesClosing() {
        val controller = Robolectric.buildActivity(WheelTestActivity::class.java).setup()
        val activity = controller.get()
        val page = ReflectionHelpers.getField<ColorTestPage>(activity, "colorPage")
        val prefs = activity.getSharedPreferences(ColorMainProbeRegistry.PREFS, android.content.Context.MODE_PRIVATE)
        val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "current" }
        val request = ColorMainProbeRegistry.begin(index, com.shilapi.xcertplay.media.VideoColorOverride())
        ColorMainProbeRegistry.finish(request.token)
        try {
            val result = org.json.JSONObject().apply {
                put("token", request.token); put("index", index); put("verdict", "无法显示"); put("continue", false)
                put("teardown_complete", false)
                put("evidence", org.json.JSONObject().apply { put("rendered", false); put("missing", org.json.JSONArray().put("first_frame")) })
            }
            prefs.edit().putString("pending_token", request.token).putString("pending_result", result.toString()).commit()
            page.setVisible(true)
            assertTrue(page.run.history.isEmpty())
            assertEquals(request.token, prefs.getString("pending_token", null))
            ColorMainProbeRegistry.teardownFinished(request.token)
            result.put("teardown_complete", true)
            prefs.edit().putString("pending_result", result.toString()).commit()
            page.setVisible(true)
            assertEquals(1, page.run.history.size)
            assertEquals(index, page.run.index)
            assertNull(prefs.getString("pending_token", null))
        } finally {
            controller.pause().stop().destroy()
            ColorMainProbeRegistry.teardownFinished(request.token)
            prefs.edit().clear().commit()
        }
    }
    private fun all(view: View): Sequence<View> = sequence {
        if (view.visibility != View.VISIBLE) return@sequence
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
}
