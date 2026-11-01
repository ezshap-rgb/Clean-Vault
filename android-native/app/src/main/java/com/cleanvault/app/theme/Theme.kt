package com.cleanvault.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentColor,
    secondary = AccentMid,
    tertiary = AmberGold,
    background = BgBase,
    surface = SurfaceColor,
    onPrimary = TextMain,
    onSecondary = TextMain,
    onBackground = TextMain,
    onSurface = TextMain
)

@Composable
fun CleanVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

