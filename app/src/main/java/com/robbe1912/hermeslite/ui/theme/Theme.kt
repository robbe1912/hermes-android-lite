package com.robbe1912.hermeslite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFB45309)
private val OrangeDark = Color(0xFFFBBF24)

private val LightColors = lightColorScheme(primary = Orange, onPrimary = Color.White)
private val DarkColors = darkColorScheme(primary = OrangeDark)

/** The theme of the app: Material 3 with an orange accent, in light and dark. */
@Composable
fun HermesLiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
