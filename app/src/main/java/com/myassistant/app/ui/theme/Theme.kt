package com.myassistant.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/** A quiet charcoal theme with one clear violet accent and high-contrast text. */
val UserBubble = Color(0xFF46436F)
val NovaCyan = Color(0xFFA8B4E8)
val NovaViolet = Color(0xFF9A8CFF)
val NovaPink = Color(0xFF8177C6)
val NovaNight = Color(0xFF0B0D12)

private val Dark = darkColorScheme(
    primary = NovaViolet,
    onPrimary = Color(0xFF17132B),
    primaryContainer = Color(0xFF29263E),
    onPrimaryContainer = Color(0xFFE7E3FF),
    secondary = NovaCyan,
    onSecondary = Color(0xFF1A1730),
    secondaryContainer = Color(0xFF302B45),
    onSecondaryContainer = Color(0xFFE8E3FF),
    tertiary = Color(0xFF73CDA0),
    onTertiary = Color(0xFF102319),
    tertiaryContainer = Color(0xFF1D3528),
    onTertiaryContainer = Color(0xFFD8F4E2),
    background = NovaNight,
    onBackground = Color(0xFFF1F2F5),
    surface = Color(0xFF101319),
    onSurface = Color(0xFFF1F2F5),
    surfaceVariant = Color(0xFF20242C),
    onSurfaceVariant = Color(0xFFC1C6D0),
    surfaceContainerLowest = Color(0xFF090B0F),
    surfaceContainerLow = Color(0xFF12151B),
    surfaceContainer = Color(0xFF171A21),
    surfaceContainerHigh = Color(0xFF1D2129),
    surfaceContainerHighest = Color(0xFF252A34),
    outline = Color(0xFF626875),
    outlineVariant = Color(0xFF343943),
    error = Color(0xFFFF8A91),
    errorContainer = Color(0xFF4A2025),
    onErrorContainer = Color(0xFFFFE1E3),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark,
        shapes = AppShapes,
        content = content,
    )
}

@Composable
fun AppBackdrop() {
    Box(
        Modifier
            .fillMaxSize()
            .background(NovaNight)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(NovaViolet.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.12f),
                        radius = size.width * 0.82f,
                    ),
                    radius = size.width * 0.82f,
                    center = Offset(size.width * 0.18f, size.height * 0.12f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(NovaViolet.copy(alpha = 0.055f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.88f),
                        radius = size.width * 0.72f,
                    ),
                    radius = size.width * 0.72f,
                    center = Offset(size.width * 0.92f, size.height * 0.88f),
                )
            },
    )
}
