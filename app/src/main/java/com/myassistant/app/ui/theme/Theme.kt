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

/** Google Sans (Latin letters and numbers); Arabic falls back to the phone's own Arabic font, like Google's apps. */
private val GoogleSans = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.myassistant.app.R.font.google_sans_regular, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.myassistant.app.R.font.google_sans_medium, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(com.myassistant.app.R.font.google_sans_medium, androidx.compose.ui.text.font.FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(com.myassistant.app.R.font.google_sans_bold, androidx.compose.ui.text.font.FontWeight.Bold),
)

private val AppTypography = androidx.compose.material3.Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.gs() = copy(fontFamily = GoogleSans)
    t.copy(
        displayLarge = t.displayLarge.gs(), displayMedium = t.displayMedium.gs(), displaySmall = t.displaySmall.gs(),
        headlineLarge = t.headlineLarge.gs(), headlineMedium = t.headlineMedium.gs(), headlineSmall = t.headlineSmall.gs(),
        titleLarge = t.titleLarge.gs(), titleMedium = t.titleMedium.gs(), titleSmall = t.titleSmall.gs(),
        bodyLarge = t.bodyLarge.gs(), bodyMedium = t.bodyMedium.gs(), bodySmall = t.bodySmall.gs(),
        labelLarge = t.labelLarge.gs(), labelMedium = t.labelMedium.gs(), labelSmall = t.labelSmall.gs(),
    )
}

/** Rounded, soft shapes like Google's own apps. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Calm, Google-style theme: colours come from the phone's wallpaper (Material You), light or dark
 * like the system, and Material 3's standard (calm) motion.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = when {
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (dark) androidx.compose.material3.dynamicDarkColorScheme(ctx) else androidx.compose.material3.dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> androidx.compose.material3.lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colors,
        motionScheme = androidx.compose.material3.MotionScheme.standard(),
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}

/** Plain app background (no glow). */
@Composable
fun AppBackdrop() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
}
