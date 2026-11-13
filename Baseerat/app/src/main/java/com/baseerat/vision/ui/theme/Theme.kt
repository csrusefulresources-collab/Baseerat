package com.baseerat.vision.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color

private val Primary = Color(0xFF1B4F72)
private val PrimaryDark = Color(0xFF0E2F44)
private val Secondary = Color(0xFFB9770E)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E6F1),
    onPrimaryContainer = PrimaryDark,
    secondary = Secondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDEBD0),
    onSecondaryContainer = Color(0xFF5D3A00),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A252F),
    surface = Color.White,
    onSurface = Color(0xFF1A252F),
    surfaceVariant = Color(0xFFE8EEF4),
    onSurfaceVariant = Color(0xFF4A5C6A),
    error = Color(0xFFC0392B),
    outline = Color(0xFF6F8294)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DADE2),
    onPrimary = Color(0xFF0A1929),
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Color(0xFFD4E6F1),
    secondary = Color(0xFFF5B041),
    onSecondary = Color(0xFF3D2A00),
    secondaryContainer = Color(0xFF5D3A00),
    onSecondaryContainer = Color(0xFFFDEBD0),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE8EEF4),
    surface = Color(0xFF1A2332),
    onSurface = Color(0xFFE8EEF4),
    surfaceVariant = Color(0xFF2A3544),
    onSurfaceVariant = Color(0xFFB0C0D0),
    error = Color(0xFFFF7B6B),
    outline = Color(0xFF8FA3B5)
)

/** Maximum contrast: black background, white text, yellow accents. */
private val HighContrastColors = darkColorScheme(
    primary = Color(0xFFFFFF00),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF222200),
    onPrimaryContainer = Color(0xFFFFFF00),
    secondary = Color(0xFF00FFFF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF002222),
    onSecondaryContainer = Color(0xFF00FFFF),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    error = Color(0xFFFF6B6B),
    outline = Color.White
)

@Composable
fun BaseeratTheme(
    highContrast: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = when {
            highContrast -> HighContrastColors
            darkTheme -> DarkColors
            else -> LightColors
        },
        content = content
    )
}

/** Theme that follows the high-contrast setting; used by activities other than MainActivity. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.baseerat.vision.BaseeratApp
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)
    BaseeratTheme(highContrast = settings.highContrast, content = content)
}
