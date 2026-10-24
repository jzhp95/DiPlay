package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsWidgets

/** An in-activity overlay: no dimmed background, decoder restart, or stream negotiation. */
internal class CarPlayPicturePanel(
    context: Context,
    adjustmentsAvailable: Boolean = true,
    initialPalette: DiPlayPalette = DiPlayPalette.DARK,
    close: () -> Unit,
) : LinearLayout(context) {
    private var palette = initialPalette
    private val prefs = CarPlayPicture.preferences(context)
    private val controls = mutableMapOf<String, Pair<TextView, SeekBar>>()
    private val original = Switch(context)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(context).apply {
        text = value
        FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.BODY)
        setTextColor(palette.overlayPrimaryText)
    }
    init {
        orientation = VERTICAL
        val inset = dp(FlymeAutoStyle.CARD_PADDING_DP)
        setPadding(inset, inset, inset, inset)
        setBackgroundColor(palette.overlayBackground)
        isClickable = true
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(rows, LayoutParams(-1, -2))
        }
        addView(scroll, LayoutParams(-1, 0, 1f))
        rows.addView(text(context.getString(R.string.picture_adjustments)).apply {
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.PAGE_TITLE)
        })
        rows.addView(text(context.getString(if (adjustmentsAvailable) R.string.picture_live_hint
            else R.string.picture_surface_output_unavailable)).apply {
            FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.SECONDARY)
            setTextColor(palette.overlaySecondaryText)
        })
        val labels = listOf(R.string.picture_brightness, R.string.picture_contrast,
            R.string.picture_saturation, R.string.picture_warmth)
        CarPlayPicture.keys.forEachIndexed { index, key ->
            val label = text("")
            val range = CarPlayPicture.range(key)
            val slider = SeekBar(context).apply {
                max = range.last - range.first
                progress = CarPlayPicture.value(prefs, key) - range.first
                contentDescription = context.getString(labels[index])
                isEnabled = adjustmentsAvailable
                progressTintList = android.content.res.ColorStateList.valueOf(palette.overlayAccent)
                thumbTintList = android.content.res.ColorStateList.valueOf(palette.overlayAccent)
            }
            fun updateLabel(value: Int) { label.text = "${context.getString(labels[index])}: $value" }
            updateLabel(CarPlayPicture.value(prefs, key))
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                    updateLabel(progress + range.first)
                    if (fromUser && adjustmentsAvailable) prefs.edit().putInt(key, progress + range.first).apply()
                }
                override fun onStartTrackingTouch(seek: SeekBar) = Unit
                override fun onStopTrackingTouch(seek: SeekBar) = Unit
            })
            controls[key] = label to slider
            rows.addView(label)
            rows.addView(slider, LayoutParams(-1, dp(48)))
        }
        original.apply {
            text = context.getString(R.string.picture_show_original)
            setTextColor(palette.overlayPrimaryText)
            SettingsWidgets.applyLargeSwitchStyle(
                this,
                Color.WHITE,
                Color.WHITE,
                palette.overlayAccentTrack,
                palette.overlayTrackOff,
            )
            isEnabled = adjustmentsAvailable
            setOnCheckedChangeListener { _, checked ->
                if (!adjustmentsAvailable) return@setOnCheckedChangeListener
                CarPlayPicture.showOriginal(checked)
                controls.values.forEach { it.second.isEnabled = adjustmentsAvailable && !checked }
            }
        }
        FlymeAutoStyle.text(original, FlymeAutoStyle.TextRole.BODY)
        original.minimumHeight = dp(FlymeAutoStyle.CONTROL_HEIGHT_DP)
        rows.addView(original, LayoutParams(-1, -2))
        val actions = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                for (i in 0 until childCount) getChildAt(i).layoutParams.height = -2
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val tallest = (0 until childCount).maxOf { getChildAt(it).measuredHeight }
                for (i in 0 until childCount) getChildAt(i).layoutParams.height = tallest
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        actions.addView(DiPlayButton(context).apply {
            text = context.getString(R.string.picture_reset)
            isEnabled = adjustmentsAvailable
            setOnClickListener {
                if (!adjustmentsAvailable) return@setOnClickListener
                original.isChecked = false
                CarPlayPicture.reset(prefs)
                controls.forEach { (key, pair) ->
                    pair.second.progress = CarPlayPicture.defaultValue(key) - CarPlayPicture.range(key).first
                }
            }
        }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(FlymeAutoStyle.CONTROL_GAP_DP) })
        actions.addView(DiPlayButton(context).apply {
            text = context.getString(R.string.picture_done)
            setOnClickListener { close() }
        }, LayoutParams(0, -2, 1f))
        rows.addView(actions)
        applyPalette(initialPalette)
    }

    fun applyPalette(palette: DiPlayPalette) {
        this.palette = palette
        setBackgroundColor(palette.overlayBackground)
        fun apply(view: View) {
            when (view) {
                is Switch -> {
                    view.setTextColor(palette.overlayPrimaryText)
                    SettingsWidgets.applyLargeSwitchStyle(
                        view,
                        Color.WHITE,
                        Color.WHITE,
                        palette.overlayAccentTrack,
                        palette.overlayTrackOff,
                    )
                }
                is SeekBar -> {
                    view.progressTintList = android.content.res.ColorStateList.valueOf(palette.overlayAccent)
                    view.thumbTintList = android.content.res.ColorStateList.valueOf(palette.overlayAccent)
                }
                is DiPlayButton -> view.style(if (view.text == context.getString(R.string.picture_done))
                    DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY, palette, overlay = true)
                is TextView -> view.setTextColor(palette.overlayPrimaryText)
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) apply(view.getChildAt(index))
            }
        }
        apply(this)
    }
}
