package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WheelTestLayoutTest {
    @Test fun allControlsWorkAndRemainReadableOnCarAndCompactScreens() {
        val base = RuntimeEnvironment.getApplication()
        for (width in listOf(900, 400)) for (scale in listOf(1f, 1.3f)) {
            val height = if (width == 900) 400 else 700
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                screenWidthDp = width; screenHeightDp = height; fontScale = scale
            })
            val actions = mutableListOf<WheelTestLayout.Action>()
            var debug = false
            val ui = WheelTestLayout(context, DiPlayPalette.DARK).build(false, { debug = it }, { actions += it })
            ui.event.text = context.getString(R.string.wheel_test_event, context.getString(R.string.wheel_test_next_label),
                context.getString(R.string.wheel_test_real), 3)
            ui.status.text = context.getString(R.string.wheel_test_status, "已开启", "已获得", "已获得")
            ui.counts.text = context.getString(R.string.wheel_test_count, 3, 1, "Flyme EAS")
            ui.logs.text = "Android focus granted\nFlyme EAS media=4 accepted=true"
            val density = context.resources.displayMetrics.density
            val w = (width * density).toInt(); val h = (height * density).toInt()
            ui.root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            ui.root.layout(0, 0, w, h)
            descendants(ui.root).filterIsInstance<Button>().filterNot { it is Switch }.forEach { it.performClick() }
            assertEquals(WheelTestLayout.Action.entries.toSet(), actions.toSet())
            descendants(ui.root).filterIsInstance<Switch>().single().performClick()
            assertTrue(debug)
            descendants(ui.root).filterIsInstance<TextView>().forEach { text ->
                val bounds = android.graphics.Rect(0, 0, text.width, text.height)
                ui.root.offsetDescendantRectToMyCoords(text, bounds)
                assertTrue("Text outside width: ${text.text}", bounds.left >= 0 && bounds.right <= w)
                text.layout?.let { layout ->
                    assertTrue("Clipped text: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                }
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                ui.root.draw(Canvas(bitmap))
                val folder = File("build/reports/wheel-test-ui").apply { mkdirs() }
                File(folder, "$width-$scale.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
