package com.example.freetv.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val FreeTvColorScheme = darkColorScheme(
    primary = FreeTvPrimary,
    onPrimary = FreeTvOnPrimary,
    secondary = FreeTvSecondary,
    onSecondary = FreeTvOnSecondary,
    background = FreeTvBackground,
    onBackground = FreeTvOnBackground,
    surface = FreeTvSurface,
    onSurface = FreeTvOnBackground,
    surfaceVariant = FreeTvSurfaceVariant,
    onSurfaceVariant = FreeTvOnSurfaceVariant,
    error = FreeTvError,
    onError = FreeTvOnError,
    border = FreeTvFocusBorder,
)

/**
 * FreeTV is dark-only: TV rooms are dark and a light theme causes glare.
 * [pureBlack] switches to a true-black background (OLED TVs).
 */
@Composable
fun FreeTvTheme(pureBlack: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (pureBlack) {
            FreeTvColorScheme.copy(background = Color.Black, surface = Color(0xFF0E0E0E))
        } else {
            FreeTvColorScheme
        },
        typography = FreeTvTypography,
        content = content,
    )
}
