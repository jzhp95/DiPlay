package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayHomeScreenshotsTest {
    @Test fun homeRemainsReadableAtLargeFontInCompactFullAndRtl() {
        val context = RuntimeEnvironment.getApplication()
        for ((name, widthDp, tag) in listOf(
            Triple("compact-zh", 320, "zh-CN"),
            Triple("full-zh", 1100, "zh-CN"),
            Triple("compact-ar-rtl", 320, "ar-SA"),
            Triple("full-uk", 1100, "uk-UA"),
        )) for (fontScale in listOf(1f, 1.2f, 1.5f)) for (night in listOf(false, true)) {
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(context.resources.configuration).apply {
                screenWidthDp = widthDp; screenHeightDp = 700; this.fontScale = fontScale
                setLocale(locale); setLayoutDirection(locale)
            }
            val localized = context.createConfigurationContext(config)
            val palette = DiPlayPalette.of(night)
            val layout = CarPlayHomeLayout(localized, palette,
                makeButton = { title, _, click -> Button(localized).apply {
                    text = title; isAllCaps = false; setTextColor(palette.primaryText)
                    setOnClickListener { click() }
                } },
                makeLabel = { title, size, color, bold -> TextView(localized).apply {
                    text = title; textSize = size.toFloat(); setTextColor(color)
                    typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", 0)
                    gravity = Gravity.CENTER_VERTICAL
                } })
            val root = LinearLayout(localized).apply {
                orientation = LinearLayout.VERTICAL
                background = layout.background()
                val padding = (12 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                addView(layout.header(View(localized), {}, {}, {}, {}).apply {
                    disconnect.visibility = View.VISIBLE
                }.root)
                addView(layout.cards({}, {}, "Bluetooth · Wi-Fi", "1.4.0").root)
            }
            val width = (widthDp * localized.resources.displayMetrics.density).toInt()
            val viewportHeight = (700 * localized.resources.displayMetrics.density).toInt()
            val scroll = android.widget.ScrollView(localized).apply {
                isFillViewport = true
                addView(root)
            }
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, width, viewportHeight)
            descendants(root).filterIsInstance<TextView>().forEach { text ->
                val bounds = android.graphics.Rect(0, 0, text.width, text.height)
                root.offsetDescendantRectToMyCoords(text, bounds)
                assertTrue("Text outside window: ${text.text}", bounds.left >= 0 && bounds.right <= width)
                assertTrue("Empty text control: ${text.text}; case=$name/$fontScale", text.width > 0)
                text.layout?.let { lines ->
                    if (text.text == localized.getString(com.shilapi.xcertplay.host.R.string.carplay)) {
                        assertTrue("Brand truncated: $name/$fontScale", lines.lineCount == 1 &&
                            lines.getLineWidth(0) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight)
                    }
                    assertTrue("Text clipped vertically: ${text.text}; layout=${lines.height}, view=${text.height}, padding=${text.compoundPaddingTop + text.compoundPaddingBottom}, case=$name",
                        lines.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                }
            }
            val bitmap = Bitmap.createBitmap(width, viewportHeight, Bitmap.Config.ARGB_8888)
            try {
                scroll.draw(Canvas(bitmap))
                val output = File("build/reports/carplay-home-ui").apply { mkdirs() }
                File(output, "$name-$fontScale-${if (night) "night" else "day"}.png").outputStream().use {
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
