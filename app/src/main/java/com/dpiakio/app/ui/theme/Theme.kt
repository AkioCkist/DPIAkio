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
    val Bg = Color(0xFF01030E)
    val BgNavy = Color(0xFF040A2C)
    val Surface = Color(0xFF0A1036)
    val Surface2 = Color(0xFF111A4A)
    val Glass = Color(0x1A9DB0FF)
    val Stroke = Color(0x29FFFFFF)
    val Indigo = Color(0xFF3D5AFE)       // electric blue
    val Cyan = Color(0xFF7FA2FF)         // accent for icons / tags
    val Violet = Color(0xFF7C4DFF)
    val Pink = Color(0xFFB388FF)
    val Lavender = Color(0xFFC9D4FF)
    val Green = Color(0xFF5EEAD4)
    val Amber = Color(0xFFFFC94D)
    val Red = Color(0xFFFF6B81)
    val Text = Color(0xFFF5F7FF)
    val TextDim = Color(0xFFA9B2DD)      // 8.9:1 on Bg
    val TextFaint = Color(0xFF8189B8)    // 5.6:1 on Bg (WCAG AA for body text)

    val Accent = Brush.linearGradient(listOf(Color(0xFF3D5AFE), Color(0xFF7C4DFF)))
    val AccentHot = Brush.linearGradient(listOf(Color(0xFF536DFE), Color(0xFFB388FF)))
    val CardTop = Brush.verticalGradient(listOf(Color(0xFF1B2470), Color(0xFF0B1240), Color(0xFF080D30)))
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
