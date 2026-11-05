package com.dpiakio.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpiakio.app.core.Modesets
import com.dpiakio.app.core.Presets
import com.dpiakio.app.core.Stats
import com.dpiakio.app.service.DpiVpnService
import com.dpiakio.app.ui.MainViewModel
import com.dpiakio.app.ui.theme.Mono
import com.dpiakio.app.ui.theme.Palette
import kotlinx.coroutines.delay

private val TopGlow = Brush.radialGradient(
    listOf(Color(0x664F46E5), Color(0x223B2AA0), Color.Transparent),
    center = Offset(540f, -120f), radius = 1100f,
)

@Composable
fun HomeScreen(
    vm: MainViewModel,
    contentPadding: PaddingValues,
    onToggle: () -> Unit,
    onOpenModes: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val run by vm.runState.collectAsState()
    val on = run.status == DpiVpnService.Status.RUNNING
    val busy = run.status == DpiVpnService.Status.STARTING

    Box(Modifier.fillMaxSize().background(TopGlow)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(onOpenSettings, onOpenModes)
            Spacer(Modifier.height(26.dp))
            Text(
                when (run.status) {
                    DpiVpnService.Status.RUNNING -> "Kết nối đã được bảo vệ"
                    DpiVpnService.Status.STARTING -> "Đang khởi động…"
                    DpiVpnService.Status.ERROR -> "Lỗi: ${run.error}"
                    DpiVpnService.Status.STOPPED -> "Chưa bỏ qua DPI"
                },
                color = if (run.status == DpiVpnService.Status.ERROR) Palette.Red else Palette.TextDim,
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(6.dp))
            Timer(on, run.since)
            Spacer(Modifier.height(10.dp))
            NetworkRow(on, run.network)
            Spacer(Modifier.height(14.dp))
            ProfileChip(vm, onOpenModes)

            // planet + connect button
            Box(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.TopCenter) {
                DottedGlobe(active = on, modifier = Modifier.fillMaxSize())
                ConnectButton(on, busy, onToggle, Modifier.padding(top = 8.dp))
            }

            Column(Modifier.padding(horizontal = 20.dp).offset(y = (-40).dp)) {
                StatsSection()
                Spacer(Modifier.height(12.dp))
                CommandCard(vm)
            }
        }
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit, onModes: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.Rounded.Tune, "Tuỳ chỉnh", onSettings)
        Text(
            "DPIAKIO",
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Light, letterSpacing = 7.sp,
        )
        GlassIconButton(Icons.Rounded.Layers, "Chế độ", onModes, tint = Palette.Amber)
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
        withStyle(SpanStyle(color = Palette.TextDim.copy(alpha = 0.75f))) { append(":%02d".format(s % 60)) }
    }
    Text(
        text, color = if (on) Palette.Text else Palette.Text.copy(alpha = 0.55f),
        fontSize = 58.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
    )
}

@Composable
private fun NetworkRow(on: Boolean, network: String) {
    val lost = network.startsWith("Mất")
    val wifi = network.contains("Wi-Fi")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (on && !lost) Palette.Accent else SolidGlass),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (lost) Icons.Rounded.WifiOff else if (wifi || !on) Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt,
                null, tint = Color.White, modifier = Modifier.size(13.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(if (on) network.ifEmpty { "Mạng" } else "Ngoại tuyến", color = Palette.Text,
            style = MaterialTheme.typography.titleMedium)
    }
}

private val SolidGlass = Brush.linearGradient(listOf(Color(0x22FFFFFF), Color(0x22FFFFFF)))

