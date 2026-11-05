package com.dpiakio.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpiakio.app.ui.theme.Palette
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.Shadow

/** The app background layer that every glass surface samples (blur + refraction). */
val LocalBackdrop = compositionLocalOf<Backdrop> { emptyBackdrop() }

/** Barely-there tint: keeps the glass clear while giving text a little contrast. */
private val GlassTint = Color(0x80030820)        // ~50%: AA text contrast over the planet
private val GlassTintStrong = Color(0x73050B2E)
private val SoftShadow = Shadow(radius = 20.dp, color = Color(0x66000010))

/**
 * Clear Liquid Glass (Kyant0/AndroidLiquidGlass): no frosting by default — the backdrop is
 * only bent by the lens at the edges (plus optional colour dispersion) and lit by the
 * specular rim highlight, like water. A tiny [blurRadius] can be used where text sits on
 * busy content.
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    refraction: Dp = 18.dp,
    blurRadius: Dp = 0.dp,
    tint: Color = GlassTint,
    dispersion: Boolean = false,
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        if (blurRadius > 0.dp) blur(blurRadius.toPx())
        if (refraction > 0.dp) lens(refraction.toPx(), refraction.toPx() * 2.2f, depthEffect = true, chromaticAberration = dispersion)
    },
    shadow = { SoftShadow },
    onDrawSurface = { drawRect(tint) },
)

private val CardShape = RoundedCornerShape(26.dp)
private val StaticFill = Brush.verticalGradient(listOf(Color(0x1FB4C3FF), Color(0x0D7F8CFF), Color(0x14000010)))
private val StaticFillHi = Brush.verticalGradient(listOf(Color(0x2E8F7BFF), Color(0x146B7BFF), Color(0x1A000010)))
private val Rim = Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x14FFFFFF), Color(0x0AFFFFFF), Color(0x40B0C4FF)))
private val RimHi = Brush.linearGradient(listOf(Color(0xCCD9D2FF), Color(0x337C4DFF), Color(0x1A3D5AFE), Color(0x996EA8FF)))

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
    refract: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(0.6f, Spring.StiffnessMedium), label = "card")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (refract) Modifier.liquidGlass(backdrop, CardShape, refraction = if (highlight) 22.dp else 16.dp,
                    tint = if (highlight) GlassTintStrong else GlassTint, dispersion = highlight)
                // Static glass for long lists: clear fill + specular rim, no offscreen passes while scrolling.
                else Modifier.clip(CardShape).background(if (highlight) StaticFillHi else StaticFill)
                    .border(if (highlight) 1.5.dp else 1.dp, if (highlight) RimHi else Rim, CardShape)
            )
            .then(
                if (onClick != null) Modifier.clip(CardShape).clickable(interactionSource = source, indication = null,
                    role = Role.Button, onClick = onClick) else Modifier
            )
            .padding(16.dp),
        content = content,
    )
}

/** Square glass button, 48dp touch target, with label for TalkBack. */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, tint: Color = Palette.Text) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(0.45f, Spring.StiffnessMedium), label = "ib")
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(LocalBackdrop.current, shape, refraction = 14.dp, tint = Color(0x0DFFFFFF), dispersion = true)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(24.dp)) }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 4.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = Palette.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = Palette.TextDim,
            letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FlagTag(text: String, color: Color = Palette.Cyan) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
fun ToggleRow(title: String, flag: String?, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit,
              enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics { stateDescription = if (checked) "Bật" else "Tắt" }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Palette.Text else Palette.TextFaint,
                    modifier = Modifier.weight(1f, fill = false))
                if (flag != null) FlagTag(flag)
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
                modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.Indigo, checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0x22FFFFFF), uncheckedBorderColor = Palette.Stroke,
                uncheckedThumbColor = Palette.TextDim,
            ),
        )
    }
}

@Composable
fun NumberField(label: String, value: Int, onValue: (Int) -> Unit, modifier: Modifier = Modifier,
                range: IntRange = 0..65535, flag: String? = null) {
    var text by remember { mutableStateOf(if (value == 0) "" else value.toString()) }
    LaunchedEffect(value) {
        if ((text.toIntOrNull() ?: 0) != value) text = if (value == 0) "" else value.toString()
    }
    val bad = text.isNotEmpty() && (text.toIntOrNull() ?: -1) !in range
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            val f = s.filter { it.isDigit() }.take(5)
            text = f
            val v = f.toIntOrNull() ?: 0
            if (v in range || f.isEmpty()) onValue(if (f.isEmpty()) 0 else v)
        },
        label = { Text(if (flag != null) "$label  $flag" else label) },
        singleLine = true,
        isError = bad,
        supportingText = if (bad) ({ Text("Giá trị ${range.first}–${range.last}") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(),
    )
}

@Composable
fun TextFieldRow(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier,
                 placeholder: String = "", isError: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (text != value) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValue(it) },
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Palette.TextFaint) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError) ({ Text("Định dạng không hợp lệ") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(),
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Cyan,
    unfocusedBorderColor = Palette.Stroke,
    focusedLabelColor = Palette.Cyan,
    unfocusedLabelColor = Palette.TextDim,
    cursorColor = Palette.Cyan,
    focusedContainerColor = Color(0x14000000),
    unfocusedContainerColor = Color(0x14000000),
)

/** Selectable glass capsule (chips), 48dp min height. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fg by animateColorAsState(if (selected) Color.White else Palette.TextDim, label = "pill")
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Palette.Accent) else Modifier.background(Color(0x1FFFFFFF)))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = if (selected) "Đã chọn" else "Chưa chọn" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
}

fun formatSpeed(b: Long): String = formatBytes(b) + "/s"
