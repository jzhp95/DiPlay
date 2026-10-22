package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1400dp-h900dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHeaderAlignmentTest {
    @Test fun expandedNavigationStartsAtTheBackButtonsPageEdge() = verifyAlignment("full", expanded = true)

    @Test
    @Config(qualifiers = "ar-ldrtl-w1400dp-h900dp-land-mdpi")
    fun expandedRtlNavigationStartsAtTheBackButtonsPageEdge() = verifyAlignment("full-rtl", expanded = true, rtl = true)

    @Test
    @Config(qualifiers = "zh-rCN-w400dp-h800dp-port-xhdpi")
    fun compactContentKeepsTheBackButtonsPageEdge() = verifyAlignment("compact", expanded = false)

    @Test
    @Config(qualifiers = "ar-ldrtl-w400dp-h800dp-port-xhdpi")
    fun compactRtlContentKeepsTheBackButtonsPageEdge() = verifyAlignment("compact-rtl", expanded = false, rtl = true)

    private fun verifyAlignment(name: String, expanded: Boolean, rtl: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        context.applicationInfo.flags = context.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        RuntimeEnvironment.setFontScale(1.5f)
        InterfaceSize.save(context, 100)
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = owner.get()
        try {
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.CONNECTION)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            // Product copy remains Chinese; mirror actual view geometry under the Arabic profile.
            descendants(root).forEach { it.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
            val density = activity.resources.displayMetrics.density
            val width = ((if (expanded) 1400 else 400) * density).toInt()
            val height = ((if (expanded) 900 else 800) * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val back = descendants(root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.back) }
            val target = if (expanded) {
                val railScroll = ReflectionHelpers.getField<ScrollView>(activity, "settingsRailScroll")
                descendants(railScroll).single {
                    it.contentDescription == activity.getString(R.string.settings_open_category,
                        activity.getString(R.string.settings_overview))
                }
            } else {
                val heading = descendants(root).filterIsInstance<TextView>().first {
                    it !is Button && it.text == activity.getString(R.string.connection_setup)
                }
                heading.parent.parent as LinearLayout
            }
            fun bounds(view: View) = Rect().also { view.getDrawingRect(it); root.offsetDescendantRectToMyCoords(view, it) }
            val backBounds = bounds(back)
            val targetBounds = bounds(target)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(image))
                val output = File("build/reports/settings-header-alignment").apply { mkdirs() }
                File(output, "$name-large-font.png").outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }
            assertTrue("Back action must stay inside the window: $backBounds", backBounds.left >= 0 && backBounds.right <= width)
            assertTrue("Back label must fit at large font scale", back.layout.height <= back.height - back.compoundPaddingTop - back.compoundPaddingBottom)
            assertEquals("Back action and lower navigation/content must share the page start edge ($name)",
                if (rtl) backBounds.right else backBounds.left,
                if (rtl) targetBounds.right else targetBounds.left)
        } finally { owner.pause().stop().destroy() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
