package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = QuantBlueDark,
    onPrimary = QuantDarkBackground,
    primaryContainer = QuantDarkSurfaceSoft,
    onPrimaryContainer = QuantDarkInk,
    secondary = QuantDarkMuted,
    secondaryContainer = QuantDarkSurfaceSoft,
    onSecondaryContainer = QuantDarkInk,
    tertiary = QuantTertiaryAccent,
    background = QuantDarkBackground,
    onBackground = QuantDarkInk,
    surface = QuantDarkSurface,
    onSurface = QuantDarkInk,
    surfaceVariant = QuantDarkSurfaceSoft,
    onSurfaceVariant = QuantDarkMuted,
    outline = QuantDarkLine,
    error = QuantDanger,
    errorContainer = QuantDanger.copy(alpha = 0.14f),
    onErrorContainer = QuantDanger
)

private val LightColorScheme = lightColorScheme(
    primary = QuantBlue,
    onPrimary = Color.White,
    primaryContainer = QuantBlueSoft,
    onPrimaryContainer = QuantBlue,
    secondary = QuantMuted,
    secondaryContainer = QuantSurfaceHigh,
    onSecondaryContainer = QuantInk,
    tertiary = QuantTertiaryAccent,
    background = QuantBackground,
    onBackground = QuantInk,
    surface = QuantSurface,
    onSurface = QuantInk,
    surfaceVariant = QuantSurfaceHigh,
    onSurfaceVariant = QuantMuted,
    outline = QuantLine,
    error = QuantDanger,
    errorContainer = QuantDanger.copy(alpha = 0.10f),
    onErrorContainer = QuantDanger
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
