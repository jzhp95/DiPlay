package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compact, full and RTL layouts after removal, all at 1.5x font size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateRemovalScreenshotsTest {
    @Test fun homeAndAboutRenderWithoutUpdateControls() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            for ((name, widthDp, language) in listOf(
                Triple("compact-en", 320, "en-US"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val configuration = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = widthDp
                    screenHeightDp = 700
                    fontScale = 1.5f
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                for (page in listOf("home", "about")) {
                    val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    DiPlayActivity::class.java.getDeclaredMethod(page, LinearLayout::class.java)
                        .apply { isAccessible = true }.invoke(activity, content)
                    val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                    content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    content.layout(0, 0, width, content.measuredHeight)
                    val bitmap = Bitmap.createBitmap(width, content.measuredHeight, Bitmap.Config.ARGB_8888)
                    try {
                        content.draw(Canvas(bitmap))
                        val directory = File("build/reports/no-app-update-ui").apply { mkdirs() }
                        File(directory, "$page-$name.png").outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                }
            }
        } finally { activity.finish() }
    }
}
