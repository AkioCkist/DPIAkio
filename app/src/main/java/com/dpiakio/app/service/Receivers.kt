package com.dpiakio.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dpiakio.app.core.AppSettings
import com.dpiakio.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Starts the VPN after reboot / app update (like installing GoodbyeDPI as a Windows service). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED &&
            a != "android.intent.action.QUICKBOOT_POWERON") return
        if (!AppSettings.load(context).startOnBoot) return
        if (VpnService.prepare(context) != null) return
        DpiVpnService.start(context)
    }
}

class ToggleTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(Dispatchers.Main + Job())
        scope = s
        s.launch { DpiVpnService.state.collect { render() } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    private fun render() {
        val t = qsTile ?: return
        val st = DpiVpnService.state.value.status
        t.state = if (st == DpiVpnService.Status.RUNNING || st == DpiVpnService.Status.STARTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = when (st) {
            DpiVpnService.Status.RUNNING -> DpiVpnService.state.value.network.ifEmpty { "Bật" }
            DpiVpnService.Status.STARTING -> "Đang bật…"
            else -> "Tắt"
        }
        t.updateTile()
    }

    override fun onClick() {
        if (DpiVpnService.isRunning) {
            DpiVpnService.stop(this)
        } else if (VpnService.prepare(this) == null) {
            DpiVpnService.start(this)
        } else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i,
                    android.app.PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
    }
}
