package com.dpiakio.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dpiakio.app.R
import com.dpiakio.app.core.AppLog
import com.dpiakio.app.core.AppSettings
import com.dpiakio.app.core.Blacklist
import com.dpiakio.app.core.BlacklistSource
import com.dpiakio.app.core.Desync
import com.dpiakio.app.core.Fakes
import com.dpiakio.app.core.Presets
import com.dpiakio.app.core.Stats
import com.dpiakio.app.net.NativeBridge
import com.dpiakio.app.net.TunEngine
import com.dpiakio.app.ui.MainActivity
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

class DpiVpnService : VpnService() {

    enum class Status { STOPPED, STARTING, RUNNING, ERROR }

    data class RunState(val status: Status = Status.STOPPED, val network: String = "", val error: String = "",
                        val since: Long = 0L)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dpi-service").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var engine: TunEngine? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var networkName = ""
    private val ticker = object : Runnable {
        override fun run() {
            Stats.sample()
            main.postDelayed(this, 1000)
        }
    }

    /**
     * Statistics are only sampled while some screen collects them (the app UI in foreground).
     * In the background nothing wakes the main thread every second.
     */
    private val scope = kotlinx.coroutines.MainScope()
    private var samplerJob: kotlinx.coroutines.Job? = null

    private fun startSampler() {
        if (samplerJob != null) return
        samplerJob = scope.launch {
            Stats.snapshot.subscriptionCount.collect { n ->
                main.removeCallbacks(ticker)
                if (n > 0 && engine != null) main.post(ticker)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                worker.execute { stopVpn(); }
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                if (engine != null) worker.execute { stopEngine(); startVpn() }
                return START_STICKY
            }
            else -> {
                goForeground(getString(R.string.notif_starting))
                worker.execute { if (engine == null) startVpn() }
            }
        }
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        ServiceCompat.startForeground(this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private fun startVpn() {
        _state.value = RunState(Status.STARTING)
        try {
            val cfg = AppSettings.load(this)
            AppLog.verbose = cfg.verboseLog || cfg.options.dnsVerbose
            val opts = cfg.options
            opts.validate()?.let { throw IllegalArgumentException(it) }
            val f = opts.finalized()

            val label = when {
                cfg.presetId.isNotEmpty() -> Presets.byId(cfg.presetId)?.title ?: cfg.presetId
                cfg.modeset > 0 -> "Mode -${cfg.modeset}"
                else -> "Tuỳ chỉnh"
            }
            AppLog.i("GoodbyeDPI for Android — $label")
            AppLog.i("goodbyedpi ${opts.toCommandLine()}")
            logSummary(f)
            if (!NativeBridge.loaded) AppLog.w("Native helper không khả dụng: tắt Fake Request Mode và phân mảnh đảo ngược chính xác")

            // --blacklist
            var blacklist: Blacklist? = null
            if (f.useBlacklist) {
                blacklist = Blacklist()
                for (id in f.blacklists) {
                    val c = blacklist.load(this, id)
                    if (c < 0) AppLog.w("Can't load blacklist ${BlacklistSource.fileNameOf(id)} (chưa có dữ liệu)")
                    else AppLog.i("Loaded $c hosts from file ${BlacklistSource.fileNameOf(id)}")
                }
            }
            val fakes = Fakes(f)
            fakes.warnings.forEach { AppLog.w(it) }
            val desync = Desync(opts, blacklist, fakes, cfg.fallbackFakeTtl)

            val b = Builder()
                .setSession("DPI Akio")
                .setMtu(TunEngine.MTU)
                .setBlocking(true)
                .addAddress(TunEngine.VIRT_ADDR4, 32)
                .addDnsServer(TunEngine.VIRT_DNS4_STR)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE))
            // GoodbyeDPI ignores local networks (DIVERT_NO_LOCALNETS*): route only public space.
            Routes.publicV4().forEach { (a, p) -> b.addRoute(a, p) }
            // Only offer IPv6 when the real network has it; otherwise IPv6 is blocked by the
            // VPN and apps fall back to IPv4 at once instead of waiting on dead IPv6 attempts.
            val cm = getSystemService(ConnectivityManager::class.java)
            val under = currentNetwork ?: bestUnderlying(cm)
            under?.let { n -> cm.getLinkProperties(n)?.let { lastDns = it.dnsServers } }
            underlyingV6 = hasIpv6(under?.let { cm.getLinkProperties(it) })
            builtWithV6 = cfg.ipv6 && underlyingV6
            if (builtWithV6) {
                b.addAddress(TunEngine.VIRT_ADDR6, 128)
                b.addRoute("2000::", 3)
            }
            AppLog.i("IPv6: ${if (builtWithV6) "bật" else if (cfg.ipv6) "mạng không hỗ trợ — chặn để dùng IPv4" else "tắt"}")
            runCatching { b.addDisallowedApplication(packageName) }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            under?.let { b.setUnderlyingNetworks(arrayOf(it)) }

            val tun = b.establish() ?: throw IllegalStateException("Chưa cấp quyền VPN")
            val eng = TunEngine(this, tun, f, desync)
            engine = eng
            registerNetworkCallback()
            eng.underlyingDns = lastDns
            eng.start()
            Stats.reset()
            main.post { startSampler(); if (Stats.snapshot.subscriptionCount.value > 0) { main.removeCallbacks(ticker); main.post(ticker) } }
            _state.value = RunState(Status.RUNNING, networkName, since = System.currentTimeMillis())
            updateNotification()
            AppLog.i("Filter activated, GoodbyeDPI is now running!")
        } catch (t: Throwable) {
            AppLog.e("Khởi động thất bại: ${t.message}")
            _state.value = RunState(Status.ERROR, error = t.message ?: "error")
            stopEngine()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun logSummary(o: com.dpiakio.app.core.DpiOptions) {
        AppLog.i(
            "Block passive: ${o.passiveDpi.i} | Block QUIC: ${o.blockQuic.i} | Fragment HTTP: ${if (o.fragmentHttp) o.httpFragmentSize else 0} | " +
                "Persistent: ${if (o.fragmentHttpPersistent) o.httpFragmentSize else 0} | HTTPS: ${if (o.fragmentHttps) o.httpsFragmentSize else 0} | " +
                "By SNI: ${o.fragBySni.i} | Native: ${o.nativeFrag.i} | Reverse: ${o.reverseFrag.i} | hoSt: ${o.hostReplace.i} | " +
                "No space: ${o.hostRemoveSpace.i} | Add space: ${o.additionalSpace.i} | Mix: ${o.hostMixedCase.i} | AllPorts: ${o.httpAllPorts.i} | " +
                "DNS: ${o.dnsV4Redirect.i}/${o.dnsV6Redirect.i} | Fake TTL: ${if (o.autoTtl) "auto ${o.autoTtl1}-${o.autoTtl2}-${o.autoTtlMax}" else if (o.setTtl > 0) "fixed ${o.setTtl}" else "disabled"} min ${o.minTtl} | " +
                "Wrong chksum: ${o.wrongChksum.i} | Wrong seq: ${o.wrongSeq.i} | Max payload: ${o.maxPayload}"
        )
        if (o.passiveDpi || o.ipIds.isNotEmpty())
            AppLog.w("-p / --ip-id: Android không root không thể lọc gói RST/302 do DPI chèn vào (chỉ WinDivert làm được) — bỏ qua")
        if (o.wrongChksum || o.wrongSeq)
            AppLog.w("--wrong-chksum/--wrong-seq cần gửi gói thô (root). Đang mô phỏng bằng gói giả TTL thấp (auto-TTL)")
        if (o.fakeResend > 1 || o.fakeFromHex.size + o.fakeWithSni.size + o.fakeGen > 1)
            AppLog.w("Không root chỉ gửi được 1 gói giả cho mỗi request: dùng gói giả đầu tiên")
    }

    private val Boolean.i get() = if (this) 1 else 0

    private fun stopEngine() {
        engine?.stop()
        engine = null
        main.removeCallbacks(ticker)
    }

    private fun stopVpn() {
        stopEngine()
        unregisterNetworkCallback()
        _state.value = RunState(Status.STOPPED)
        AppLog.i("Đã dừng")
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() = main.post {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------------ network tracking

    @Volatile private var lastDns: List<java.net.InetAddress> = emptyList()

    /**
     * Tracks the real (non-VPN) networks directly. registerDefaultNetworkCallback() can
     * report the VPN itself on some ROMs, so the best physical network is chosen here
     * (validated first, then Wi-Fi/Ethernet over cellular — like the system default).
     * The tunnel stays up across changes; only the underlying network, the DNS used
     * for forwarding and the open TCP sessions are refreshed.
     */
    private val nets = HashMap<Network, NetworkCapabilities>()
    private val lps = HashMap<Network, LinkProperties>()

    private fun score(c: NetworkCapabilities): Int =
        (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0) +
            (if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 2
            else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 1 else 0)

    private fun bestUnderlying(cm: ConnectivityManager): Network? {
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
            .maxByOrNull { score(it.second) }?.first
    }

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                nets[network] = caps
                reevaluate()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                lps[network] = lp
                if (network == currentNetwork) applyLinkProps(lp)
            }

            override fun onLost(network: Network) {
                nets.remove(network)
                lps.remove(network)
                reevaluate()
            }
        }
        cm.registerNetworkCallback(req, cb, main)
        netCallback = cb
    }

    private fun reevaluate() {
        val pick = nets.entries.maxByOrNull { score(it.value) }?.key
        val prev = currentNetwork
        if (pick == null) {
            if (prev != null) {
                currentNetwork = null
                runCatching { setUnderlyingNetworks(null) }
                networkName = "Mất kết nối"
                AppLog.w("Mất mạng — chờ mạng mới, VPN vẫn giữ nguyên")
                _state.value = _state.value.copy(network = networkName)
                updateNotification()
            }
            return
        }
        val name = describe(nets[pick])
        if (pick == prev) {
            if (name != networkName) {
                networkName = name
                _state.value = _state.value.copy(network = name)
                updateNotification()
            }
            return
        }
        currentNetwork = pick
        networkName = name
        runCatching { setUnderlyingNetworks(arrayOf(pick)) }
        (lps[pick] ?: getSystemService(ConnectivityManager::class.java).getLinkProperties(pick))?.let { applyLinkProps(it) }
        if (prev != null || networkName != _state.value.network) {
            AppLog.i("Mạng: $name")
            // The first callbacks right after establish() are not a real switch.
            if (prev != null && System.currentTimeMillis() - _state.value.since > 3000) engine?.onNetworkChanged()
        }
        _state.value = _state.value.copy(network = name)
        updateNotification()
    }

    private fun applyLinkProps(lp: LinkProperties) {
        lastDns = lp.dnsServers
        engine?.underlyingDns = lp.dnsServers
        val v6 = hasIpv6(lp)
        if (v6 != underlyingV6) {
            underlyingV6 = v6
            if (engine != null && AppSettings.config.value.ipv6 && v6 != builtWithV6) {
                AppLog.i("Mạng ${if (v6) "có" else "không có"} IPv6 — cấu hình lại VPN")
                worker.execute { if (engine != null) { stopEngine(); startVpn() } }
            }
        }
    }

    @Volatile private var underlyingV6 = false
    @Volatile private var builtWithV6 = false

    private fun hasIpv6(lp: LinkProperties?): Boolean = lp != null &&
        lp.routes.any { it.isDefaultRoute && it.destination.address is java.net.Inet6Address } &&
        lp.linkAddresses.any { val a = it.address; a is java.net.Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress }

    private fun unregisterNetworkCallback() {
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCallback = null
        currentNetwork = null
        nets.clear(); lps.clear()
    }

    private fun describe(caps: NetworkCapabilities?): String = when {
        caps == null -> "Không rõ"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dữ liệu di động"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Mạng khác"
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(text: String): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DpiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF7C5CFF.toInt())
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        if (engine == null) return
        val cfg = AppSettings.config.value
        val label = Presets.byId(cfg.presetId)?.title ?: if (cfg.modeset > 0) "Mode -${cfg.modeset}" else "Tuỳ chỉnh"
        val text = "Đang hoạt động · $label" + if (networkName.isNotEmpty()) " · $networkName" else ""
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onRevoke() {
        AppLog.w("Quyền VPN bị thu hồi (ứng dụng VPN khác đã bật?)")
        worker.execute { stopVpn() }
    }

    override fun onDestroy() {
        stopEngine()
        unregisterNetworkCallback()
        if (_state.value.status == Status.RUNNING || _state.value.status == Status.STARTING)
            _state.value = RunState(Status.STOPPED)
        worker.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dpiakio.app.START"
        const val ACTION_STOP = "com.dpiakio.app.STOP"
        const val ACTION_RELOAD = "com.dpiakio.app.RELOAD"
        private const val CHANNEL = "dpi"
        private const val NOTIF_ID = 1

        private val _state = MutableStateFlow(RunState())
        val state: StateFlow<RunState> = _state

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            }
        }

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx,
            Intent(ctx, DpiVpnService::class.java).setAction(ACTION_START))

        fun stop(ctx: Context) {
            if (_state.value.status == Status.STOPPED) return
            ctx.startService(Intent(ctx, DpiVpnService::class.java).setAction(ACTION_STOP))
        }

        fun reload(ctx: Context) {
            if (_state.value.status != Status.RUNNING) return
            ctx.startService(Intent(ctx, DpiVpnService::class.java).setAction(ACTION_RELOAD))
        }

        val isRunning get() = _state.value.status == Status.RUNNING || _state.value.status == Status.STARTING

    }
}

/** IPv4 routes covering the public address space (excludes LAN / loopback / multicast). */
object Routes {
    private val EXCLUDED = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.0.0.0/24", "192.168.0.0/16", "224.0.0.0/3",
    )

    fun publicV4(): List<Pair<String, Int>> {
        val ex = EXCLUDED.map { s ->
            val (a, p) = s.split('/')
            ipToLong(a) to p.toInt()
        }
        val out = ArrayList<Pair<String, Int>>()
        fun walk(base: Long, prefix: Int) {
            val size = 1L shl (32 - prefix)
            val end = base + size - 1
            val overlaps = ex.filter { (eb, ep) ->
                val es = 1L shl (32 - ep); eb <= end && eb + es - 1 >= base
            }
            if (overlaps.isEmpty()) { out += longToIp(base) to prefix; return }
            if (overlaps.any { (eb, ep) -> eb <= base && eb + (1L shl (32 - ep)) - 1 >= end }) return
            walk(base, prefix + 1)
            walk(base + size / 2, prefix + 1)
        }
        walk(0, 0)
        return out
    }

    private fun ipToLong(s: String) = s.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
    private fun longToIp(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
}
