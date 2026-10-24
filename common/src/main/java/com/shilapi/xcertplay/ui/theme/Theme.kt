package com.shilapi.xcertplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.FlymeAutoStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.shilapi.xcertplay.DiPlayPalette

private fun color(value: Int) = Color(value)

private fun darkScheme(palette: DiPlayPalette) = darkColorScheme(
    primary = color(palette.accent),
    onPrimary = color(palette.onAccent),
    background = color(palette.background),
    onBackground = color(palette.primaryText),
    surface = color(palette.surface),
    onSurface = color(palette.primaryText),
    secondary = color(palette.accent),
    onSecondary = color(palette.onAccent),
    tertiary = color(palette.accent),
    onTertiary = color(palette.onAccent),
    primaryContainer = color(palette.railSelected),
    onPrimaryContainer = color(palette.primaryText),
    secondaryContainer = color(palette.button),
    onSecondaryContainer = color(palette.primaryText),
    surfaceVariant = color(palette.button),
    onSurfaceVariant = color(palette.secondaryText),
    outline = color(palette.outline),
    error = color(palette.danger),
)

private fun lightScheme(palette: DiPlayPalette) = lightColorScheme(
    primary = color(palette.accent),
    onPrimary = color(palette.onAccent),
    background = color(palette.background),
    onBackground = color(palette.primaryText),
    surface = color(palette.surface),
    onSurface = color(palette.primaryText),
    secondary = color(palette.accent),
    onSecondary = color(palette.onAccent),
    tertiary = color(palette.accent),
    onTertiary = color(palette.onAccent),
    primaryContainer = color(palette.railSelected),
    onPrimaryContainer = color(palette.primaryText),
    secondaryContainer = color(palette.button),
    onSecondaryContainer = color(palette.primaryText),
    surfaceVariant = color(palette.button),
    onSurfaceVariant = color(palette.secondaryText),
    outline = color(palette.outline),
    error = color(palette.danger),
)

@Composable
fun XcertplayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = DiPlayPalette.of(darkTheme)
    val colorScheme = if (darkTheme) darkScheme(palette) else lightScheme(palette)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = flymeTypography(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600),
        shapes = Shapes(
            small = RoundedCornerShape((FlymeAutoStyle.CARD_CORNER_DP / 2).dp),
            medium = RoundedCornerShape(FlymeAutoStyle.CARD_CORNER_DP.dp),
            large = RoundedCornerShape(FlymeAutoStyle.CARD_CORNER_DP.dp),
        ),
        content = content
    )
}
