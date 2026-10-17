package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Large controls and a persistent last-key banner for vehicle testing. */
internal class WheelTestLayout(private val context: Context, private val palette: DiPlayPalette) {
    enum class Action { ACQUIRE, RELEASE, PREVIOUS, NEXT, PLAY, PAUSE, TOGGLE, SIRI, BACK }
    data class Views(val root: ScrollView, val status: TextView, val event: TextView,
        val counts: TextView, val logs: TextView, val acquire: Button, val release: Button)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = FlymeAutoStyle.card(context, palette)
        val padding = dp(FlymeAutoStyle.CARD_PADDING_DP)
        setPadding(padding, padding, padding, padding)
    }
    private fun text(id: Int, size: Float) = TextView(context).apply {
        setText(id); textSize = size; setTextColor(palette.primaryText)
        setPadding(0, dp(8), 0, dp(8))
    }

    fun build(debugEnabled: Boolean, debugChanged: (Boolean) -> Unit, action: (Action) -> Unit): Views {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(FlymeAutoStyle.CARD_PADDING_DP), dp(16), dp(FlymeAutoStyle.CARD_PADDING_DP), dp(FlymeAutoStyle.CARD_PADDING_DP))
        }
        fun button(id: Int, key: Action) = Button(context).apply {
            setText(id)
            FlymeAutoStyle.button(this, palette, primary = key == Action.ACQUIRE)
            setOnClickListener { action(key) }
        }
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(R.string.wheel_test_title, 28f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(R.string.back, Action.BACK).apply { minWidth = dp(96) }, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        val wide = context.resources.configuration.screenWidthDp >= 840
        val feedback = card()
        val controls = card()
        val event = text(R.string.wheel_test_waiting, 32f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(90)
            setTextColor(palette.accent)
        }
        feedback.addView(event, LinearLayout.LayoutParams(-1, -2))
        val status = text(R.string.wheel_test_stopped, FlymeAutoStyle.BODY_TEXT_SP)
        feedback.addView(status)
        fun row(first: Button, second: Button) {
            val row = LinearLayout(context)
            row.addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(FlymeAutoStyle.CONTROL_GAP_DP) })
            row.addView(second, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.CONTROL_GAP_DP) })
        }
        val acquire = button(R.string.wheel_test_acquire, Action.ACQUIRE)
        val release = button(R.string.wheel_test_release, Action.RELEASE)
        row(acquire, release)
        row(button(R.string.wheel_test_previous, Action.PREVIOUS), button(R.string.wheel_test_next, Action.NEXT))
        row(button(R.string.wheel_test_play, Action.PLAY), button(R.string.wheel_test_pause, Action.PAUSE))
        row(button(R.string.wheel_test_toggle, Action.TOGGLE), button(R.string.wheel_test_siri, Action.SIRI))
        val counts = text(R.string.wheel_test_waiting, 16f)
        feedback.addView(counts)
        feedback.addView(text(R.string.wheel_test_voice_capture_hint, FlymeAutoStyle.SECONDARY_TEXT_SP).apply { setTextColor(palette.secondaryText) })
        if (wide) {
            content.addView(LinearLayout(context).apply {
                gravity = Gravity.TOP
                addView(feedback, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
                addView(controls, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            content.addView(feedback)
            content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
        }
        val diagnostics = card()
        content.addView(diagnostics, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(FlymeAutoStyle.BLOCK_GAP_DP) })
        diagnostics.addView(text(R.string.wheel_test_description, FlymeAutoStyle.SECONDARY_TEXT_SP))
        diagnostics.addView(Switch(context).apply {
            setText(R.string.settings_detailed_debug)
            textSize = FlymeAutoStyle.BUTTON_TEXT_SP
            setTextColor(palette.primaryText)
            minHeight = dp(FlymeAutoStyle.CONTROL_HEIGHT_DP)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(palette.accent, palette.secondaryText))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(palette.railSelected, palette.button))
            isChecked = debugEnabled
            setOnCheckedChangeListener { _, checked -> debugChanged(checked) }
        })
        diagnostics.addView(text(R.string.wheel_test_log_hint, FlymeAutoStyle.SECONDARY_TEXT_SP))
        val logs = text(R.string.wheel_test_waiting, FlymeAutoStyle.SECONDARY_TEXT_SP).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        diagnostics.addView(logs)
        val scroll = ScrollView(context).apply { setBackgroundColor(palette.background); isFillViewport = true; addView(content) }
        return Views(scroll, status, event, counts, logs, acquire, release)
    }
}