@Composable
private fun ProfileChip(vm: MainViewModel, onClick: () -> Unit) {
    val cfg by vm.config.collectAsState()
    val preset = Presets.byId(cfg.presetId)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x12FFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 5.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(Palette.Accent).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(if (cfg.modeset > 0) "-${cfg.modeset}" else "custom", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(preset?.title ?: (Modesets.info(cfg.modeset)?.title ?: "Cấu hình tuỳ chỉnh"),
            color = Palette.Lavender, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Round button with gradient ring. All animated values are read inside draw / graphicsLayer
 * lambdas so the composable itself never recomposes per frame.
 */
@Composable
private fun ConnectButton(on: Boolean, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val level = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on, busy) { level.animateTo(if (on) 1f else if (busy) 0.5f else 0f, tween(700, easing = FastOutSlowInEasing)) }
    // Infinite animations exist only while connecting / connected: idle screen draws nothing per frame.
    val spin = if (busy) rememberInfiniteTransition(label = "spin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin") else null
    val breathe = if (on) rememberInfiniteTransition(label = "breathe")
        .animateFloat(0.85f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe") else null
    val press = remember { MutableInteractionSource() }

    Box(modifier.size(176.dp), contentAlignment = Alignment.Center) {
        // glow + ring
        Box(
            Modifier.fillMaxSize().drawBehind {
                val l = level.value
                val r = size.minDimension / 2
                val glow = breathe?.value ?: 1f
                drawCircle(
                    Brush.radialGradient(
                        listOf(Palette.Violet.copy(alpha = 0.55f * l * glow), Palette.Indigo.copy(alpha = 0.18f * l), Color.Transparent),
                        center = center, radius = r,
                    ), radius = r,
                )
                val ringR = r * 0.66f
                val w = 7.dp.toPx()
                drawCircle(Color(0xFF0B0C24), radius = ringR + w)
                drawCircle(Color(0x22FFFFFF), radius = ringR, style = Stroke(w))
                if (busy) {
                    rotate(spin?.value ?: 0f) {
                        drawArc(
                            Brush.sweepGradient(listOf(Color.Transparent, Palette.Lavender), center),
                            0f, 270f, false, topLeft = Offset(center.x - ringR, center.y - ringR),
                            size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2), style = Stroke(w, cap = StrokeCap.Round),
                        )
                    }
                } else if (l > 0.01f) {
                    drawArc(
                        Brush.linearGradient(listOf(Color(0xFFC4B5FD), Palette.Violet, Palette.Indigo),
                            start = Offset(center.x, center.y - ringR), end = Offset(center.x, center.y + ringR)),
                        -90f, 360f * l, false, topLeft = Offset(center.x - ringR, center.y - ringR),
                        size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2), style = Stroke(w, cap = StrokeCap.Round),
                    )
                }
            }
        )
        Column(
            Modifier
                .size(116.dp)
                .clip(CircleShape)
                .clickable(interactionSource = press, indication = null, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Bật/Tắt", tint = Color.White,
                modifier = Modifier.size(44.dp).graphicsLayer { alpha = 0.6f + 0.4f * level.value })
            Text(
                when { on -> "Đã kết nối"; busy -> "Đang nối…"; else -> "Kết nối" },
                color = Palette.TextDim, fontSize = 12.sp,
            )
        }
    }
}

/** Isolated so the 1 Hz statistics updates recompose only this section. */
@Composable
private fun StatsSection() {
    val stats by Stats.snapshot.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SpeedTile(Modifier.weight(1f), Icons.Rounded.ArrowUpward, "Tải lên", formatSpeed(stats.upSpeed), formatBytes(stats.up))
        SpeedTile(Modifier.weight(1f), Icons.Rounded.ArrowDownward, "Tải xuống", formatSpeed(stats.downSpeed), formatBytes(stats.down))
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniStat(Modifier.weight(1f), Icons.Rounded.Hub, "${stats.tcp}", "Kết nối")
        MiniStat(Modifier.weight(1f), Icons.Rounded.CallSplit, "${stats.bypassed}", "Đã lách")
        MiniStat(Modifier.weight(1f), Icons.Rounded.Dns, "${stats.dns}", "DNS")
        MiniStat(Modifier.weight(1f), Icons.Rounded.Block, "${stats.quic}", "QUIC")
    }
}

@Composable
private fun SpeedTile(modifier: Modifier, icon: ImageVector, label: String, speed: String, total: String) {
    GlassCard(modifier, highlight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Palette.Lavender, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(speed, color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("Tổng $total", color = Palette.TextFaint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MiniStat(modifier: Modifier, icon: ImageVector, value: String, label: String) {
    GlassCard(modifier) {
        Icon(icon, null, tint = Palette.Cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(8.dp))
        Text(value, color = Palette.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(label, color = Palette.TextFaint, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun CommandCard(vm: MainViewModel) {
    val cfg by vm.config.collectAsState()
    val cmd = remember(cfg) { vm.commandLine(cfg) }
    val clip = LocalClipboardManager.current
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Terminal, null, tint = Palette.Violet, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Lệnh tương đương trên PC", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { clip.setText(AnnotatedString(cmd)) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.ContentCopy, "Sao chép", tint = Palette.TextDim, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(cmd, style = Mono, color = Palette.Lavender)
    }
}
