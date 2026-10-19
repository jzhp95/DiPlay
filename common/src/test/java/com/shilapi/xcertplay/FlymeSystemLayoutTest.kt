package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1400dp-h900dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlymeSystemLayoutTest {
    @Test fun categoryUsesDirectGroupsAndUnboxedIndependentNavigation() {
        RuntimeEnvironment.setFontScale(1.5f)
        InterfaceSize.save(RuntimeEnvironment.getApplication(), 100)
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = owner.get()
        try {
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.CONNECTION)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1400, 900)
            val heading = descendants(root).filterIsInstance<TextView>().first {
                it !is Button && it.text.toString() == activity.getString(R.string.connection_setup)
            }
            val group = heading.parent.parent as LinearLayout
            assertNull("Settings categories use open form groups rather than a card around every section", group.background)
            val railScroll = ReflectionHelpers.getField<ScrollView>(activity, "settingsRailScroll")
            val rail = (railScroll.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
            assertNull("Navigation is placed directly on the page background", rail.background)
            assertTrue(rail.getChildAt(0).height >= 72)
            for (button in descendants(root).filterIsInstance<Button>()) {
                if (button.visibility == View.VISIBLE && button.height > 0 && button.layout != null) {
                    assertTrue("Button text clipped: ${button.text}", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
            }
            val image = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val out = File("build/reports/flyme-system").apply { mkdirs() }
            File(out, "connection-full-large-font.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun auxiliaryButtonsTreatLegacyFixedHeightAsAMinimum() {
        val context = RuntimeEnvironment.getApplication()
        val button = DiPlayButton(context).apply {
            text = "较窄窗口中的辅助设置操作，需要换行显示".repeat(2)
            style(DiPlayButtonKind.SECONDARY, DiPlayPalette.DARK, overlay = true)
            layoutParams = LinearLayout.LayoutParams(220, 48)
        }
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, button.layoutParams.height)
        val parent = LinearLayout(context).apply { addView(button) }
        parent.measure(View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        assertTrue(button.height >= button.layout.height + button.compoundPaddingTop + button.compoundPaddingBottom)
    }

    @Test fun picturePanelKeepsLargeControlsReachableInAShortViewport() {
        RuntimeEnvironment.setFontScale(1.5f)
        val context = RuntimeEnvironment.getApplication()
        val panel = CarPlayPicturePanel(context, initialPalette = DiPlayPalette.DARK, close = {})
        panel.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 420, 320)
        assertTrue("The driver must be able to reach actions below the short panel viewport",
            descendants(panel).filterIsInstance<ScrollView>().any { it.canScrollVertically(1) })
        val action = descendants(panel).filterIsInstance<DiPlayButton>().first()
        assertEquals(Color.WHITE, action.currentTextColor)
        val scroll = descendants(panel).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        val actionBounds = android.graphics.Rect()
        action.getDrawingRect(actionBounds)
        scroll.offsetDescendantRectToMyCoords(action, actionBounds)
        assertTrue("Bottom actions are visible after scrolling", actionBounds.bottom <= scroll.scrollY + scroll.height)
        assertTrue("Bottom actions fit inside the viewport", actionBounds.top >= scroll.scrollY)
    }

    @Test fun pageScreenshotMatrixWithLargeFonts() {
        val info = RuntimeEnvironment.getApplication().applicationInfo
        info.flags = info.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val profiles = listOf(
            Triple("compact", "zh-rCN-w400dp-h800dp-port-mdpi", 400 to 800),
            Triple("full", "zh-rCN-w1400dp-h900dp-land-mdpi", 1400 to 900),
            Triple("short", "zh-rCN-w900dp-h400dp-land-mdpi", 900 to 400),
            Triple("arabic-rtl-dense", "ar-ldrtl-w400dp-h800dp-port-xhdpi", 400 to 800),
        )
        for ((name, qualifiers, dimensions) in profiles) for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(qualifiers.replace("-mdpi", "-${if (night) "night" else "notnight"}-mdpi").replace("-xhdpi", "-${if (night) "night" else "notnight"}-xhdpi"))
            RuntimeEnvironment.setFontScale(1.5f)
            InterfaceSize.save(RuntimeEnvironment.getApplication(), 100)
            for (category in listOf<SettingsCategory?>(null, SettingsCategory.CONNECTION, SettingsCategory.AUDIO)) {
                val owner = Robolectric.buildActivity(DiPlayActivity::class.java,
                    Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java)
                        .putExtra("page", if (category == null) "home" else "settings")).setup()
                val activity = owner.get()
                try {
                    if (category != null) {
                        ReflectionHelpers.setField(activity, "settingsCategory", category)
                        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                    }
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    // The product fixes copy to Chinese; force direction to exercise mirrored geometry.
                    if (name.startsWith("arabic")) descendants(root).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_RTL }
                    val density = activity.resources.displayMetrics.density
                    val width = (dimensions.first * density).toInt()
                    val height = (dimensions.second * density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    if (name.startsWith("arabic")) assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
                    for (button in descendants(root).filterIsInstance<Button>()) {
                        if (button.visibility == View.VISIBLE && button.height > 0 && button.layout != null) {
                            assertTrue("$name/$category clips ${button.text}",
                                button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                        }
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        val out = File("build/reports/flyme-system").apply { mkdirs() }
                        File(out, "$name-${if (night) "night" else "day"}-${category?.name?.lowercase() ?: "home"}.png")
                            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { bitmap.recycle() }
                } finally { owner.pause().stop().destroy() }
            }
        }
    }

    private fun descendants(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(descendants(v.getChildAt(i)))
    }
}
