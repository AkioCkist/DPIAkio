package com.dpiakio.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dpiakio.app.core.AppLog
import com.dpiakio.app.service.DpiVpnService
import com.dpiakio.app.ui.screens.AdvancedScreen
import com.dpiakio.app.ui.screens.EarthGlobe
import com.dpiakio.app.ui.screens.HomeScreen
import com.dpiakio.app.ui.screens.LocalBackdrop
import com.dpiakio.app.ui.screens.LogScreen
import com.dpiakio.app.ui.screens.ModesScreen
import com.dpiakio.app.ui.screens.liquidGlass
import com.dpiakio.app.ui.theme.DpiTheme
import com.dpiakio.app.ui.theme.Palette
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DpiTheme { App(vm) } }
    }
}

private data class Tab(val title: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Trang chủ", Icons.Rounded.Home),
    Tab("Chế độ", Icons.Rounded.Layers),
    Tab("Tuỳ chỉnh", Icons.Rounded.Tune),
    Tab("Nhật ký", Icons.Rounded.ReceiptLong),
)

private val NavHeight = 68.dp
private val NavGap = 12.dp

@Composable
private fun App(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = tab != 0) { tab = 0 }   // back returns home before leaving the app

    val ctx = LocalContext.current
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
    val run by vm.runState.collectAsState()

    // Single backdrop: the background layer. Every glass surface (incl. the tab bar) refracts it,
    // so no full-screen content layer has to be re-recorded each frame.
    val bg = rememberLayerBackdrop()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pad = PaddingValues(top = statusInset, bottom = navInset + NavHeight + NavGap * 2)

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(bg)) {
                AppBackground(home = tab == 0, active = run.status == DpiVpnService.Status.RUNNING)
            }
            CompositionLocalProvider(LocalBackdrop provides bg) {
                AnimatedContent(
                    tab,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) },
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
        GlassTabBar(
            bg, tab, { tab = it },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = NavGap),
        )
    }
}

/**
 * Static deep-blue backdrop with coloured light pools (so glass has something to refract);
 * the home tab adds the rotating Earth.
 */
@Composable
private fun AppBackground(home: Boolean, active: Boolean) {
    // Static gradients: rendered once into a cached texture, never re-shaded.
    Box(
        Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithCache {
            val top = Brush.radialGradient(
                listOf(Color(0xFF2A3BD9).copy(alpha = 0.34f), Color(0x00000000)),
                center = Offset(size.width * 0.5f, -size.width * 0.15f), radius = size.width * 1.1f,
            )
            val left = Brush.radialGradient(
                listOf(Color(0xFF6A3DF0).copy(alpha = 0.30f), Color(0x00000000)),
                center = Offset(-size.width * 0.1f, size.height * 0.55f), radius = size.width * 0.9f,
            )
            val right = Brush.radialGradient(
                listOf(Color(0xFF1E6BFF).copy(alpha = 0.22f), Color(0x00000000)),
                center = Offset(size.width * 1.1f, size.height * 0.85f), radius = size.width * 0.9f,
            )
            val base = Brush.verticalGradient(listOf(Color(0xFF040A2E), Palette.Bg, Color(0xFF02041A)))
            onDrawBehind {
                drawRect(base); drawRect(top); drawRect(left); drawRect(right)
            }
        }
    )
    if (home) EarthGlobe(active = active, modifier = Modifier.fillMaxSize())
}

/**
 * Floating Liquid Glass tab bar: refracts the screen scrolling beneath it, with a sliding
 * selection capsule. 48dp+ targets, labels always visible, selected state exposed to TalkBack.
 */
@Composable
private fun GlassTabBar(
    backdrop: com.kyant.backdrop.Backdrop,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(NavHeight)
            .liquidGlass(backdrop, CircleShape, refraction = 24.dp, tint = Color(0xB8030820), dispersion = true)
            .padding(5.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth / TABS.size
            val x by animateDpAsState(w * selected, spring(0.72f, Spring.StiffnessMediumLow), label = "pill")
            Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape).background(Palette.Accent))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                TABS.forEachIndexed { i, t ->
                    val sel = i == selected
                    val tint by animateColorAsState(if (sel) Color.White else Palette.TextDim, label = "tint")
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .clickable(role = Role.Tab) { onSelect(i) }
                            .semantics { this.selected = sel },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(t.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(t.title, color = tint, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}
