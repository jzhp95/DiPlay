package com.shilapi.xcertplay.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.FlymeAutoStyle
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

object SettingsWidgets {

    private fun TextView.applyTextStyle(context: Context, theme: SettingsTheme, bold: Boolean = false) {
        val requestedSp = textSize / context.resources.displayMetrics.scaledDensity
        FlymeAutoStyle.text(this, when {
            bold -> FlymeAutoStyle.TextRole.LABEL
            requestedSp <= FlymeAutoStyle.SECONDARY_TEXT_SP -> FlymeAutoStyle.TextRole.SECONDARY
            else -> FlymeAutoStyle.TextRole.BODY
        }, compact = !theme.isOverlay && FlymeAutoStyle.compact(context))
        gravity = Gravity.CENTER_VERTICAL
    }

    data class SwitchRowResult(
        val rowView: View,
        val switch: Switch,
    )

    data class ChoiceRowResult<T>(
        val container: View,
        val radioGroup: RadioGroup,
    )

    data class ResolutionSliderResult(
        val container: View,
        val valueTextView: TextView,
        val seekBar: SeekBar,
    )

    fun createCategoryHeader(
        context: Context,
        title: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
    ): TextView = TextView(context).apply {
        text = title
        textSize = if (theme.isOverlay) FlymeAutoStyle.BUTTON_TEXT_SP else 22f
        setTextColor(if (theme.isOverlay) theme.accentText else theme.textPrimary)
        FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECTION_TITLE)
        setTextColor(theme.palette.supportingText)
    }

    /**
     * Native-size track and inset thumb keep both states readable at arm's length.
     */
    fun applyLargeSwitchStyle(switch: Switch, thumbOn: Int, thumbOff: Int, trackOn: Int, trackOff: Int) {
        val density = switch.resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()
        // Switch stretches the thumb to the full track height, so the inset keeps a margin around it.
        val thumbInset = px(FlymeAutoStyle.SWITCH_INSET_DP)
        switch.thumbDrawable = InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(px(FlymeAutoStyle.SWITCH_THUMB_DP), px(FlymeAutoStyle.SWITCH_THUMB_DP))
                setColor(Color.WHITE)
            },
            thumbInset,
        )
        switch.trackDrawable = GradientDrawable().apply {
            cornerRadius = px(FlymeAutoStyle.SWITCH_HEIGHT_DP / 2).toFloat()
            setSize(px(FlymeAutoStyle.SWITCH_WIDTH_DP), px(FlymeAutoStyle.SWITCH_HEIGHT_DP))
            setColor(Color.WHITE)
        }
        switch.switchMinWidth = px(FlymeAutoStyle.SWITCH_WIDTH_DP)
        switch.showText = false
        switch.thumbTintList = switchStates(thumbOn, thumbOff)
        switch.trackTintList = switchStates(trackOn, trackOff)
    }

    private fun switchStates(on: Int, off: Int) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(ColorUtils.setAlphaComponent(off, 0x61), on, off),
    )

    fun createSwitchRow(
        context: Context,
        label: String,
        description: String,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        enabled: Boolean = true,
        labelSizeSp: Float = 18f,
        onChanged: (Boolean) -> Unit,
    ): SwitchRowResult {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switch = Switch(context).apply {
            isChecked = checked
            this.contentDescription = contentDescription
            isEnabled = enabled
            applyLargeSwitchStyle(this, Color.WHITE, Color.WHITE, theme.accentTrack, theme.trackOff)
            minHeight = theme.dp(context, FlymeAutoStyle.CONTROL_HEIGHT_DP)
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        val inset = theme.dp(context, FlymeAutoStyle.INPUT_PADDING_DP)
        row.setPaddingRelative(inset, inset, inset, inset)
        row.minimumHeight = theme.dp(context, if (FlymeAutoStyle.compact(context))
            FlymeAutoStyle.COMPACT_SETTING_ROW_HEIGHT_DP else FlymeAutoStyle.SETTING_ROW_HEIGHT_DP)
        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(context).apply {
            text = label
            setTextColor(theme.textPrimary)
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.LABEL)
        })
        if (description.isNotBlank()) textCol.addView(TextView(context).apply {
            text = description
            setTextColor(theme.textSecondary)
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECONDARY)
            setPaddingRelative(0, theme.dp(context, FlymeAutoStyle.TITLE_DESCRIPTION_GAP_DP), 0, 0)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = theme.dp(context, FlymeAutoStyle.CONTROL_GAP_DP)
        })
        row.addView(switch)
        switch.isFocusable = false
        row.isFocusable = true
        row.isEnabled = enabled
        row.background = FlymeAutoStyle.control(context, theme.palette, theme.palette.button, row.minimumHeight)
        com.shilapi.xcertplay.FlymePressFeedback.install(row)
        row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
        switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = switch.isChecked
                info.isEnabled = switch.isEnabled
            }
        }

        return SwitchRowResult(row, switch)
    }

    fun <T> createChoiceRow(
        context: Context,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (T) -> Unit,
    ): ChoiceRowResult<T> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = FlymeAutoStyle.BUTTON_TEXT_SP
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        container.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, theme.dp(context, 4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                this.text = text
                FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.VALUE)
                minHeight = theme.dp(context, FlymeAutoStyle.INPUT_HEIGHT_DP)
                val inset = theme.dp(context, FlymeAutoStyle.INPUT_PADDING_DP)
                setPaddingRelative(inset, inset, inset, inset)
                setTextColor(theme.textPrimary)
                background = FlymeAutoStyle.control(context, theme.palette, theme.palette.button, minHeight)
                com.shilapi.xcertplay.FlymePressFeedback.install(this)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(theme.accent, theme.textSecondary),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = theme.dp(context, FlymeAutoStyle.CONTROL_GAP_DP) },
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        var lastSelectedId = group.checkedRadioButtonId
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            if (checkedId == View.NO_ID) {
                lastSelectedId = View.NO_ID
                return@setOnCheckedChangeListener
            }
            val button = radioGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            // RadioGroup may notify while unchecking the old button, then repeat the new ID.
            if (!button.isChecked || checkedId == lastSelectedId) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = button.tag as? T ?: return@setOnCheckedChangeListener
            lastSelectedId = checkedId
            onSelected(value)
        }
        container.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ChoiceRowResult(container, group)
    }

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): ResolutionSliderResult {
        val initial = initialPercent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.resolution)
            textSize = 20f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueTextView = TextView(context).apply {
            text = "$initial%"
            textSize = 28f
            setTextColor(theme.accentText)
            applyTextStyle(context, theme, bold = true)
        }
        header.addView(
            valueTextView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 14) },
        )

        val seekBar = SeekBar(context).apply {
            max = CarPlayDisplayScale.MAX_PERCENT - CarPlayDisplayScale.MIN_PERCENT
            progress = initial - CarPlayDisplayScale.MIN_PERCENT
            splitTrack = false
            progressTintList = ColorStateList.valueOf(theme.accent)
            thumbTintList = ColorStateList.valueOf(theme.accent)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val percent = (CarPlayDisplayScale.MIN_PERCENT + progress)
                            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
                        valueTextView.text = "$percent%"
                        onPercentChanged(percent)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                },
            )
        }
        container.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 8) },
        )

        val range = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val minLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MIN_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(minLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val maxLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MAX_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(
            maxLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        return ResolutionSliderResult(container, valueTextView, seekBar)
    }
}
