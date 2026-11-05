package com.dpiakio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpiakio.app.core.Modesets
import com.dpiakio.app.core.Presets
import com.dpiakio.app.ui.MainViewModel
import com.dpiakio.app.ui.theme.Mono
import com.dpiakio.app.ui.theme.Palette

@Composable
fun ModesScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    val cfg by vm.config.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Chế độ", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
            Text("Giống các script .cmd và modeset -1…-9 của GoodbyeDPI", color = Palette.TextDim,
                style = MaterialTheme.typography.bodyMedium)
            SectionHeader("Kịch bản có sẵn", Icons.Rounded.RocketLaunch)
        }
        items(Presets.all, key = { it.id }) { p ->
            val selected = cfg.presetId == p.id
            val icon = when {
                p.id.startsWith("russia") && p.id.endsWith("dnsredir") -> Icons.Rounded.Dns
                p.id.startsWith("russia") -> Icons.Rounded.Flag
                p.id.endsWith("dnsredir") -> Icons.Rounded.Dns
                else -> Icons.Rounded.Public
            }
            GlassCard(Modifier.fillMaxWidth(), highlight = selected, onClick = { vm.selectPreset(p.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Palette.Accent else androidx.compose.ui.graphics.SolidColor(Palette.Surface)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = if (selected) Color.White else Palette.Cyan) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                        Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                        Spacer(Modifier.height(4.dp))
                        Text(p.script, style = Mono.copy(fontSize = 11.sp), color = Palette.TextFaint)
                    }
                    if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Palette.Green)
                }
            }
        }
        item { SectionHeader("Modeset hiện đại", Icons.Rounded.Shield) }
        items(Modesets.all.filter { !it.legacy }, key = { "m${it.id}" }) { m ->
            ModeCard(m, cfg.modeset == m.id) { vm.selectMode(m.id) }
        }
        item { SectionHeader("Modeset cũ (legacy)", Icons.Rounded.History) }
        items(Modesets.all.filter { it.legacy }, key = { "m${it.id}" }) { m ->
            ModeCard(m, cfg.modeset == m.id) { vm.selectMode(m.id) }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text(
                "Chọn modeset sẽ giữ nguyên cài đặt DNS, blacklist và cổng. Tinh chỉnh từng cờ ở tab Tuỳ chỉnh.",
                color = Palette.TextFaint, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ModeCard(m: Modesets.Info, selected: Boolean, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), highlight = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Palette.AccentHot else androidx.compose.ui.graphics.SolidColor(Palette.Surface)),
                contentAlignment = Alignment.Center,
            ) {
                Text("-${m.id}", color = if (selected) Color.White else Palette.Text, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    if (m.id == 9) { Spacer(Modifier.width(8.dp)); FlagTag("default", Palette.Green) }
                }
                Text(m.description, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                Spacer(Modifier.height(4.dp))
                Text(m.flags, style = Mono.copy(fontSize = 11.sp), color = Palette.Cyan.copy(alpha = 0.8f))
            }
        }
    }
}
