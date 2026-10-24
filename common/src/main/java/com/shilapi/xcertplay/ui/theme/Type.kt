package com.shilapi.xcertplay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal fun flymeTypography(compact: Boolean): Typography {
    fun role(full: Int, small: Int, line: Int, smallLine: Int, medium: Boolean = false) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
        fontSize = (if (compact) small else full).sp,
        lineHeight = (if (compact) smallLine else line).sp,
    )
    return Typography(
        titleLarge = role(28,22,40,32,true),
        titleMedium = role(26,20,36,28,true),
        bodyLarge = role(26,20,36,28),
        bodyMedium = role(22,16,30,24),
        labelLarge = role(24,18,34,26,true),
    )
}
