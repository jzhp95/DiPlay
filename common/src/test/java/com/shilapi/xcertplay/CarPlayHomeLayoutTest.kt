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
        val cards = layout.cards({ calls += "wireless" }, { calls += "usb" }, "hint")
        val width = (context.resources.configuration.screenWidthDp * context.resources.displayMetrics.density).toInt()
        fun measure(view: View) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, width, view.measuredHeight)
        }
        measure(header.root)
        measure(cards.root)
        if (wide) {
            assertTrue("USB must be beside wireless", cards.usb.left >= cards.wireless.right)
            assertTrue("card widths differ: ${cards.wireless.width}/${cards.usb.width}",
                kotlin.math.abs(cards.wireless.width - cards.usb.width) <= 1)
            assertEquals(cards.wireless.height, cards.usb.height)
        }
        else assertTrue("USB must follow wireless", cards.usb.top >= cards.wireless.bottom)
        assertTrue(cards.connect.performClick())
        assertTrue(cards.usbConnect.performClick())
        header.disconnect.performClick()
        for (id in listOf(R.string.choose_iphone, R.string.settings, R.string.car_home)) {
            descendants(header.root).filterIsInstance<Button>().single { it.text == context.getString(id) }.performClick()
        }
        assertEquals(listOf("wireless", "usb", "disconnect", "phone", "settings", "home"), calls)
    }

    @Test @Config(qualifiers = "zh-rCN-w1100dp-h700dp-land")
    fun bothCardsRemainVisibleInsideTheActivityScrollContainer() = verifyScrollContainer(1100, 700)

    @Test @Config(qualifiers = "zh-rCN-w900dp-h400dp-land")
    fun shortCarScreenKeepsBothCardsMeasurableAndScrollable() = verifyScrollContainer(900, 400)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h700dp-port")
    fun stackedUsbCardCanBeReachedByScrolling() = verifyScrollContainer(400, 700)

    @Test @Config(qualifiers = "zh-rCN-w900dp-h400dp-land")
    fun shortScreenKeepsALongNoticeAndPermissionActionOutsideTheScrollableCards() {
        RuntimeEnvironment.setFontScale(1.5f)
        try { verifyScrollContainer(900, 400, stressFooter = true) }
        finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun verifyScrollContainer(widthDp: Int, heightDp: Int, stressFooter: Boolean = false) {
        val context = RuntimeEnvironment.getApplication()
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        val layout = CarPlayHomeLayout(context, DiPlayPalette.LIGHT,
            makeButton = { title, _, click -> Button(context).apply {
                text = title; setOnClickListener { click() }
            } },
            makeLabel = { title, size, color, _ -> TextView(context).apply {
                text = title; textSize = size.toFloat(); setTextColor(color)
            } })
        val cards = layout.cards({}, {}, "hint")
        cards.notice.text = context.getString(R.string.hotspot_details_off)
        cards.notice.visibility = View.VISIBLE
        if (stressFooter) {
            cards.notice.text = context.getString(R.string.hotspot_details_off).repeat(4)
            cards.footer.addView(Button(context).apply { text = context.getString(R.string.settings_write_settings_allow) })
        }
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
            setPadding(32, 24, 32, 32)
            addView(layout.header(View(context), {}, {}, {}, {}).root)
            addView(View(context), android.widget.LinearLayout.LayoutParams(-1, 24))
            layout.addCardsTo(this, cards)
        }
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        val screen = layout.screen(scroll, content, cards)
        for (viewport in listOf(height, maxOf(height - (100 * density).toInt(), 300), height)) {
            scroll.scrollTo(0, 0)
            screen.requestLayout()
            screen.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewport, View.MeasureSpec.EXACTLY))
            screen.layout(0, 0, width, viewport)
            if (widthDp >= 850) {
                assertTrue(kotlin.math.abs(cards.wireless.width - cards.usb.width) <= 1)
                assertEquals(cards.wireless.height, cards.usb.height)
            }
            assertFalse("notice must be outside wireless card", cards.notice.parent === cards.wireless)
            assertEquals(viewport, cards.footer.bottom)
            assertEquals(content.paddingLeft, cards.notice.left)
            val upperSpace = content.getChildAt(2).height
            val lowerSpace = content.getChildAt(4).height
            assertTrue("cards are not centered: above=$upperSpace below=$lowerSpace", kotlin.math.abs(upperSpace - lowerSpace) <= 1)
            assertTrue("wireless height=${cards.wireless.height}", cards.wireless.height > 100)
            assertTrue("usb height=${cards.usb.height}", cards.usb.height > 100)
            assertTrue("connect height=${cards.connect.height}", cards.connect.height > 0)
            val cardBounds = android.graphics.Rect(0, 0, cards.wireless.width, cards.wireless.height)
            content.offsetDescendantRectToMyCoords(cards.wireless, cardBounds)
            assertTrue("card starts below viewport: $cardBounds", cardBounds.top < viewport)
            for (button in listOf(cards.connect, cards.usbConnect)) {
                assertTrue("empty button: ${button.text}", button.width > 0 && button.height > 0)
                val bounds = android.graphics.Rect(0, 0, button.width, button.height)
                content.offsetDescendantRectToMyCoords(button, bounds)
                assertTrue("button outside content: $bounds", bounds.top >= 0 && bounds.bottom <= content.height)
                scroll.scrollTo(0, maxOf(0, bounds.bottom - scroll.height))
                assertTrue("button cannot scroll into view: $bounds", bounds.bottom - scroll.scrollY <= scroll.height &&
                    bounds.top - scroll.scrollY >= 0)
            }

        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
