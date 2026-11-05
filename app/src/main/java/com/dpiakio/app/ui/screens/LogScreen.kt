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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.dpiakio.app.core.AppLog
import com.dpiakio.app.ui.MainViewModel
import com.dpiakio.app.ui.theme.Mono
import com.dpiakio.app.ui.theme.Palette

@Composable
fun LogScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    val lines by AppLog.lines.collectAsState()
    val cfg by vm.config.collectAsState()
    val list = rememberLazyListState()
    val clip = LocalClipboardManager.current
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nhật ký", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
                Text("${lines.size} dòng", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
            Pill(if (cfg.verboseLog) "Chi tiết" else "Gọn", cfg.verboseLog, { vm.editApp { it.copy(verboseLog = !it.verboseLog) } })
            IconButton(onClick = {
                clip.setText(AnnotatedString(lines.joinToString("\n") { "${it.time} ${it.level} ${it.text}" }))
            }) { Icon(Icons.Rounded.ContentCopy, "Sao chép", tint = Palette.TextDim) }
            IconButton(onClick = { AppLog.clear() }) { Icon(Icons.Rounded.DeleteSweep, "Xoá", tint = Palette.TextDim) }
        }
        Box(
            Modifier.fillMaxSize().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Surface),
        ) {
            if (lines.isEmpty()) {
                Text("Chưa có nhật ký", color = Palette.TextFaint, modifier = Modifier.align(Alignment.Center))
            }
            LazyColumn(state = list, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(lines, key = { it.id }) { l ->
                    Row {
                        Text(l.time, style = Mono, color = Palette.TextFaint)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.padding(top = 5.dp).size(6.dp).clip(RoundedCornerShape(3.dp)).background(
                            when (l.level) {
                                AppLog.Level.ERROR -> Palette.Red
                                AppLog.Level.WARN -> Palette.Amber
                                AppLog.Level.BYPASS -> Palette.Violet
                                AppLog.Level.DEBUG -> Palette.TextFaint
                                AppLog.Level.INFO -> Palette.Cyan
                            }
                        ))
                        Spacer(Modifier.width(8.dp))
                        Text(l.text, style = Mono, color = when (l.level) {
                            AppLog.Level.ERROR -> Palette.Red
                            AppLog.Level.WARN -> Palette.Amber
                            AppLog.Level.DEBUG -> Palette.TextDim
                            else -> Palette.Text
                        })
                    }
                }
            }
        }
    }
}
