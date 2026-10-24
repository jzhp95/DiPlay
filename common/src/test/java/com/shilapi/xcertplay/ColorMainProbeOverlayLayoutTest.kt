package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.media.VideoColorOverride
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorMainProbeOverlayLayoutTest {
    @Test fun theActualHostOverlayFitsShortWideAndNarrowWindowsWithLargeFonts() {
        for ((width, height) in listOf(900 to 500, 400 to 800)) for (rtl in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("${if (rtl) "ldrtl" else "ldltr"}-w${width}dp-h${height}dp-mdpi")
            val app = RuntimeEnvironment.getApplication()
            val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale = 1.5f }
            @Suppress("DEPRECATION") app.resources.updateConfiguration(config, app.resources.displayMetrics)
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "texture-limited" }
            val request = ColorMainProbeRegistry.begin(index, VideoColorOverride(2))
            val overlay = ColorMainProbeOverlay(activity, request, { false }, { "连接失败；这段长详情只出现在报告中" },
                { null to null }, { JSONObject() }, { _, _, _ -> })
            try {
                val root = FrameLayout(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(overlay.root, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                }
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                assertTrue("controls must leave part of the production picture visible", overlay.root.height < height)
                all(overlay.root).filterIsInstance<TextView>().forEach { text -> text.layout?.let { layout ->
                    assertTrue("clipped height: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) assertTrue("clipped width: ${text.text}", layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1)
                } }
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                val directory = File("build/reports/color-main-overlay-ui").apply { mkdirs() }
                File(directory, "$width-${if (rtl) "rtl" else "ltr"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            } finally {
                overlay.close(); ColorMainProbeRegistry.finish(request.token); ColorMainProbeRegistry.teardownFinished(request.token)
                controller.pause().stop().destroy()
            }
        }
    }
    @Test fun observationsRequireAStablePeriodWithTheDiagnosticControlsHidden() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val index = ColorTestRun.cases.indexOfFirst { it.mainProfile == "current" }
        val request = ColorMainProbeRegistry.begin(index, VideoColorOverride())
        var generation = 1
        var completed = 0
        val overlay = ColorMainProbeOverlay(activity, request, { true }, { "" }, { null to null }, {
            JSONObject().put("configuration_sha256", "same").put("output_instance", "one")
                .put("output_generation", generation).put("rendered", true).put("missing", org.json.JSONArray())
        }, { _, _, _ -> completed++ })
        activity.setContentView(overlay.root)
        fun button(text: String) = all(overlay.root).filterIsInstance<android.widget.Button>().single { it.text == text }
        fun chooseNormal() {
            button("记录观察并继续").performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(null, 0, 0)
            dialog.dismiss()
        }
        try {
            chooseNormal()
            assertEquals(0, completed)
            assertEquals(View.VISIBLE, overlay.root.visibility)
            button("全屏观察 8 秒").performClick()
            assertEquals(View.INVISIBLE, overlay.root.visibility)
            generation++
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(8))
            chooseNormal()
            assertEquals(0, completed)
            assertEquals(View.VISIBLE, overlay.root.visibility)
            button("全屏观察 8 秒").performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(8))
            chooseNormal()
            assertEquals("a stable unobstructed observation can enter image capture", View.INVISIBLE, overlay.root.visibility)
        } finally {
            overlay.close(); ColorMainProbeRegistry.finish(request.token); ColorMainProbeRegistry.teardownFinished(request.token)
            controller.pause().stop().destroy()
        }
    }
    private fun all(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
}
