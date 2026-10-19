package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/** App-owned Flyme Auto presentation. No preferences, transport or input ownership. */
internal object FlymeAutoStyle {
    const val CARD_CORNER_DP = 24
    const val CARD_PADDING_DP = 24
    const val BLOCK_GAP_DP = 32
    const val CONTROL_GAP_DP = 16
    const val BUTTON_PADDING_HORIZONTAL_DP = 24
    const val BUTTON_PADDING_VERTICAL_DP = 12
    const val FOCUS_STROKE_DP = 2
    const val CONTROL_HEIGHT_DP = 64
    const val COMPACT_CONTROL_HEIGHT_DP = 56
    const val DIALOG_CONTROL_HEIGHT_DP = 80
    const val BUTTON_TEXT_SP = 24f
    const val PAGE_TITLE_SP = 28f
    const val BODY_TEXT_SP = 22f
    const val SECONDARY_TEXT_SP = 18f
    const val NAVIGATION_TEXT_SP = 22f
    const val NAVIGATION_HEIGHT_DP = 72
    const val COMPACT_NAVIGATION_HEIGHT_DP = 64

    enum class TextRole { PAGE_TITLE, SECTION_TITLE, BODY, SECONDARY, BUTTON, NAVIGATION }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun compact(context: Context) = context.resources.configuration.screenWidthDp < 600
    fun radius(context: Context, heightPx: Int): Float =
        minOf(dp(context, CARD_CORNER_DP).toFloat(), heightPx.coerceAtLeast(1) * .375f)

    /** Paired type size/line height, scaled together with the driver's font preference. */
    fun text(view: TextView, role: TextRole, compact: Boolean = compact(view.context)) {
        val (size, line) = when (role) {
            TextRole.PAGE_TITLE -> if (compact) 24f to 34f else 28f to 40f
            TextRole.SECTION_TITLE -> if (compact) 22f to 30f else 26f to 36f
            TextRole.BODY -> if (compact) 18f to 26f else 22f to 30f
            TextRole.SECONDARY -> if (compact) 16f to 24f else 18f to 26f
            TextRole.BUTTON -> if (compact) 18f to 26f else 24f to 34f
            TextRole.NAVIGATION -> if (compact) 18f to 26f else 22f to 32f
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        view.typeface = Typeface.create(if (role in listOf(TextRole.PAGE_TITLE, TextRole.SECTION_TITLE,
                TextRole.BUTTON, TextRole.NAVIGATION)) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        view.includeFontPadding = false
        val linePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, line, view.resources.displayMetrics)
        val metrics = view.paint.fontMetricsInt
        view.setLineSpacing((linePx - (metrics.descent - metrics.ascent)).coerceAtLeast(0f), 1f)
    }

    fun card(context: Context, palette: DiPlayPalette) = GradientDrawable().apply {
        setColor(palette.surface)
        cornerRadius = dp(context, CARD_CORNER_DP).toFloat()
    }

    fun control(context: Context, palette: DiPlayPalette, primary: Boolean, heightPx: Int): StateListDrawable =
        control(context, palette, if (primary) palette.accent else palette.button, heightPx)

    /** Bounds own the radius, so restyling does not accumulate View layout listeners. */
    fun control(context: Context, palette: DiPlayPalette, fill: Int, heightPx: Int): StateListDrawable =
        object : StateListDrawable() {
            private val fills = List(3) { index -> GradientDrawable().apply {
                setColor(fill)
                cornerRadius = radius(context, heightPx)
                if (index == 0) alpha = 89
                if (index == 1) setStroke(dp(context, FOCUS_STROKE_DP), palette.focusRing)
            } }
            init {
                addState(intArrayOf(-android.R.attr.state_enabled), fills[0])
                addState(intArrayOf(android.R.attr.state_focused), fills[1])
                addState(intArrayOf(), fills[2])
            }
            override fun onBoundsChange(bounds: Rect) {
                super.onBoundsChange(bounds)
                if (bounds.height() > 0) fills.forEach { it.cornerRadius = radius(context, bounds.height()) }
            }
        }

    fun contentColors(text: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf((text and 0x00ffffff) or 0x59000000, text))

    /** Keeps click/touch listeners, text, enabled state and layout parameters owned by the caller. */
    fun button(button: Button, palette: DiPlayPalette, primary: Boolean = false, compact: Boolean = compact(button.context)) {
        val context = button.context
        button.isAllCaps = false
        text(button, TextRole.BUTTON, compact)
        button.gravity = Gravity.CENTER
        button.minHeight = dp(context, if (compact) COMPACT_CONTROL_HEIGHT_DP else CONTROL_HEIGHT_DP)
        val horizontal = dp(context, if (compact) 16 else BUTTON_PADDING_HORIZONTAL_DP)
        val vertical = dp(context, if (compact) 8 else BUTTON_PADDING_VERTICAL_DP)
        button.setPadding(horizontal, vertical, horizontal, vertical)
        val text = if (primary) palette.onAccent else palette.primaryText
        button.setTextColor(contentColors(text))
        button.backgroundTintList = null
        button.background = control(context, palette, primary, button.height.takeIf { it > 0 } ?: button.minHeight)
        FlymePressFeedback.install(button)
    }
}
