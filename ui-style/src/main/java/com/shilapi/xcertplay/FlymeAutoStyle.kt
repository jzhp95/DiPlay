package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button

/** Presentation tokens for app-owned screens; no session, preference or input ownership. */
internal object FlymeAutoStyle {
    const val CARD_CORNER_DP = 24
    const val CARD_PADDING_DP = 24
    const val BLOCK_GAP_DP = 20
    const val CONTROL_GAP_DP = 12
    const val BUTTON_PADDING_HORIZONTAL_DP = 20
    const val BUTTON_PADDING_VERTICAL_DP = 8
    const val FOCUS_STROKE_DP = 2
    const val CONTROL_HEIGHT_DP = 64
    const val COMPACT_CONTROL_HEIGHT_DP = 56
    const val DIALOG_CONTROL_HEIGHT_DP = 72
    const val BUTTON_TEXT_SP = 20f
    const val PAGE_TITLE_SP = 28f
    const val BODY_TEXT_SP = 18f
    const val SECONDARY_TEXT_SP = 16f
    const val NAVIGATION_TEXT_SP = 18f

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun radius(context: Context, heightPx: Int): Float =
        minOf(dp(context, CARD_CORNER_DP).toFloat(), heightPx.coerceAtLeast(1) * .28f)

    fun card(context: Context, palette: DiPlayPalette) = GradientDrawable().apply {
        setColor(palette.surface)
        cornerRadius = dp(context, CARD_CORNER_DP).toFloat()
    }

    fun control(context: Context, palette: DiPlayPalette, primary: Boolean, heightPx: Int): RippleDrawable {
        val radius = radius(context, heightPx)
        val color = if (primary) palette.accent else palette.button
        fun fill(outline: Boolean) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (outline) setStroke(dp(context, FOCUS_STROKE_DP), palette.focusRing)
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), fill(false).apply { alpha = 110 })
            addState(intArrayOf(android.R.attr.state_focused), fill(true))
            addState(intArrayOf(), fill(false))
        }
        return RippleDrawable(ColorStateList.valueOf(palette.ripple), states,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius })
    }

    /** Applies visuals only; leaves text, click handlers, enabled state and layout params intact. */
    fun button(button: Button, palette: DiPlayPalette, primary: Boolean = false, compact: Boolean = false) {
        val context = button.context
        button.isAllCaps = false
        button.textSize = if (compact) NAVIGATION_TEXT_SP else BUTTON_TEXT_SP
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.gravity = Gravity.CENTER
        button.stateListAnimator = null
        button.minHeight = dp(context, if (compact) COMPACT_CONTROL_HEIGHT_DP else CONTROL_HEIGHT_DP)
        button.setPadding(dp(context, BUTTON_PADDING_HORIZONTAL_DP), dp(context, BUTTON_PADDING_VERTICAL_DP), dp(context, BUTTON_PADDING_HORIZONTAL_DP), dp(context, BUTTON_PADDING_VERTICAL_DP))
        val text = if (primary) palette.onAccent else palette.primaryText
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf((text and 0x00ffffff) or 0x66000000, text)))
        button.backgroundTintList = null
        fun paint() { button.background = control(context, palette, primary, button.height.takeIf { it > 0 } ?: button.minHeight) }
        paint()
        button.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) paint()
        }
    }

}
