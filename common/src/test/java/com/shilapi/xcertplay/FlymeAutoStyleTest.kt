package com.shilapi.xcertplay

import android.graphics.Color
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test fun controlsFollowSystemButtonProportionsAndPairedLineHeight() {
        val context = RuntimeEnvironment.getApplication()
        val d = context.resources.displayMetrics.density
        val sp = context.resources.displayMetrics.scaledDensity
        assertEquals(24f * d, FlymeAutoStyle.radius(context, (64 * d).toInt()), .1f)
        assertEquals(21f * d, FlymeAutoStyle.radius(context, (56 * d).toInt()), .1f)
        val full = Button(context)
        FlymeAutoStyle.button(full, DiPlayPalette.DARK, compact = false)
        assertEquals(24f * sp, full.textSize, .1f)
        assertEquals(34f * sp, full.lineHeight.toFloat(), 1f)
        val compact = Button(context)
        FlymeAutoStyle.button(compact, DiPlayPalette.DARK, compact = true)
        assertEquals(18f * sp, compact.textSize, .1f)
        assertEquals(26f * sp, compact.lineHeight.toFloat(), 1f)
    }

    @Test fun settingActionsTreatRequestedHeightAsMinimumAndWrapLongLabels() {
        val context = RuntimeEnvironment.getApplication()
        val button = DiPlayActivity.SettingButton(context, DiPlayPalette.DARK.secondaryText, DiPlayPalette.DARK.accent)
        FlymeAutoStyle.button(button, DiPlayPalette.DARK, compact = false)
        button.text = "用于较窄窗口的较长设置操作标题".repeat(2)
        button.layoutParams = android.widget.LinearLayout.LayoutParams(220, 50)
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, button.layoutParams.height)
        val parent = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL; addView(button) }
        parent.measure(android.view.View.MeasureSpec.makeMeasureSpec(220, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.AT_MOST))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        assertTrue(button.layout.lineCount > 1)
        assertTrue(button.height >= button.layout.height + button.compoundPaddingTop + button.compoundPaddingBottom)
    }

    @Test fun picturePanelCheckedThumbRemainsVisibleAgainstTheBrandTrack() {
        val panel = CarPlayPicturePanel(RuntimeEnvironment.getApplication(), close = {})
        fun views(v: android.view.View): Sequence<android.view.View> = sequence {
            yield(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
        }
        val control = views(panel).filterIsInstance<android.widget.Switch>().first()
        control.isChecked = true
        assertEquals(Color.WHITE, control.thumbTintList!!.getColorForState(control.drawableState, 0))
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
