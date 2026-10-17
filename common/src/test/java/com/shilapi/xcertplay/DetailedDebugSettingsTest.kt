package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailedDebugSettingsTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp")
    fun compactDiagnosticsCanEnableMarkAndStopRecording() = checkScreen("compact")

    @Test @Config(qualifiers = "zh-rCN-w1024dp-h600dp")
    fun carDiagnosticsCanEnableMarkAndStopRecording() = checkScreen("car")

    private fun checkScreen(name: String) {
        val context = RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setFontScale(1.3f)
        DetailedDebugLog.setEnabled(context, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).first {
                it.contentDescription == activity.getString(R.string.settings_open_category, activity.getString(R.string.diagnostics))
            }.performClick()
            val toggle = descendants(activity.window.decorView).filterIsInstance<Switch>().single {
                it.contentDescription == activity.getString(R.string.settings_detailed_debug)
            }
            assertFalse(toggle.isChecked)
            toggle.performClick()
            assertTrue(DetailedDebugLog.enabled(context))
            descendants(activity.window.decorView).filterIsInstance<TextView>().first {
                it.text == activity.getString(R.string.settings_debug_marker)
            }.performClick()
            assertTrue(DetailedDebugLog.report(context).contains("USER_MARK"))
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.save_diagnostic_report)
            })

            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = activity.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/reports/detailed-debug-ui").apply { mkdirs() }
                File(output, "$name-large-font.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            toggle.performClick()
            assertFalse(DetailedDebugLog.enabled(context))
        } finally {
            DetailedDebugLog.setEnabled(context, false)
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
