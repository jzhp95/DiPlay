package com.diplay.maphost

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
class MapHostAppearanceTest {
    @Test fun mapAndActionsFitCompactAndFullWindowsWithLargeFonts() {
        for ((width, height) in listOf(400 to 800, 1100 to 700)) for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-${if (width > height) "land" else "port"}-${if (night) "night" else "notnight"}-mdpi")
            RuntimeEnvironment.setFontScale(1.5f)
            val owner = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
            try {
                val activity = owner.get()
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                fun layout() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
                layout()
                val map = descendants(root).filterIsInstance<SurfaceView>().single()
                assertTrue("Map must fit available width", map.width <= width - 48)
                val smaller = descendants(root).filterIsInstance<Button>().first { it.text == activity.getString(R.string.maphost_smaller) }
                smaller.performClick()
                layout()
                assertTrue(map.width <= width - 48)
                descendants(root).filterIsInstance<Button>().forEach { button ->
                    assertTrue("Button text clipped", (button.layout?.height ?: 0) <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(image))
                    val dir = File("build/reports/flyme-maphost").apply { mkdirs() }
                    File(dir, "maphost-$width-${if (night) "dark" else "light"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { image.recycle() }
            } finally { owner.pause().stop().destroy() }
        }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
