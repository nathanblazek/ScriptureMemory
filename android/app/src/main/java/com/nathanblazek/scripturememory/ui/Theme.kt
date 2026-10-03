package com.nathanblazek.scripturememory.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Burgundy and gold, after the app icon.
private val LightColors = lightColorScheme(
    primary = Color(0xFF7A1E24),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD7),
    onPrimaryContainer = Color(0xFF410006),
    secondary = Color(0xFF8A6A1C),
    secondaryContainer = Color(0xFFF7E1A6),
    onSecondaryContainer = Color(0xFF2B2000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB3AE),
    onPrimary = Color(0xFF5F1117),
    primaryContainer = Color(0xFF7A1E24),
    onPrimaryContainer = Color(0xFFFFDAD7),
    secondary = Color(0xFFE6C66F),
    secondaryContainer = Color(0xFF5A4500),
    onSecondaryContainer = Color(0xFFF7E1A6),
)

@Composable
fun ScriptureMemoryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Colors for revealed words, matching the Windows app's success / caution colors. */
object WordColors {
    val spoken: Color
        @Composable
        get() = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

    val missed: Color
        @Composable
        get() = if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFFB26A00)
}
