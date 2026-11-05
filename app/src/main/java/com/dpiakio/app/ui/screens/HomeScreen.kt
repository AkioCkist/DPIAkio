package com.dpiakio.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpiakio.app.core.Modesets
import com.dpiakio.app.core.Presets
import com.dpiakio.app.core.Stats
import com.dpiakio.app.service.DpiVpnService
import com.dpiakio.app.ui.MainViewModel
import com.dpiakio.app.ui.theme.Palette
import kotlinx.coroutines.delay

private enum class UiState { OFF, CONNECTING, ON, ERROR }

/**
 * Home. The rotating Earth lives in the app background layer (so glass surfaces refract it);
 * this screen only lays out controls on top of it.
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    contentPadding: PaddingValues,
    onToggle: () -> Unit,
    onOpenModes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val run by vm.runState.collectAsState()
    val ui = when (run.status) {
        DpiVpnService.Status.RUNNING -> UiState.ON
        DpiVpnService.Status.STARTING -> UiState.CONNECTING
        DpiVpnService.Status.ERROR -> UiState.ERROR
        DpiVpnService.Status.STOPPED -> UiState.OFF
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(bottom = contentPadding.calculateBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "DPIAKIO", modifier = Modifier.padding(top = 18.dp),
            color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Light, letterSpacing = 7.sp,
        )
        Spacer(Modifier.height(14.dp))
        StatusPill(ui, run.error)
        Spacer(Modifier.height(6.dp))
        Timer(ui == UiState.ON, run.since)
        NetworkRow(ui == UiState.ON, run.network)
        Spacer(Modifier.height(12.dp))
        ProfileChip(vm, onOpenModes)
        Spacer(Modifier.weight(1f))
        PowerButton(ui, onToggle)
        Spacer(Modifier.weight(0.6f))
        StatsSection(Modifier.padding(horizontal = 18.dp))
        Spacer(Modifier.height(12.dp))
    }
}

/** Status shown with icon + text + colour (never colour alone). Announced by TalkBack. */
@Composable
private fun StatusPill(ui: UiState, error: String) {
    val (icon, text, color) = when (ui) {
        UiState.ON -> Triple(Icons.Rounded.CheckCircle, "Đã bỏ qua DPI", Palette.Green)
        UiState.CONNECTING -> Triple(Icons.Rounded.Sync, "Đang kết nối…", Palette.Amber)
        UiState.ERROR -> Triple(Icons.Rounded.ErrorOutline, "Lỗi: $error", Palette.Red)
        UiState.OFF -> Triple(Icons.Rounded.RadioButtonUnchecked, "Chưa bảo vệ", Palette.TextDim)
    }
    Row(
        Modifier
            .padding(horizontal = 24.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** HH:MM with dimmed :SS, ticking only while connected. */
@Composable
private fun Timer(on: Boolean, since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on, since) {
        while (on) { now = System.currentTimeMillis(); delay(1000L - now % 1000L) }
    }
    val s = if (on) ((now - since).coerceAtLeast(0) / 1000) else 0L
    val text = buildAnnotatedString {
        append("%02d:%02d".format(s / 3600, s / 60 % 60))
        withStyle(SpanStyle(color = Palette.Cyan.copy(alpha = 0.85f))) { append(":%02d".format(s % 60)) }
    }
    Text(
        text,
        color = if (on) Palette.Text else Palette.Text.copy(alpha = 0.6f),
        fontSize = 60.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
        modifier = Modifier.semantics { contentDescription = "Thời gian bảo vệ ${s / 3600} giờ ${s / 60 % 60} phút" },
    )
}

@Composable
private fun NetworkRow(on: Boolean, network: String) {
    val lost = network.startsWith("Mất")
    val wifi = network.contains("Wi-Fi")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (on && !lost) Palette.Accent else Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x33FFFFFF)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (lost) Icons.Rounded.WifiOff else if (wifi || !on) Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt,
                null, tint = Color.White, modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(if (on) network.ifEmpty { "Mạng" } else "Ngoại tuyến", color = Palette.Text,
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProfileChip(vm: MainViewModel, onClick: () -> Unit) {
    val cfg by vm.config.collectAsState()
    val preset = Presets.byId(cfg.presetId)
    val title = preset?.title ?: (Modesets.info(cfg.modeset)?.title ?: "Cấu hình tuỳ chỉnh")
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .liquidGlass(LocalBackdrop.current, CircleShape, refraction = 14.dp, tint = Color(0x14020617), dispersion = true)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Đổi chế độ", onClick = onClick)
            .padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(CircleShape).background(Palette.Accent).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(if (cfg.modeset > 0) "-${cfg.modeset}" else "custom", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = Palette.Lavender, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Main action. Icon shows what tapping will do: power (start) when off, pause (stop) when
 * running. Glass core refracts the planet behind it; ring + glow are drawn in the draw phase.
 */
@Composable
private fun PowerButton(ui: UiState, onClick: () -> Unit) {
    val on = ui == UiState.ON
    val busy = ui == UiState.CONNECTING
    val level = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(ui) { level.animateTo(if (on) 1f else if (busy) 0.4f else 0f, tween(650, easing = FastOutSlowInEasing)) }
    val spin = if (busy) rememberInfiniteTransition(label = "spin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spin") else null
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(0.45f, Spring.StiffnessMediumLow), label = "press")
    val haptic = LocalHapticFeedback.current
    val ringColor = if (ui == UiState.ERROR) Palette.Red else Palette.Violet

    Box(
        Modifier
            .size(196.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                val l = level.value
                val r = size.minDimension / 2
                drawCircle(
                    Brush.radialGradient(
                        0f to ringColor.copy(alpha = 0.65f * l), 0.55f to Palette.Indigo.copy(alpha = 0.35f * l), 1f to Color.Transparent,
                        center = center, radius = r,
                    ), radius = r,
                )
                val ringR = r * 0.64f
                val w = 9.dp.toPx()
                val box = Size(ringR * 2, ringR * 2)
                val tl = Offset(center.x - ringR, center.y - ringR)
                drawCircle(Color(0x33FFFFFF), radius = ringR, style = Stroke(w))
                if (spin != null) {
                    rotate(spin.value) {
                        drawArc(Brush.sweepGradient(listOf(Color.Transparent, Palette.Lavender), center),
                            0f, 280f, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
                    }
                } else if (l > 0.01f) {
                    drawArc(
                        Brush.linearGradient(listOf(Color(0xFFD1C4FF), ringColor, Palette.Indigo),
                            start = Offset(center.x, center.y - ringR), end = Offset(center.x, center.y + ringR)),
                        -90f, 360f * l, false, tl, box, style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .size(110.dp)
                .liquidGlass(LocalBackdrop.current, CircleShape, refraction = 16.dp, tint = Color(0x59030820), dispersion = true)
                .clip(CircleShape)
                .clickable(interactionSource = source, indication = null, role = Role.Button,
                    onClickLabel = if (on) "Dừng" else "Bật") {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
                .semantics {
                    contentDescription = if (on || busy) "Dừng bỏ qua DPI" else "Bật bỏ qua DPI"
                    stateDescription = when (ui) {
                        UiState.ON -> "Đang hoạt động"; UiState.CONNECTING -> "Đang kết nối"
                        UiState.ERROR -> "Lỗi"; UiState.OFF -> "Đã tắt"
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = on || busy,
                transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                label = "icon",
            ) { running ->
                Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PowerSettingsNew, null,
                    tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Text(
                when (ui) { UiState.ON -> "Dừng"; UiState.CONNECTING -> "Huỷ"; else -> "Bật" },
                color = Palette.Lavender, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Isolated so the 1 Hz statistics updates recompose only this section. */
@Composable
private fun StatsSection(modifier: Modifier = Modifier) {
    val stats by Stats.snapshot.collectAsState()
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedTile(Modifier.weight(1f), Icons.Rounded.ArrowUpward, "Tải lên", formatSpeed(stats.upSpeed), formatBytes(stats.up))
            SpeedTile(Modifier.weight(1f), Icons.Rounded.ArrowDownward, "Tải xuống", formatSpeed(stats.downSpeed), formatBytes(stats.down))
        }
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat(Icons.Rounded.Hub, "${stats.tcp}", "Kết nối")
                MiniStat(Icons.Rounded.CallSplit, "${stats.bypassed}", "Đã lách")
                MiniStat(Icons.Rounded.Dns, "${stats.dns}", "DNS")
                MiniStat(Icons.Rounded.Block, "${stats.quic}", "QUIC")
            }
        }
    }
}

@Composable
private fun SpeedTile(modifier: Modifier, icon: ImageVector, label: String, speed: String, total: String) {
    GlassCard(modifier, highlight = true, refract = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x26FFFFFF)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Palette.Lavender, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = Palette.TextDim, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(10.dp))
        Text(speed, color = Palette.Text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("Tổng $total", color = Palette.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun MiniStat(icon: ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Palette.Cyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(value, color = Palette.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Text(label, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1)
    }
}
