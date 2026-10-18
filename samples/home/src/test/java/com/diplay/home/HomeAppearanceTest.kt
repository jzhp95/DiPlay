package com.diplay.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
@Config(sdk = [32])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAppearanceTest {
    @Test fun largeFontsKeepHomeActionsInsideCompactAndFullWindows() {
        for ((width, height) in listOf(400 to 800, 800 to 360, 1100 to 700)) for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-${if (width > height) "land" else "port"}-${if (night) "night" else "notnight"}-mdpi")
            RuntimeEnvironment.setFontScale(1.5f)
            val owner = Robolectric.buildActivity(HomeActivity::class.java).setup().visible()
            try {
                val activity = owner.get()
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                descendants(root).filterIsInstance<TextView>().filter { it.isShown }.forEach { text ->
                    val bounds = android.graphics.Rect(0, 0, text.width, text.height)
                    root.offsetDescendantRectToMyCoords(text, bounds)
                    assertTrue("Outside window: ${text.text}: $bounds", bounds.left >= 0 && bounds.right <= width)
                    if (text is Button) assertTrue("Clipped button: ${text.text}", (text.layout?.height ?: 0) <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                }
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(image))
                    val dir = File("build/reports/flyme-home").apply { mkdirs() }
                    File(dir, "home-$width-${if (night) "dark" else "light"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { image.recycle() }
                descendants(root).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.home_edit) }.performClick()
                owner.recreate()
                assertTrue(descendants(owner.get().window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text == owner.get().getString(R.string.home_done) })
            } finally { owner.pause().stop().destroy() }
        }
    }
    @Test fun pendingWidgetRequestSurvivesActivityRecreation() {
        val owner = Robolectric.buildActivity(HomeActivity::class.java).setup()
        try {
            val board = org.robolectric.util.ReflectionHelpers.getField<WidgetBoard>(owner.get(), "board")
            org.robolectric.util.ReflectionHelpers.setField(board, "pendingId", 42)
            owner.recreate()
            val restored = org.robolectric.util.ReflectionHelpers.getField<WidgetBoard>(owner.get(), "board")
            assertEquals(42, org.robolectric.util.ReflectionHelpers.getField<Int?>(restored, "pendingId"))
            restored.onActivityResult(11, android.app.Activity.RESULT_CANCELED)
            assertNull(org.robolectric.util.ReflectionHelpers.getField<Int?>(restored, "pendingId"))
        } finally { owner.pause().stop().destroy() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
