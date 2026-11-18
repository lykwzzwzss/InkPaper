package com.inkpaper.epd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PaperBgLight = Color(0xFFF7F6F1)
val PaperCardLight = Color(0xFFFFFFFF)
val PaperBorderLight = Color(0xFFE3E1D8)
val InkPrimaryLight = Color(0xFF176B61)
val InkTextLight = Color(0xFF18201C)
val InkSubtleLight = Color(0xFF5D6862)
val InkChipBgLight = Color(0xFFE9F2EF)

val PaperBgDark = Color(0xFF111513)
val PaperCardDark = Color(0xFF1B211E)
val PaperBorderDark = Color(0xFF2D3631)
val InkPrimaryDark = Color(0xFF5CE1C7)
val InkTextDark = Color(0xFFF2F1EA)
val InkSubtleDark = Color(0xFF9EA9A3)
val InkChipBgDark = Color(0xFF21332E)

private val LightColors = lightColorScheme(
    primary = InkPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = InkChipBgLight,
    onPrimaryContainer = Color(0xFF0E453E),
    secondaryContainer = InkChipBgLight,
    onSecondaryContainer = Color(0xFF0E453E),
    background = PaperBgLight,
    onBackground = InkTextLight,
    surface = PaperCardLight,
    onSurface = InkTextLight,
    surfaceVariant = Color(0xFFECEAE2),
    onSurfaceVariant = InkSubtleLight,
    outlineVariant = PaperBorderLight,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = InkPrimaryDark,
    onPrimary = Color(0xFF072923),
    primaryContainer = InkChipBgDark,
    onPrimaryContainer = Color(0xFF9CEFE0),
    secondaryContainer = InkChipBgDark,
    onSecondaryContainer = Color(0xFF9CEFE0),
    background = PaperBgDark,
    onBackground = InkTextDark,
    surface = PaperCardDark,
    onSurface = InkTextDark,
    surfaceVariant = Color(0xFF242C28),
    onSurfaceVariant = InkSubtleDark,
    outlineVariant = PaperBorderDark,
    error = Color(0xFFF2B8B5),
)

@Composable
fun InkPaperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
