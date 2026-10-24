package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotProfile
import com.shilapi.xcertplay.network.CarHotspotProfiles
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
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
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsControlVisibilityTest {
    @Test fun compactDayControlsAreVisibleWithoutFocus() = verify("compact-day", false, false)
    @Test fun compactNightControlsAreVisibleWithoutFocus() = verify("compact-night", false, true)
    @Test fun fullDayControlsAreVisibleWithoutFocus() = verify("full-day", true, false)
    @Test fun fullNightControlsAreVisibleWithoutFocus() = verify("full-night", true, true)
    @Test fun emptyFieldsStillHaveNamedEditingEntrances() = verify("compact-day-empty", false, false, empty = true)

    private fun verify(name: String, expanded: Boolean, night: Boolean, empty: Boolean = false) {
        val width = if (expanded) 1400 else 400
        val height = if (expanded) 900 else 1000
        RuntimeEnvironment.setQualifiers("zh-rCN-w${width}dp-h${height}dp-${if (expanded) "land" else "port"}-${if (night) "night" else "notnight"}-mdpi")
        RuntimeEnvironment.setFontScale(1.5f)
        val context = RuntimeEnvironment.getApplication()
        InterfaceSize.save(context, 100)
        CarHotspotProfiles.saveSaved(context, CarHotspotProfile("MyCar-WiFi", "test-password", ManualHotspotSecurity.WPA2))
        val owner = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        val activity = owner.get()
        try {
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.CONNECTION)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            measure(root, width, height)
            val titles = listOf(R.string.hotspot_name, R.string.hotspot_password, R.string.settings_hotspot_security,
                R.string.settings_hotspot_band, R.string.settings_hotspot_channel).map(activity::getString)
            val rows = editorViews(root).filterIsInstance<Button>().filter {
                it.text.toString().substringBefore(" · ") in titles
            }.toList()
            assertEquals("Two editors and three choices must remain visible", 5, rows.size)
            for (row in rows) {
                assertTrue("A full row is a readable touch target", row.height >= FlymeAutoStyle.dp(activity, FlymeAutoStyle.COMPACT_SETTING_ROW_HEIGHT_DP))
                assertNotNull(row.background)
                assertTrue(row.layout.height <= row.height - row.compoundPaddingTop - row.compoundPaddingBottom)
                assertTrue("Visible value must occupy a separate line", row.transformationMethod.getTransformation(row.text, row).contains('\n'))
            }
            val dialog = openHotspotEditor(activity, root, R.string.hotspot_password)
            try {
                val input = editorViews(dialog.window!!.decorView).filterIsInstance<EditText>().single()
                if (empty) input.setText("")
                assertNotNull("Password must start hidden", input.transformationMethod)
                assertTrue("Input needs a visible filled container", (input.parent as View).background != null)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val decor = dialog.window!!.decorView
                // Measure the dialog after its show callbacks, before native drawing.
                measure(decor, minOf(width - 32, 760), height)
                assertTrue("Clear and eye actions must leave room to type", input.width >= 100)
                editorViews(decor).filterIsInstance<ScrollView>().forEach { it.scrollIndicators = 0 }
                screenshot(decor, decor.width, decor.height, "$name-editor")
            } finally { dialog.dismiss() }
            val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
            val rect = Rect()
            rows.first().getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(rows.first(), rect)
            scroll.scrollTo(0, (rect.top - 160).coerceAtLeast(0))
            screenshot(root, width, height, name)
            val save = editorViews(root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.settings_hotspot_save) }
            save.getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(save, rect)
            scroll.scrollTo(0, (rect.bottom - scroll.height + 160).coerceAtLeast(0))
            screenshot(root, width, height, "$name-actions")
        } finally { owner.pause().stop().destroy() }
    }
    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun screenshot(view: View, width: Int, height: Int, name: String) {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            val folder = File("build/reports/settings-control-visibility").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
