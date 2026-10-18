package com.shilapi.xcertplay

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.network.CarHotspotProfile
import com.shilapi.xcertplay.network.CarHotspotProfiles
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class HotspotSettingsPreviewActivity : Activity(), AppAppearanceOwner {
    override var currentAppNight: Boolean = false
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarHotspotSettingsLayoutTest {
    @Test fun inlineCardFitsCompactWideAndRtlWindowsWithLargeText() {
        for (width in listOf(400, 1080)) for (densityDpi in listOf(160, 320)) for (night in listOf(false, true)) for (rtl in listOf(false, true)) {
            val owner = Robolectric.buildActivity(HotspotSettingsPreviewActivity::class.java).setup()
            val activity = owner.get()
            val original = Configuration(activity.resources.configuration)
            val height = if (width == 400) 960 else 720
            activity.resources.updateConfiguration(Configuration(original).apply {
                screenWidthDp = width; screenHeightDp = height; fontScale = 1.5f
                this.densityDpi = densityDpi
                setLayoutDirection(if (rtl) Locale.forLanguageTag("ar") else Locale.SIMPLIFIED_CHINESE)
            }, activity.resources.displayMetrics)
            activity.currentAppNight = night
            val palette = DiPlayPalette.of(night)
            CarHotspotProfiles.saveSaved(activity, CarHotspotProfile("CarPlay Wi-Fi", "test-password", ManualHotspotSecurity.WPA2))
            val ui = CarHotspotSettingsUi(activity, changed = {}, redraw = {})
            try {
                val padding = FlymeAutoStyle.dp(activity, FlymeAutoStyle.CARD_PADDING_DP)
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = FlymeAutoStyle.card(activity, palette)
                    setPadding(padding, padding, padding, padding)
                    layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                ui.build(content)
                val root = ScrollView(activity).apply {
                    setBackgroundColor(palette.background)
                    setPadding(padding, padding, padding, padding)
                    addView(content)
                }
                val density = activity.resources.displayMetrics.density
                val w = (width * density).toInt()
                val h = (height * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                descendants(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.forEach { text ->
                    val bounds = Rect(0, 0, text.width, text.height)
                    root.offsetDescendantRectToMyCoords(text, bounds)
                    assertTrue("Text outside card: " + text.text, bounds.left >= 0 && bounds.right <= w)
                    text.layout?.let {
                        assertTrue("Text clipped vertically: " + text.text,
                            it.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    }
                }
                val folder = File("build/reports/hotspot-settings-ui").apply { mkdirs() }
                for (bottom in listOf(false, true)) {
                    root.scrollTo(0, if (bottom) content.height else 0)
                    val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(image))
                        val name = width.toString() + "-large-" + (if (night) "dark" else "light") +
                            (if (rtl) "-rtl" else "") + (if (densityDpi == 320) "-2x" else "") + (if (bottom) "-bottom" else "") + ".png"
                        File(folder, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { image.recycle() }
                }
            } finally {
                ui.close()
                activity.resources.updateConfiguration(original, activity.resources.displayMetrics)
                owner.pause().stop().destroy()
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
