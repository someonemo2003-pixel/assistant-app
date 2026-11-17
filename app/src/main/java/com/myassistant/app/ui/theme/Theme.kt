package com.myassistant.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Strong indigo with white text: readable in light and dark mode. */
val UserBubble = Color(0xFF6D5BF0)

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFF059669),
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F9),
    surfaceContainer = Color(0xFFEEF2F7),
    surfaceContainerHigh = Color(0xFFE8EDF4),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

/** Night-cosmic palette taken from the Nova icon: deep navy, violet glow, cyan → violet → pink. */
val NovaCyan = Color(0xFF22D3EE)
val NovaViolet = Color(0xFF8B5CF6)
val NovaPink = Color(0xFFE879F9)
val NovaNight = Color(0xFF05041A)

private val Dark = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF14093A),
    primaryContainer = Color(0xFF2A1F66),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = NovaCyan,
    onSecondary = Color(0xFF042F3A),
    secondaryContainer = Color(0xFF0E3A52),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF123C46),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = NovaNight,
    onBackground = Color(0xFFE8E6F7),
    surface = NovaNight,
    onSurface = Color(0xFFE8E6F7),
    surfaceVariant = Color(0xFF1C1946),
    onSurfaceVariant = Color(0xFF9D9BC4),
    surfaceContainerLowest = Color(0xFF03020F),
    surfaceContainerLow = Color(0xFF0B0926),
    surfaceContainer = Color(0xFF100E30),
    surfaceContainerHigh = Color(0xFF16133C),
    surfaceContainerHighest = Color(0xFF1E1A4D),
    outline = Color(0xFF3B3778),
    outlineVariant = Color(0xFF221F52),
    error = Color(0xFFFB7185),
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFFE4E6),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark, // Nova is always night-cosmic
        shapes = AppShapes,
        content = content,
    )
}
