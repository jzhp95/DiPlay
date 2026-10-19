package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Button
import androidx.core.graphics.ColorUtils

internal enum class DiPlayButtonKind { PRIMARY, SECONDARY, DANGER, TONAL_DANGER }

/**
 * Button for views built in code outside `DiPlayActivity`, whose own `button()` stays private.
 * The corner radius follows the control height, as in `DiPlayActivity.buttonShape`.
 */
internal class DiPlayButton(context: Context) : Button(context) {
    private var kind = DiPlayButtonKind.SECONDARY
    private var palette: DiPlayPalette? = null
    private var overlay = false

    init {
        isAllCaps = false
        FlymeAutoStyle.text(this, FlymeAutoStyle.TextRole.BUTTON)
        minHeight = FlymeAutoStyle.dp(context, FlymeAutoStyle.COMPACT_CONTROL_HEIGHT_DP)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** [overlay] picks the in-car menu colours instead of the app chrome colours. */
    fun style(kind: DiPlayButtonKind, palette: DiPlayPalette, overlay: Boolean) {
        this.kind = kind
        this.palette = palette
        this.overlay = overlay
        repaint()
    }

    override fun setLayoutParams(params: android.view.ViewGroup.LayoutParams) {
        if (params.height > 0) {
            minHeight = maxOf(minHeight, params.height)
            params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        super.setLayoutParams(params)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) repaint()
    }

    private fun repaint() {
        val palette = palette ?: return
        val fill: Int
        val text: Int
        when (kind) {
            DiPlayButtonKind.PRIMARY -> {
                fill = if (overlay) palette.overlayAccent else palette.accent
                text = if (overlay) palette.overlayOnAccent else palette.onAccent
            }
            DiPlayButtonKind.SECONDARY -> {
                fill = if (overlay) palette.overlayTrackOff else palette.button
                text = if (overlay) palette.overlayPrimaryText else palette.primaryText
            }
            DiPlayButtonKind.DANGER -> {
                fill = palette.overlayDanger
                text = Color.WHITE
            }
            DiPlayButtonKind.TONAL_DANGER -> {
                val surface = if (overlay) palette.overlayBackground else palette.surface
                fill = ColorUtils.blendARGB(surface, palette.overlayDanger, 0.16f)
                text = if (ColorUtils.calculateLuminance(surface) < 0.5)
                    ColorUtils.blendARGB(palette.overlayDanger, Color.WHITE, 0.40f)
                else palette.overlayDanger
            }
        }
        setTextColor(FlymeAutoStyle.contentColors(text))
        background = FlymeAutoStyle.control(context, palette, fill, if (height > 0) height else minHeight)
        FlymePressFeedback.install(this)
    }
}
