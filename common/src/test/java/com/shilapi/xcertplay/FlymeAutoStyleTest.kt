package com.shilapi.xcertplay

import android.graphics.Color
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class FlymeAutoStyleTest {
    @Test fun chromeUsesNeutralLayersInsteadOfBlueTintedBackgrounds() {
        for (p in listOf(DiPlayPalette.LIGHT, DiPlayPalette.DARK)) {
            for(c in listOf(p.background, p.surface, p.button)) {
                val channels = listOf(Color.red(c), Color.green(c), Color.blue(c))
                assertTrue("Flyme surfaces must stay neutral", channels.max() - channels.min() <= 8)
            }
            assertTrue(Color.blue(p.accent) > Color.red(p.accent))
        }
    }

    @Test fun homeActionsUseTheAppAccentAndRemainFunctional() {
        val context = RuntimeEnvironment.getApplication()
        var calls = 0
        val home = CarPlayHomeLayout(context, DiPlayPalette.LIGHT,
            makeButton = { title, _, click -> Button(context).apply { text = title; setOnClickListener { click() } } },
            makeLabel = { title, size, color, _ -> TextView(context).apply { text = title; textSize = size.toFloat(); setTextColor(color) } })
        val cards = home.cards({ calls++ }, { calls += 10 }, "连接提示")
        assertTrue("main action must be legible", cards.connect.textSize >= 18f)
        assertTrue("main action needs a large touch target", cards.connect.minHeight >= 56)
        cards.connect.performClick(); cards.usbConnect.performClick()
        assertEquals(11, calls)
        assertEquals(DiPlayPalette.LIGHT.onAccent, cards.connect.currentTextColor)
    }
}
