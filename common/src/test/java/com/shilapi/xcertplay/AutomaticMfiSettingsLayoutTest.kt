package com.shilapi.xcertplay

import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.settings.ConnectionSettingsSection
import com.shilapi.xcertplay.settings.SettingsTheme
import java.io.File
import java.util.Locale
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
class AutomaticMfiSettingsLayoutTest {
    @Test fun allAuthenticationChoicesRemainReadableWithLargeFonts() {
        val base = RuntimeEnvironment.getApplication()
        val originalFlags = base.applicationInfo.flags
        val originalTarget = base.applicationInfo.targetSdkVersion
        // Library-test manifests omit the mobile application's RTL support declaration.
        base.applicationInfo.flags = originalFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        base.applicationInfo.targetSdkVersion = 37
        try {
        for (width in listOf(400, 900)) for (dpi in listOf(160, 320))
            for (night in listOf(false, true)) for (rtl in listOf(false, true)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                screenWidthDp = width; screenHeightDp = 700; fontScale = 1.5f; densityDpi = dpi
                setLayoutDirection(if (rtl) Locale.forLanguageTag("ar") else Locale.SIMPLIFIED_CHINESE)
            })
            val palette = DiPlayPalette.of(night)
            val chosen = mutableListOf<MfiTarget>()
            val row = ConnectionSettingsSection.createMfiTargetChoice(context, MfiTarget.AUTO,
                SettingsTheme.card(palette), chosen::add)
            val padding = FlymeAutoStyle.dp(context, FlymeAutoStyle.CARD_PADDING_DP)
            val root = ScrollView(context).apply {
                layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                setBackgroundColor(palette.background)
                setPadding(padding, padding, padding, padding)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; background = FlymeAutoStyle.card(context, palette)
                    setPadding(padding, padding, padding, padding); addView(row.container)
                })
            }
            val density = context.resources.displayMetrics.density
            val w = (width * density).toInt(); val h = (700 * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            assertEquals(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
            val choices = views(root).filterIsInstance<RadioButton>().toList()
            assertEquals(listOf(MfiTarget.AUTO, MfiTarget.LOCAL, MfiTarget.USB_CH341), choices.map { it.tag })
            assertTrue(choices.first().isChecked)
            views(root).filterIsInstance<TextView>().forEach { text ->
                text.layout?.let { layout ->
                    assertTrue("Clipped height: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) {
                        assertEquals(0, layout.getEllipsisCount(line))
                        assertTrue("Clipped width: ${text.text}", layout.getLineMax(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1)
                    }
                }
            }
            choices[1].performClick(); choices[2].performClick(); choices[0].performClick()
            assertEquals(listOf(MfiTarget.LOCAL, MfiTarget.USB_CH341, MfiTarget.AUTO), chosen)
            assertEquals(1, choices.count { it.isChecked })
            choices.forEach { it.jumpDrawablesToCurrentState() }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val folder = File("build/reports/mfi-auto-settings-ui").apply { mkdirs() }
                File(folder, "$width-$dpi-large-${if (night) "dark" else "light"}${if (rtl) "-rtl" else ""}.png")
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        } finally {
            base.applicationInfo.flags = originalFlags
            base.applicationInfo.targetSdkVersion = originalTarget
        }
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
