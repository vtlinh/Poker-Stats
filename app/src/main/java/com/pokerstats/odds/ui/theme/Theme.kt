package com.pokerstats.odds.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = InkBlack,
    secondary = FeltGreen,
    onSecondary = CardWhite,
    background = TableFelt,
    onBackground = CardWhite,
    surface = FeltGreenDark,
    onSurface = CardWhite,
    error = ChipRed,
)

private val LightColors = lightColorScheme(
    primary = FeltGreen,
    onPrimary = CardWhite,
    secondary = Gold,
    onSecondary = InkBlack,
    background = Color(0xFFF1F4F0),
    onBackground = InkBlack,
    surface = CardWhite,
    onSurface = InkBlack,
    error = ChipRed,
)

@Composable
fun PokerStatsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = PokerTypography,
        content = content,
    )
}
