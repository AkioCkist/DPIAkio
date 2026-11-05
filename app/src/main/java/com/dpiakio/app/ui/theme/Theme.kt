package com.dpiakio.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Deep navy + indigo/violet glow palette. */
object Palette {
    val Bg = Color(0xFF03040B)
    val BgNavy = Color(0xFF070A1F)
    val Surface = Color(0xFF0B0D22)
    val Surface2 = Color(0xFF12153A)
    val Glass = Color(0x0FFFFFFF)
    val Stroke = Color(0x1AFFFFFF)
    val Indigo = Color(0xFF4F46E5)
    val Cyan = Color(0xFF8F95FF)        // periwinkle accent (kept name for screens)
    val Violet = Color(0xFF7B5CFF)
    val Pink = Color(0xFFB79CFF)
    val Lavender = Color(0xFFC7CBFF)
    val Green = Color(0xFF6EE7B7)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFFB7185)
    val Text = Color(0xFFF4F5FF)
    val TextDim = Color(0xFF8C90B8)
    val TextFaint = Color(0xFF555A82)

    val Accent = Brush.linearGradient(listOf(Indigo, Violet))
    val AccentHot = Brush.linearGradient(listOf(Color(0xFF6D5BFF), Color(0xFFA78BFA)))
    val CardTop = Brush.verticalGradient(listOf(Color(0xFF1A1950), Color(0xFF0C0E26), Color(0xFF0A0B1E)))
}

private val scheme = darkColorScheme(
    primary = Palette.Violet,
    onPrimary = Color.White,
    secondary = Palette.Cyan,
    onSecondary = Color.White,
    tertiary = Palette.Green,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface2,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface2,
    surfaceContainerHighest = Color(0xFF1A1E48),
    outline = Color(0x33FFFFFF),
    outlineVariant = Palette.Stroke,
    error = Palette.Red,
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun DpiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
