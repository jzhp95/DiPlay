package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.DiPlayPalette

class SettingsTheme private constructor(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentText: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
    val ripple: Int,
    val focusRing: Int,
    internal val palette: DiPlayPalette,
) {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        /** Dark aliases keep existing overlay callers source-compatible until Phase 4. */
        @JvmField val CARD = card(DiPlayPalette.DARK)
        @JvmField val OVERLAY = overlay(DiPlayPalette.DARK)
        @JvmField val entries = listOf(CARD, OVERLAY)

        internal fun card(palette: DiPlayPalette) = SettingsTheme(
            isOverlay = false,
            palette = palette,
            textPrimary = palette.primaryText,
            textSecondary = palette.secondaryText,
            accent = palette.accent,
            accentText = palette.accentText,
            accentTrack = palette.accentTrack,
            trackOff = palette.trackOff,
            buttonText = palette.onAccent,
            ripple = palette.ripple,
            focusRing = palette.focusRing,
        )

        internal fun overlay(palette: DiPlayPalette) = SettingsTheme(
            isOverlay = true,
            palette = palette,
            textPrimary = palette.overlayPrimaryText,
            textSecondary = palette.overlaySecondaryText,
            accent = palette.overlayAccent,
            accentText = palette.overlayAccentText,
            accentTrack = palette.overlayAccentTrack,
            trackOff = palette.overlayTrackOff,
            buttonText = palette.overlayOnAccent,
            ripple = palette.ripple,
            focusRing = palette.focusRing,
        )
    }
}
