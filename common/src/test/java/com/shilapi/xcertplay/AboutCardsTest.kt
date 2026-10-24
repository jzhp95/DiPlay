package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutCardsTest {
    @Test fun allThreeAboutCardsKeepTheirBackgroundInDayNightAndBothLayouts() {
        for (width in listOf(400, 1400)) for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers("zh-rCN-w${width}dp-h1000dp-${if (night) "night" else "notnight"}-mdpi")
            RuntimeEnvironment.setFontScale(1.5f)
            val context = RuntimeEnvironment.getApplication()
            val owner = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
            val activity = owner.get()
            try {
                ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ABOUT)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, 1000)
                val titles = listOf(R.string.app_name, R.string.settings_about_features_title,
                    R.string.made_possible_by_open_source).map(activity::getString)
                val cards = titles.map { title ->
                    val heading = all(root).filterIsInstance<TextView>().single { it.text.toString() == title }
                    (heading.parent.parent as LinearLayout).also {
                        assertNotNull("About card background missing: $title", it.background)
                        assertTrue(it.paddingLeft > 0 && it.paddingRight > 0)
                    }
                }
                assertEquals(3, cards.distinct().size)
                assertTrue(cards.all { it.parent === cards.first().parent })
                for (card in cards) for (text in all(card).filterIsInstance<TextView>()) {
                    assertTrue("Clipped text: ${text.text}",
                        text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    for (line in 0 until text.layout.lineCount)
                        assertEquals("Ellipsized: ${text.text}", 0, text.layout.getEllipsisCount(line))
                }
                val content = cards.first().parent as View
                val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                content.draw(Canvas(image))
                val folder = File("build/reports/about-cards-ui").apply { mkdirs() }
                File(folder, "about-$width-${if (night) "night" else "day"}.png").outputStream()
                    .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            } finally { owner.pause().stop().destroy() }
        }
    }
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
}
