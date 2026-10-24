package com.shilapi.xcertplay

import android.graphics.Color

/** Semantic colors for DiPlay-owned chrome. Media canvases and cluster-specific palettes stay separate. */
internal data class DiPlayPalette(
    val background: Int,
    val surface: Int,
    val button: Int,
    val settingsControlFill: Int,
    val outline: Int,
    val railSelected: Int,
    val railSelectedOutline: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val accentText: Int,
    val dialogSurface: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val warning: Int,
    val success: Int,
    val danger: Int,
    val ripple: Int,
    val focusRing: Int,
    val systemBar: Int,
    val systemBarIconsAreDark: Boolean,
    val overlayBackground: Int,
    val overlayPrimaryText: Int,
    val overlaySecondaryText: Int,
    val overlayAccent: Int,
    val overlayOnAccent: Int,
    val overlayAccentText: Int,
    val overlayAccentTrack: Int,
    val overlayTrackOff: Int,
    val overlayDanger: Int,
) {
    /** Native text/input roles are separate from the optional functional-card layer. */
    val supportingText: Int get() = if (systemBarIconsAreDark) 0x7F000000 else 0x7FFFFFFF
    val inputFill: Int get() = if (systemBarIconsAreDark) Color.rgb(242,248,252) else Color.rgb(48,57,66)

    companion object {
        fun of(night: Boolean): DiPlayPalette = if (night) DARK else LIGHT

        // Flyme Auto IHU623G/537: brand fill is shared by both modes. Text emphasis is a
        // separate role so small labels remain legible on the blue-grey layered backgrounds.
        val DARK = DiPlayPalette(
            background = Color.rgb(32, 39, 46),
            surface = 0x66495460,
            button = Color.rgb(40, 44, 49),
            settingsControlFill = Color.rgb(40, 44, 49),
            outline = Color.rgb(71, 80, 90),
            railSelected = 0xBF15191F.toInt(),
            railSelectedOutline = Color.TRANSPARENT,
            primaryText = 0xE5FFFFFF.toInt(),
            secondaryText = 0x80FFFFFF.toInt(),
            accent = Color.rgb(19, 104, 251),
            onAccent = Color.WHITE,
            accentText = Color.rgb(102, 178, 255),
            dialogSurface = Color.rgb(36, 40, 46),
            accentTrack = Color.rgb(19, 104, 251),
            trackOff = Color.rgb(105, 116, 127),
            warning = Color.rgb(255, 174, 0),
            success = Color.rgb(45, 178, 89),
            danger = Color.rgb(217, 59, 66),
            ripple = 0x08FFFFFF,
            focusRing = Color.rgb(102, 178, 255),
            systemBar = Color.rgb(32, 39, 46),
            systemBarIconsAreDark = false,
            overlayBackground = Color.rgb(32, 39, 46),
            overlayPrimaryText = 0xE5FFFFFF.toInt(),
            overlaySecondaryText = 0x80FFFFFF.toInt(),
            overlayAccent = Color.rgb(19, 104, 251),
            overlayOnAccent = Color.WHITE,
            overlayAccentText = Color.rgb(102, 178, 255),
            overlayAccentTrack = Color.rgb(19, 104, 251),
            overlayTrackOff = Color.rgb(105, 116, 127),
            overlayDanger = Color.rgb(217, 59, 66),
        )

        val LIGHT = DiPlayPalette(
            background = Color.rgb(211, 219, 229),
            surface = 0xE5F6FBFF.toInt(),
            button = Color.rgb(242, 247, 252),
            settingsControlFill = Color.rgb(242, 247, 252),
            outline = Color.rgb(184, 190, 204),
            railSelected = 0x99FFFFFF.toInt(),
            railSelectedOutline = Color.TRANSPARENT,
            primaryText = 0xE5000000.toInt(),
            secondaryText = 0x80000000.toInt(),
            accent = Color.rgb(19, 104, 251),
            onAccent = Color.WHITE,
            accentText = Color.rgb(6, 77, 189),
            dialogSurface = Color.rgb(218, 226, 235),
            accentTrack = Color.rgb(19, 104, 251),
            trackOff = Color.rgb(184, 190, 204),
            warning = Color.rgb(122, 69, 0),
            success = Color.rgb(30, 101, 56),
            danger = Color.rgb(175, 19, 36),
            ripple = 0x0D000000,
            focusRing = Color.rgb(6, 77, 189),
            systemBar = Color.rgb(211, 219, 229),
            systemBarIconsAreDark = true,
            overlayBackground = Color.rgb(211, 219, 229),
            overlayPrimaryText = 0xE5000000.toInt(),
            overlaySecondaryText = 0x80000000.toInt(),
            overlayAccent = Color.rgb(19, 104, 251),
            overlayOnAccent = Color.WHITE,
            overlayAccentText = Color.rgb(6, 77, 189),
            overlayAccentTrack = Color.rgb(19, 104, 251),
            overlayTrackOff = Color.rgb(184, 190, 204),
            overlayDanger = Color.rgb(175, 19, 36),
        )
    }
}
