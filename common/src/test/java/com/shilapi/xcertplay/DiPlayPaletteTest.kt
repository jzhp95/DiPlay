package com.shilapi.xcertplay

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DiPlayPaletteTest {
    @Test fun paletteKeepsBrandFillSeparateFromReadableTextAndDialogRoles() {
        assertEquals(Color.rgb(32, 39, 46), DiPlayPalette.DARK.background)
        assertEquals(Color.rgb(211, 219, 229), DiPlayPalette.LIGHT.background)
        for (palette in listOf(DiPlayPalette.LIGHT, DiPlayPalette.DARK)) {
            assertEquals(Color.rgb(19, 104, 251), palette.accent)
            assertEquals(Color.WHITE, palette.onAccent)
            assertEquals(palette.accent, palette.overlayAccent)
            assertEquals(Color.WHITE, palette.overlayOnAccent)
            assertEquals(255, Color.alpha(palette.dialogSurface))
        }
        assertEquals(Color.rgb(218, 226, 235), DiPlayPalette.LIGHT.dialogSurface)
        assertEquals(Color.rgb(36, 40, 46), DiPlayPalette.DARK.dialogSurface)
        assertFalse(DiPlayPalette.DARK.systemBarIconsAreDark)
    }

    @Test fun waitingScreenDelegationKeepsItsExactLightAndDarkColors() {
        assertEquals(
            WaitingScreenColors(Color.rgb(32, 39, 46), 0xE5FFFFFF.toInt(), 0x80FFFFFF.toInt()),
            WaitingScreenColors.of(night = true),
        )
        assertEquals(
            WaitingScreenColors(Color.rgb(211, 219, 229), 0xE5000000.toInt(), 0x80000000.toInt()),
            WaitingScreenColors.of(night = false),
        )
    }

    // Flyme C1 supporting copy uses the verified 50% ink; primary body copy keeps 4.5:1.
    @Test fun semanticRolesRetainReadablePrimaryAndNativeSupportingContrast() {
        for (palette in listOf(DiPlayPalette.DARK, DiPlayPalette.LIGHT)) {
            assertContrastAtLeast(palette.primaryText, palette.background, 4.5)
            assertContrastAtLeast(palette.secondaryText, palette.background, 3.0)
            assertContrastAtLeast(palette.accentText, palette.background, 4.5)
            assertContrastAtLeast(palette.accent, palette.background, 3.0)
            val surface = androidx.core.graphics.ColorUtils.compositeColors(palette.surface, palette.background)
            assertContrastAtLeast(palette.accentText, surface, 4.5)
            assertContrastAtLeast(palette.primaryText, surface, 4.5)
            assertContrastAtLeast(palette.secondaryText, surface, 3.0)
            assertContrastAtLeast(palette.onAccent, palette.accent, 4.5)
            assertContrastAtLeast(palette.warning, palette.background, 4.5)
            assertContrastAtLeast(palette.success, palette.background, 4.5)
            // The legacy dark danger role is used for graphical/status emphasis, not body text.
            assertContrastAtLeast(palette.danger, palette.background, 3.0)
            assertContrastAtLeast(palette.overlayPrimaryText, palette.overlayBackground, 4.5)
            assertContrastAtLeast(palette.overlaySecondaryText, palette.overlayBackground, 3.0)
            assertContrastAtLeast(palette.overlayAccentText, palette.overlayBackground, 4.5)
            assertContrastAtLeast(palette.overlayOnAccent, palette.overlayAccent, 4.5)
            assertContrastAtLeast(palette.overlayDanger, palette.overlayBackground, 3.0)
        }
        // State is also conveyed by thumb position. Verify the checked content against its
        // fill, rather than requiring unrelated on/off tracks to act as foreground text.
        assertContrastAtLeast(Color.WHITE, DiPlayPalette.LIGHT.accentTrack, 4.5)
        assertContrastAtLeast(Color.WHITE, DiPlayPalette.DARK.accentTrack, 4.5)
        assertTrue(DiPlayPalette.LIGHT.systemBarIconsAreDark)
    }

    private fun assertContrastAtLeast(foreground: Int, background: Int, minimum: Double) {
        val visible = androidx.core.graphics.ColorUtils.compositeColors(foreground, background)
        val foregroundLuminance = luminance(visible)
        val backgroundLuminance = luminance(background)
        val ratio = (max(foregroundLuminance, backgroundLuminance) + 0.05) /
            (min(foregroundLuminance, backgroundLuminance) + 0.05)
        assertTrue("Expected contrast >= $minimum, was $ratio", ratio >= minimum)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }
}
