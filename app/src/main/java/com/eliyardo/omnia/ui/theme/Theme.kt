package com.eliyardo.omnia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dedicated Tunnel Status Accents
val StatusConnectedGreen = Color(0xFF00E676)
val StatusConnectingAmber = Color(0xFFFFB300)
val StatusErrorRed = Color(0xFFFF3B30)

// Dominant AMOLED Black palette (Black, White, Grey with Green/Red status accents)
private val DarkAmoledColorScheme = darkColorScheme(
    background = Color(0xFF000000),          // Pure AMOLED Black
    surface = Color(0xFF0A0A0A),             // Deep Dark Card Surface
    surfaceVariant = Color(0xFF141414),      // Elevated Surface
    surfaceTint = Color.Transparent,         // CRUCIAL: Never tint elevated surfaces with accent color!
    onBackground = Color(0xFFFFFFFF),        // Crisp White
    onSurface = Color(0xFFF0F0F0),           // Crisp Off-White
    onSurfaceVariant = Color(0xFF8E8E93),    // Subtle Grey
    outline = Color(0xFF222222),             // Subtle Border
    outlineVariant = Color(0xFF181818),

    primary = Color(0xFFFFFFFF),             // Monochrome White Primary
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1C1C1E),
    onPrimaryContainer = Color(0xFFFFFFFF),

    secondary = Color(0xFF8E8E93),           // Neutral Grey (Idle / Disconnected)
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1C1C1E),
    onSecondaryContainer = Color(0xFFE5E5EA),

    tertiary = Color(0xFFFFB300),            // Amber (Connecting)
    onTertiary = Color(0xFF000000),

    error = Color(0xFFFF3B30),               // Vivid Red (Problem / Error)
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF2C0B0E),
    onErrorContainer = Color(0xFFFF8983)
)

// Light Palette (White replaces AMOLED Black, Black replaces White)
private val LightMonochromeColorScheme = lightColorScheme(
    background = Color(0xFFFFFFFF),          // Clean White
    surface = Color(0xFFF7F7F7),             // Elevated Light Card Surface
    surfaceVariant = Color(0xFFEEEEEE),
    surfaceTint = Color.Transparent,         // Never tint elevated surfaces
    onBackground = Color(0xFF000000),        // Crisp Black
    onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF666666),    // Medium Grey
    outline = Color(0xFFD6D6D6),
    outlineVariant = Color(0xFFE5E5E5),

    primary = Color(0xFF000000),             // Monochrome Black Primary
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E0E0),
    onPrimaryContainer = Color(0xFF000000),

    secondary = Color(0xFF666666),           // Neutral Grey (Idle / Disconnected)
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF1C1C1E),

    tertiary = Color(0xFFF57C00),            // Amber (Connecting)
    onTertiary = Color(0xFFFFFFFF),

    error = Color(0xFFD32F2F),               // Vivid Red (Problem / Error)
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFB71C1C)
)

@Composable
fun OmniaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkAmoledColorScheme else LightMonochromeColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
