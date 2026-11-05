package com.dpiakio.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpiakio.app.ui.theme.Palette

private val CardShape = RoundedCornerShape(24.dp)
private val CardBg = Brush.verticalGradient(listOf(Color(0xFF10123A), Color(0xFF0A0B20)))
private val HighlightBorder = Brush.verticalGradient(listOf(Color(0xFF8F86FF), Color(0x337B5CFF)))
private val PlainBorder = Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x0AFFFFFF)))

/** Square frosted button (top bar of the home screen). */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, tint: Color = Palette.Text) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0x14FFFFFF))
            .border(1.dp, PlainBorder, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(CardShape)
            .background(if (highlight) Palette.CardTop else CardBg)
            .border(if (highlight) 1.5.dp else 1.dp, if (highlight) HighlightBorder else PlainBorder, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 4.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = Palette.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
            letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FlagTag(text: String, color: Color = Palette.Cyan) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
fun ToggleRow(title: String, flag: String?, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit,
              enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Palette.Text else Palette.TextFaint)
                if (flag != null) FlagTag(flag)
            }
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.Violet, checkedThumbColor = Color.White,
                uncheckedTrackColor = Palette.Surface, uncheckedBorderColor = Palette.Stroke,
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
    cursorColor = Palette.Cyan,
)

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) Palette.Violet.copy(alpha = 0.25f) else Palette.Surface2, label = "pill")
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) Palette.Violet else Palette.Stroke, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Palette.Text else Palette.TextDim, style = MaterialTheme.typography.labelLarge)
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
}

fun formatSpeed(b: Long): String = formatBytes(b) + "/s"
