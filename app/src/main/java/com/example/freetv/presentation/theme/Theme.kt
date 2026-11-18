package com.example.freetv.presentation.theme

import androidx.compose.runtime.Composable
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

/** FreeTV is dark-only: TV rooms are dark and a light theme causes glare. */
@Composable
fun FreeTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FreeTvColorScheme,
        typography = FreeTvTypography,
        content = content,
    )
}
