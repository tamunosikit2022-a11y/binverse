package com.binverse.vision.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BinVerseGreen = Color(0xFF2ECC71)
val BinVerseRed = Color(0xFFE74C3C)
val BinVerseAmber = Color(0xFFF39C12)
val BinVerseBackground = Color(0xFF0D1117)
val BinVerseSurface = Color(0xFF161B22)

private val DarkColors = darkColorScheme(
    primary = BinVerseGreen,
    background = BinVerseBackground,
    surface = BinVerseSurface,
    error = BinVerseRed
)

@Composable
fun BinVerseVisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
