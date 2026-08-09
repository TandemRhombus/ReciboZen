package com.recibozen.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** User-facing appearance choice, driven from Settings > Modo oscuro. */
enum class AppThemeMode { LIGHT, DARK, SYSTEM }

@Composable
fun resolveDarkTheme(mode: AppThemeMode): Boolean = when (mode) {
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun ReciboZenTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(themeMode)
    // No dynamic color: the brief calls for the brand palette (violet /
    // lime / green / amber) to stay fixed across devices, not follow
    // per-device Material You wallpaper extraction.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalRzExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RzTypography,
            shapes = RzShapes,
            content = content,
        )
    }
}

object RzTheme {
    val extendedColors: RzExtendedColors
        @Composable get() = LocalRzExtendedColors.current
}
