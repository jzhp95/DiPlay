package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayHomeLayoutTest {
    @Config(qualifiers = "zh-rCN-w1100dp-h700dp-land")
    @Test fun wideHomeHasTwoConnectionCardsAndKeepsEveryAction() = verify(wide = true)

    @Config(qualifiers = "ar-w320dp-h700dp-port")
    @Test fun narrowHomeStacksCardsWithoutLosingActions() = verify(wide = false)

    private fun verify(wide: Boolean) {
        val context = RuntimeEnvironment.getApplication() as Context
        val calls = mutableListOf<String>()
        val layout = CarPlayHomeLayout(context, DiPlayPalette.LIGHT,
            makeButton = { title, _, click -> Button(context).apply {
                text = title; setOnClickListener { click() }
            } },
            makeLabel = { title, size, color, _ -> TextView(context).apply {
                text = title; textSize = size.toFloat(); setTextColor(color)
            } })
        val header = layout.header(View(context),
            { calls += "phone" }, { calls += "disconnect" },
            { calls += "settings" }, { calls += "home" })
        val cards = layout.cards({ calls += "wireless" }, { calls += "usb" }, "hint", "version")
        val width = (context.resources.configuration.screenWidthDp * context.resources.displayMetrics.density).toInt()
        fun measure(view: View) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, width, view.measuredHeight)
        }
        measure(header.root)
        measure(cards.root)
        if (wide) assertTrue("USB must be beside wireless", cards.usb.left >= cards.wireless.right)
        else assertTrue("USB must follow wireless", cards.usb.top >= cards.wireless.bottom)
        assertTrue(cards.connect.performClick())
        assertTrue(cards.usbConnect.performClick())
        header.disconnect.performClick()
        for (id in listOf(R.string.choose_iphone, R.string.settings, R.string.car_home)) {
            descendants(header.root).filterIsInstance<Button>().single { it.text == context.getString(id) }.performClick()
        }
        assertEquals(listOf("wireless", "usb", "disconnect", "phone", "settings", "home"), calls)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
