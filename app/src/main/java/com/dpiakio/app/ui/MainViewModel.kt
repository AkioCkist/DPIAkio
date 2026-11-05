package com.dpiakio.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpiakio.app.core.AppConfig
import com.dpiakio.app.core.AppLog
import com.dpiakio.app.core.AppSettings
import com.dpiakio.app.core.BlacklistSource
import com.dpiakio.app.core.CommandLine
import com.dpiakio.app.core.DpiOptions
import com.dpiakio.app.core.Modesets
import com.dpiakio.app.core.Presets
import com.dpiakio.app.service.DpiVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val config: StateFlow<AppConfig> = AppSettings.config
    val runState = DpiVpnService.state

    private val _remoteInfo = MutableStateFlow(remoteInfo())
    val remoteInfo: StateFlow<String> = _remoteInfo
    val downloading = MutableStateFlow(false)

    private var reloadJob: Job? = null

    init {
        AppSettings.load(app)
    }

    private val ctx get() = getApplication<Application>()

    private fun change(f: (AppConfig) -> AppConfig) {
        AppSettings.update(ctx, f)
        AppLog.verbose = config.value.verboseLog || config.value.options.dnsVerbose
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(700)
            DpiVpnService.reload(ctx)
        }
    }

    fun selectPreset(id: String) {
        val p = Presets.byId(id) ?: return
        change { it.copy(options = p.build(), presetId = id, modeset = 5) }
    }

    fun selectMode(n: Int) = change {
        val o = Modesets.fresh(n, it.options)
        it.copy(options = o, modeset = n, presetId = Presets.all.firstOrNull { p -> p.build() == o }?.id ?: "")
    }

    /** Any manual flag change turns the profile into "custom". */
    fun editOptions(f: (DpiOptions) -> DpiOptions) = change {
        val n = f(it.options)
        val keepsPreset = it.presetId.isNotEmpty() && Presets.byId(it.presetId)?.build() == n
        it.copy(options = n, presetId = if (keepsPreset) it.presetId else "",
            modeset = if (Modesets.fresh(it.modeset, n) == n) it.modeset else 0)
    }

    /** DNS / blacklist edits keep the selected modeset. */
    fun editTargets(f: (DpiOptions) -> DpiOptions) = change {
        val n = f(it.options)
        it.copy(options = n, presetId = Presets.all.firstOrNull { p -> p.build() == n }?.id ?: "")
    }

    fun editApp(f: (AppConfig) -> AppConfig) = change(f)

    fun importCommandLine(cmd: String): String? = try {
        val o = CommandLine.parse(cmd)
        o.validate()?.let { return it }
        val mode = Modesets.all.firstOrNull { Modesets.fresh(it.id, o) == o }?.id ?: 0
        change { it.copy(options = o, presetId = Presets.all.firstOrNull { p -> p.build() == o }?.id ?: "", modeset = mode) }
        null
    } catch (e: CommandLine.ParseException) {
        e.message
    }

    fun commandLine(c: AppConfig): String {
        val mode = c.modeset
        return if (mode > 0 && Modesets.fresh(mode, c.options) == c.options) {
            val extra = DpiOptions(
                dnsV4Addr = c.options.dnsV4Addr, dnsV4Port = c.options.dnsV4Port,
                dnsV6Addr = c.options.dnsV6Addr, dnsV6Port = c.options.dnsV6Port,
                blacklists = c.options.blacklists, allowNoSni = c.options.allowNoSni,
                extraPorts = c.options.extraPorts,
            ).toCommandLine()
            "goodbyedpi.exe -$mode" + if (extra.isNotEmpty()) " $extra" else ""
        } else "goodbyedpi.exe ${c.options.toCommandLine()}".trimEnd()
    }

    fun customBlacklist(): String = BlacklistSource.readCustom(ctx)

    fun saveCustomBlacklist(text: String) {
        BlacklistSource.writeCustom(ctx, text)
        change { it }
    }

    fun downloadBlacklist() {
        if (downloading.value) return
        downloading.value = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BlacklistSource.downloadRemote(ctx) } }
            downloading.value = false
            r.onSuccess {
                AppLog.i("Đã cập nhật blacklist: $it tên miền")
                _remoteInfo.value = remoteInfo()
                editTargets { o -> if (BlacklistSource.REMOTE in o.blacklists) o else o.copy(blacklists = o.blacklists + BlacklistSource.REMOTE) }
            }.onFailure { AppLog.e("Tải blacklist thất bại: ${it.message}") }
        }
    }

    private fun remoteInfo(): String {
        val f = BlacklistSource.remoteFile(getApplication())
        return if (f.exists()) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(f.lastModified())) else ""
    }
}
