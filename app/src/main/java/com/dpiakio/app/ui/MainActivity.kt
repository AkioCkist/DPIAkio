package com.dpiakio.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dpiakio.app.core.AppLog
import com.dpiakio.app.service.DpiVpnService
import com.dpiakio.app.ui.screens.AdvancedScreen
import com.dpiakio.app.ui.screens.HomeScreen
import com.dpiakio.app.ui.screens.LogScreen
import com.dpiakio.app.ui.screens.ModesScreen
import com.dpiakio.app.ui.theme.DpiTheme
import com.dpiakio.app.ui.theme.Palette

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DpiTheme { App(vm) } }
    }
}

private data class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("Trang chủ", Icons.Rounded.Home),
    Tab("Chế độ", Icons.Rounded.Layers),
    Tab("Tuỳ chỉnh", Icons.Rounded.Tune),
    Tab("Nhật ký", Icons.Rounded.ReceiptLong),
)

@Composable
private fun App(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) DpiVpnService.start(ctx)
        else AppLog.w("Người dùng từ chối quyền VPN")
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val toggle = {
        if (DpiVpnService.isRunning) {
            DpiVpnService.stop(ctx)
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            val prep = VpnService.prepare(ctx)
            if (prep != null) vpnLauncher.launch(prep) else DpiVpnService.start(ctx)
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { FloatingNav(tab) { tab = it } },
        ) { pad ->
            // Short cross-fade; the outgoing screen is dropped right after (no double layout).
            AnimatedContent(
                tab,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) },
                label = "tab",
            ) { t ->
                when (t) {
                    0 -> HomeScreen(vm, pad, onToggle = toggle, onOpenModes = { tab = 1 }, onOpenSettings = { tab = 2 })
                    1 -> ModesScreen(vm, pad)
                    2 -> AdvancedScreen(vm, pad)
                    else -> LogScreen(vm, pad)
                }
            }
        }
    }
}

private val NavShape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
private val NavBg = Brush.verticalGradient(listOf(Color(0xF0141640), Color(0xF00A0B20)))
private val NavBorder = Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x0DFFFFFF)))

@Composable
private fun FloatingNav(selected: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .clip(NavShape)
            .background(NavBg)
            .border(1.dp, NavBorder, NavShape)
            .padding(6.dp),
    ) {
        TABS.forEachIndexed { i, t ->
            val sel = i == selected
            androidx.compose.foundation.layout.Column(
                Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .then(if (sel) Modifier.background(Palette.Accent) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.title, tint = if (sel) Color.White else Palette.TextDim, modifier = Modifier.size(22.dp))
                Text(t.title, color = if (sel) Color.White else Palette.TextDim, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
