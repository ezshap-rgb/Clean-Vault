package com.cleanvault.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.cleanvault.app.R

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

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
