package com.daftech.swapku.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark theme for SwapKu. Every Text in MainScreen also sets its colour
 * explicitly, because the default text colour on a custom dark
 * background can resolve to dark-on-dark on some themes (a bug seen in
 * an earlier project with EditText on a dark background). Do not rely
 * on the theme alone for text contrast.
 */
private val SwapKuDarkColors = darkColorScheme(
    primary = Color(0xFF4CAF50),
    onPrimary = Color.White,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    error = Color(0xFFFF6B6B),
    onError = Color.White
)

@Composable
fun SwapKuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SwapKuDarkColors,
        content = content
    )
}
