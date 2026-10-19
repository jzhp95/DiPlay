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
    @Test fun chromeUsesTheVerifiedSystemBlueAndBlueGreyLayers() {
        assertEquals(Color.rgb(19, 104, 251), DiPlayPalette.LIGHT.accent)
        assertEquals(Color.rgb(19, 104, 251), DiPlayPalette.DARK.accent)
        assertEquals(Color.WHITE, DiPlayPalette.DARK.onAccent)
        assertEquals(Color.rgb(211, 219, 229), DiPlayPalette.LIGHT.background)
        assertEquals(Color.rgb(32, 39, 46), DiPlayPalette.DARK.background)
        assertEquals(0xE5F6FBFF.toInt(), DiPlayPalette.LIGHT.surface)
        assertEquals(0x66495460, DiPlayPalette.DARK.surface)
        val button = Button(RuntimeEnvironment.getApplication())
        FlymeAutoStyle.button(button, DiPlayPalette.DARK, primary = true)
        assertEquals("Night primary actions retain white content on the system brand blue", Color.WHITE, button.currentTextColor)
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
