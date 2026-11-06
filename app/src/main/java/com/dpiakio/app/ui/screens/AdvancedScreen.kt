package com.dpiakio.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Masks
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dpiakio.app.core.BlacklistSource
import com.dpiakio.app.core.DpiOptions
import com.dpiakio.app.core.Presets
import com.dpiakio.app.ui.MainViewModel
import com.dpiakio.app.ui.theme.Mono
import com.dpiakio.app.ui.theme.Palette

@Composable
fun AdvancedScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val o = cfg.options
    val edit = vm::editOptions
    val ctx = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    var showCustomList by remember { mutableStateOf(false) }
    var showDns by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Tuỳ chỉnh", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
        Text("Toàn bộ tham số dòng lệnh của goodbyedpi.exe", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)

        // ---------------------------------------------------------- command line
        SectionHeader("Dòng lệnh", Icons.Rounded.Terminal)
        GlassCard(Modifier.fillMaxWidth()) {
            Text(vm.commandLine(cfg), style = Mono, color = Palette.Cyan)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showImport = true }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Violet)) {
                    Icon(Icons.Rounded.Input, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Nhập lệnh")
                }
                OutlinedButton(onClick = { clip.setText(AnnotatedString(vm.commandLine(cfg))) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sao chép")
                }
            }
        }

        // ---------------------------------------------------------- passive / QUIC
        SectionHeader("Passive DPI & QUIC", Icons.Rounded.Shield)
        GlassCard(Modifier.fillMaxWidth()) {
            ToggleRow("Chặn passive DPI", "-p", "Lọc RST/302 giả do DPI chèn vào. Cần root/WinDivert — trên Android chỉ ghi nhận cờ",
                o.passiveDpi, { v -> edit { it.copy(passiveDpi = v) } })
            Divider()
            ToggleRow("Chặn QUIC / HTTP3", "-q", "Ép trình duyệt dùng TCP để có thể phân mảnh",
                o.blockQuic, { v -> edit { it.copy(blockQuic = v) } })
        }

        // ---------------------------------------------------------- HTTP
        SectionHeader("Thủ thuật HTTP", Icons.Rounded.Http)
        GlassCard(Modifier.fillMaxWidth()) {
            ToggleRow("Đổi Host → hoSt", "-r", null, o.hostReplace, { v -> edit { it.copy(hostReplace = v) } })
            Divider()
            ToggleRow("Xoá khoảng trắng sau Host:", "-s", null, o.hostRemoveSpace,
                { v -> edit { it.copy(hostRemoveSpace = v, additionalSpace = if (!v) false else it.additionalSpace) } })
            Divider()
            ToggleRow("Thêm khoảng trắng sau Method", "-a", "Bật kèm -s, có thể làm hỏng vài trang", o.additionalSpace,
                { v -> edit { it.copy(additionalSpace = v, hostRemoveSpace = v || it.hostRemoveSpace) } })
            Divider()
            ToggleRow("Trộn hoa/thường Host", "-m", "test.com → tEsT.cOm", o.hostMixedCase, { v -> edit { it.copy(hostMixedCase = v) } })
            Divider()
            ToggleRow("Tìm HTTP trên mọi cổng xử lý", "-w", null, o.httpAllPorts, { v -> edit { it.copy(httpAllPorts = v) } })
            Spacer(Modifier.height(8.dp))
            TextFieldRow("Cổng TCP bổ sung (--port)", o.extraPorts.joinToString(", "), { s ->
                val ports = s.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 && it != 80 && it != 443 }.distinct()
                vm.editTargets { it.copy(extraPorts = ports) }
            }, Modifier.fillMaxWidth(), placeholder = "8080, 8443", keyboard = KeyboardType.Number)
        }

        // ---------------------------------------------------------- fragmentation
        SectionHeader("Phân mảnh", Icons.Rounded.CallSplit)
        GlassCard(Modifier.fillMaxWidth()) {
            ToggleRow("Phân mảnh HTTP", "-f", null, o.fragmentHttp, { v -> edit { it.copy(fragmentHttp = v) } })
            ToggleRow("Phân mảnh HTTP keep-alive", "-k", null, o.fragmentHttpPersistent,
                { v -> edit { it.copy(fragmentHttpPersistent = v, nativeFrag = v || it.nativeFrag) } })
            ToggleRow("Không chờ ACK đoạn đầu", "-n", null, o.persistentNowait,
                { v -> edit { it.copy(persistentNowait = v, fragmentHttpPersistent = v || it.fragmentHttpPersistent, nativeFrag = v || it.nativeFrag) } })
            NumberField("Kích thước HTTP", o.httpFragmentSize, { v -> edit { it.copy(httpFragmentSize = v) } },
                Modifier.fillMaxWidth(), flag = "-f/-k")
            Divider()
            ToggleRow("Phân mảnh HTTPS", "-e", null, o.fragmentHttps, { v -> edit { it.copy(fragmentHttps = v) } })
            NumberField("Kích thước HTTPS", o.httpsFragmentSize, { v -> edit { it.copy(httpsFragmentSize = v) } },
                Modifier.fillMaxWidth(), flag = "-e")
            Divider()
            ToggleRow("Native fragmentation", "--native-frag", "Tách gói thật, không thu nhỏ Window Size", o.nativeFrag,
                { v -> edit { it.copy(nativeFrag = v, reverseFrag = if (!v) false else it.reverseFrag, fragmentHttpPersistent = v || it.fragmentHttpPersistent, persistentNowait = v || it.persistentNowait) } })
            ToggleRow("Gửi mảnh theo thứ tự ngược", "--reverse-frag", null, o.reverseFrag,
                { v -> edit { it.copy(reverseFrag = v, nativeFrag = v || it.nativeFrag, fragmentHttpPersistent = v || it.fragmentHttpPersistent, persistentNowait = v || it.persistentNowait) } })
            ToggleRow("Cắt ngay trước SNI", "--frag-by-sni", null, o.fragBySni, { v -> edit { it.copy(fragBySni = v) } })
            Divider()
            ToggleRow("Giới hạn payload xử lý", "--max-payload", "Bỏ qua gói dữ liệu lớn để giảm CPU", o.maxPayload > 0,
                { v -> edit { it.copy(maxPayload = if (v) 1200 else 0) } })
            if (o.maxPayload > 0) NumberField("Max payload", o.maxPayload, { v -> edit { it.copy(maxPayload = v) } }, Modifier.fillMaxWidth())
        }

        // ---------------------------------------------------------- fake
        SectionHeader("Fake Request Mode", Icons.Rounded.Masks)
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Gói giả được gửi với TTL thấp để DPI thấy nhưng không tới máy chủ. Không root: --wrong-chksum/--wrong-seq được mô phỏng bằng TTL tự động.",
                color = Palette.TextFaint, style = MaterialTheme.typography.bodySmall)
            ToggleRow("Auto TTL", "--auto-ttl", "Đo TTL máy chủ rồi giảm theo khoảng cách", o.autoTtl,
                { v -> edit { it.copy(autoTtl = v, fakePacket = v || it.fakePacket, setTtl = if (v) 0 else it.setTtl) } })
            if (o.autoTtl) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("a1", o.autoTtl1, { v -> edit { it.copy(autoTtl1 = v) } }, Modifier.weight(1f), 0..255)
                NumberField("a2", o.autoTtl2, { v -> edit { it.copy(autoTtl2 = v) } }, Modifier.weight(1f), 0..255)
                NumberField("max", o.autoTtlMax, { v -> edit { it.copy(autoTtlMax = v) } }, Modifier.weight(1f), 0..255)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                NumberField("TTL cố định", o.setTtl, { v ->
                    edit { it.copy(setTtl = v, fakePacket = v > 0 || it.fakePacket, autoTtl = if (v > 0) false else it.autoTtl) }
                }, Modifier.weight(1f), 0..255, "--set-ttl")
                NumberField("TTL tối thiểu", o.minTtl, { v -> edit { it.copy(minTtl = v, fakePacket = v > 0 || it.fakePacket) } },
                    Modifier.weight(1f), 0..255, "--min-ttl")
            }
            Divider()
            ToggleRow("Checksum sai", "--wrong-chksum", null, o.wrongChksum, { v -> edit { it.copy(wrongChksum = v, fakePacket = v || it.fakePacket) } })
            ToggleRow("SEQ/ACK sai", "--wrong-seq", null, o.wrongSeq, { v -> edit { it.copy(wrongSeq = v, fakePacket = v || it.fakePacket) } })
            ToggleRow("Bật Fake Request Mode", null, "Tự bật khi chọn một tuỳ chọn ở trên", o.fakePacket, { v -> edit { it.copy(fakePacket = v) } })
            Divider()
            TextFieldRow("Fake với SNI (--fake-with-sni)", o.fakeWithSni.joinToString(" "), { s ->
                edit { it.copy(fakeWithSni = s.split(' ', ',').map { x -> x.trim() }.filter { x -> x.isNotEmpty() }) }
            }, Modifier.fillMaxWidth(), placeholder = "www.google.com")
            Spacer(Modifier.height(6.dp))
            TextFieldRow("Fake từ HEX (--fake-from-hex)", o.fakeFromHex.joinToString(" "), { s ->
                edit { it.copy(fakeFromHex = s.split(' ', ',').map { x -> x.trim() }.filter { x -> x.isNotEmpty() }) }
            }, Modifier.fillMaxWidth(), placeholder = "1603010200…")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                NumberField("Sinh ngẫu nhiên", o.fakeGen, { v -> edit { it.copy(fakeGen = v) } }, Modifier.weight(1f), 0..30, "--fake-gen")
                NumberField("Gửi lại", o.fakeResend, { v -> edit { it.copy(fakeResend = v) } }, Modifier.weight(1f), 0..100, "--fake-resend")
            }
            Spacer(Modifier.height(6.dp))
            NumberField("TTL dự phòng khi không đo được (0 = không gửi)", cfg.fallbackFakeTtl,
                { v -> vm.editApp { it.copy(fallbackFakeTtl = v) } }, Modifier.fillMaxWidth(), 0..64)
        }

        // ---------------------------------------------------------- DNS
        SectionHeader("Chuyển hướng DNS", Icons.Rounded.Dns)
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Gửi mọi truy vấn DNS UDP tới resolver chỉ định (chống đầu độc DNS).", color = Palette.TextFaint,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            val current = when {
                !o.dnsV4Redirect && !o.dnsV6Redirect -> "Tắt — dùng DNS của mạng"
                else -> DNS_PRESETS.firstOrNull { it.matches(o) }?.name ?: "Tuỳ chỉnh"
            }
            OutlinedButton(onClick = { showDns = true }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Dns, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(current, Modifier.weight(1f), color = Palette.Text)
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            Spacer(Modifier.height(10.dp))
            DnsFields(o, v6 = false, vm)
            Spacer(Modifier.height(6.dp))
            DnsFields(o, v6 = true, vm)
            ToggleRow("Log chi tiết DNS", "--dns-verb", null, o.dnsVerbose, { v -> vm.editTargets { it.copy(dnsVerbose = v) } })
        }

        // ---------------------------------------------------------- blacklist
        SectionHeader("Blacklist", Icons.Rounded.ListAlt)
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Bật bất kỳ danh sách nào: chỉ lách DPI cho các tên miền (và tên miền con) trong danh sách (HTTP Host / TLS SNI).",
                color = Palette.TextFaint, style = MaterialTheme.typography.bodySmall)
            fun toggle(id: String, v: Boolean) = vm.editTargets {
                it.copy(blacklists = if (v) (it.blacklists + id).distinct() else it.blacklists - id)
            }
            ToggleRow("Russia blacklist", "--blacklist", "russia-blacklist.txt · ~91 000 tên miền", BlacklistSource.RUSSIA in o.blacklists,
                { toggle(BlacklistSource.RUSSIA, it) })
            val remoteInfo by vm.remoteInfo.collectAsStateWithLifecycle()
            val downloading by vm.downloading.collectAsStateWithLifecycle()
            ToggleRow("Russia blacklist (bản mới)", null,
                if (remoteInfo.isEmpty()) "Chưa tải — antizapret.prostovpn.org" else "Cập nhật: $remoteInfo",
                BlacklistSource.REMOTE in o.blacklists, { toggle(BlacklistSource.REMOTE, it) }, enabled = remoteInfo.isNotEmpty())
            OutlinedButton(onClick = { vm.downloadBlacklist() }, enabled = !downloading) {
                if (downloading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Cập nhật blacklist")
            }
            Divider()
            ToggleRow("Danh sách của tôi", null, "Mỗi dòng một tên miền", BlacklistSource.CUSTOM in o.blacklists,
                { toggle(BlacklistSource.CUSTOM, it) })
            OutlinedButton(onClick = { showCustomList = true }) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sửa danh sách")
            }
            Divider()
            ToggleRow("Lách cả khi không đọc được SNI", "--allow-no-sni", null, o.allowNoSni,
                { v -> vm.editTargets { it.copy(allowNoSni = v) } })
        }

        // ---------------------------------------------------------- system
        SectionHeader("Hệ thống", Icons.Rounded.Settings)
        GlassCard(Modifier.fillMaxWidth()) {
            ToggleRow("Xử lý IPv6", null, "Tắt: chặn IPv6 để ứng dụng dùng IPv4", cfg.ipv6, { v -> vm.editApp { it.copy(ipv6 = v) } })
            ToggleRow("Tự bật khi khởi động máy", null, "Như cài GoodbyeDPI thành Windows Service", cfg.startOnBoot,
                { v -> vm.editApp { it.copy(startOnBoot = v) } })
            ToggleRow("Log chi tiết", null, "Ghi từng kết nối được lách", cfg.verboseLog, { v -> vm.editApp { it.copy(verboseLog = v) } })
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.VpnKey, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("VPN luôn bật")
                }
                OutlinedButton(onClick = {
                    val pm = ctx.getSystemService(PowerManager::class.java)
                    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        }
                    } else ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.BatteryChargingFull, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pin")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showImport) ImportDialog(vm) { showImport = false }
    if (showCustomList) CustomListDialog(vm) { showCustomList = false }
    if (showDns) DnsPickerDialog(o, vm) { showDns = false }
}

private enum class DnsGroup(val title: String) {
    PLAIN("Không lọc"), ASIA("Châu Á"), BLOCK("Chặn QC / mã độc"), FAMILY("Gia đình"), RUSSIA("Nga"),
}

/** [v6] empty = provider has no IPv6 resolver; IPv6 queries then keep the system DNS. */
private data class DnsPreset(val name: String, val group: DnsGroup, val v4: String, val v6: String, val port: Int,
                             val note: String? = null) {
    fun matches(o: DpiOptions) = o.dnsV4Addr == v4 && o.dnsV4Port == port
}

private val DNS_PRESETS = listOf(
    DnsPreset("Cloudflare", DnsGroup.PLAIN, "1.1.1.1", "2606:4700:4700::1111", 53),
    DnsPreset("Google", DnsGroup.PLAIN, "8.8.8.8", "2001:4860:4860::8888", 53),
    DnsPreset("Quad9 ECS", DnsGroup.PLAIN, "9.9.9.11", "2620:fe::11", 53,
        "Gửi dải IP của bạn: Google/YouTube trả server gần nhất"),
    DnsPreset("Quad9", DnsGroup.PLAIN, "9.9.9.9", "2620:fe::fe", 53, "Chặn tên miền độc hại"),
    DnsPreset("OpenDNS", DnsGroup.PLAIN, "208.67.222.222", "2620:119:35::35", 53),
    DnsPreset("Control D", DnsGroup.PLAIN, "76.76.2.0", "2606:1a40::", 53),
    DnsPreset("AdGuard (không lọc)", DnsGroup.PLAIN, "94.140.14.140", "2a10:50c0::1:ff", 53),
    DnsPreset("DNS.WATCH", DnsGroup.PLAIN, "84.200.69.80", "2001:1608:10:25::1c04:b12f", 53),
    DnsPreset("Hurricane Electric", DnsGroup.PLAIN, "74.82.42.42", "2001:470:20::2", 53),
    DnsPreset("Level3", DnsGroup.PLAIN, "4.2.2.2", "", 53),
    DnsPreset("AliDNS", DnsGroup.ASIA, "223.5.5.5", "2400:3200::1", 53),
    DnsPreset("DNSPod", DnsGroup.ASIA, "119.29.29.29", "2402:4e00::", 53),
    DnsPreset("AdGuard chặn QC", DnsGroup.BLOCK, "94.140.14.14", "2a10:50c0::ad1:ff", 53),
    DnsPreset("AdGuard :5353", DnsGroup.BLOCK, "94.140.14.14", "2a10:50c0::ad1:ff", 5353, "Cổng lạ, né chặn DNS cổng 53"),
    DnsPreset("Cloudflare chặn mã độc", DnsGroup.BLOCK, "1.1.1.2", "2606:4700:4700::1112", 53),
    DnsPreset("CleanBrowsing", DnsGroup.BLOCK, "185.228.168.9", "2a0d:2a00:1::2", 53),
    DnsPreset("Comodo", DnsGroup.BLOCK, "8.26.56.26", "", 53),
    DnsPreset("Cloudflare gia đình", DnsGroup.FAMILY, "1.1.1.3", "2606:4700:4700::1113", 53),
    DnsPreset("AdGuard gia đình", DnsGroup.FAMILY, "94.140.14.15", "2a10:50c0::bad1:ff", 53),
    DnsPreset("OpenDNS gia đình", DnsGroup.FAMILY, "208.67.222.123", "2620:119:35::123", 53),
    DnsPreset("Yandex :1253", DnsGroup.RUSSIA, Presets.YANDEX_DNS_V4, Presets.YANDEX_DNS_V6, Presets.YANDEX_DNS_PORT,
        "Mặc định GoodbyeDPI — chỉ hợp khi ở Nga"),
)

@Composable
private fun DnsPickerDialog(o: DpiOptions, vm: MainViewModel, onDismiss: () -> Unit) {
    var group by remember { mutableStateOf<DnsGroup?>(null) }   // null = "Tất cả"
    fun pick(v4: String, v6: String, port: Int) {
        vm.editTargets { it.copy(dnsV4Addr = v4, dnsV4Port = port, dnsV6Addr = v6, dnsV6Port = port) }
        onDismiss()
    }
    val panel = RoundedCornerShape(28.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // dark base under the clear glass so text stays readable over the dimmed screen
                .background(Color(0xE6070C2C), panel)
                .glassSurface(panel, highlight = true)
                .padding(vertical = 18.dp),
        ) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Dns, null, tint = Palette.Cyan, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Chọn DNS", style = MaterialTheme.typography.titleLarge, color = Palette.Text, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("Tất cả", group == null, { group = null })
                DnsGroup.entries.forEach { g -> Pill(g.title, group == g, { group = g }) }
            }
            Spacer(Modifier.height(12.dp))
            GlassDivider()
            val off = !o.dnsV4Redirect && !o.dnsV6Redirect
            LazyColumn(
                Modifier.heightIn(max = 440.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (group == null) {
                    item { DnsRow("Tắt", "Dùng DNS của mạng (nhà mạng / router)", null, off) { pick("", "", 53) } }
                }
                DnsGroup.entries.filter { group == null || it == group }.forEach { g ->
                    if (group == null) item {
                        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(g.title.uppercase(), color = Palette.Cyan, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(10.dp))
                            GlassDivider(Modifier.weight(1f), inset = 0.dp)
                        }
                    }
                    val list = DNS_PRESETS.filter { it.group == g }
                    itemsIndexed(list) { i, d ->
                        val addr = d.v4 + (if (d.port != 53) ":${d.port}" else "") + (if (d.v6.isEmpty()) " · không IPv6" else "")
                        DnsRow(d.name, addr, d.note, !off && d.matches(o)) { pick(d.v4, d.v6, d.port) }
                        if (i < list.lastIndex) GlassDivider(Modifier.fillMaxWidth(), inset = 12.dp)
                    }
                }
            }
            GlassDivider()
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Đóng", color = Palette.Lavender) }
            }
        }
    }
}

/** Hairline separator with the glass rim's light gradient. */
@Composable
private fun GlassDivider(modifier: Modifier = Modifier.fillMaxWidth(), inset: Dp = 0.dp) {
    Box(
        modifier
            .padding(horizontal = inset)
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color(0x14FFFFFF), Color(0x4DB0C4FF), Color(0x14FFFFFF)))),
    )
}

@Composable
private fun DnsRow(name: String, addr: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (selected) Modifier.glassSurface(shape, highlight = true) else Modifier.clip(shape))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Palette.Text, style = MaterialTheme.typography.bodyLarge)
            Text(addr, color = if (selected) Palette.Lavender else Palette.TextFaint, style = Mono)
            if (note != null) Text(note, color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Icon(Icons.Rounded.Check, "Đang dùng", tint = Palette.Green)
    }
}

@Composable
private fun DnsFields(o: DpiOptions, v6: Boolean, vm: MainViewModel) {
    var err by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextFieldRow(if (v6) "--dnsv6-addr" else "--dns-addr", if (v6) o.dnsV6Addr else o.dnsV4Addr, { s ->
            val t = s.trim()
            val ok = t.isEmpty() || if (v6) DpiOptions.isIPv6(t) else DpiOptions.isIPv4(t)
            err = !ok
            if (ok) vm.editTargets { if (v6) it.copy(dnsV6Addr = t) else it.copy(dnsV4Addr = t) }
        }, Modifier.weight(1.6f), placeholder = if (v6) "2a02:6b8::feed:0ff" else "77.88.8.8", isError = err)
        NumberField("port", if (v6) o.dnsV6Port else o.dnsV4Port, { p ->
            vm.editTargets { if (v6) it.copy(dnsV6Port = if (p == 0) 53 else p) else it.copy(dnsV4Port = if (p == 0) 53 else p) }
        }, Modifier.weight(1f))
    }
}

@Composable
private fun ImportDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface2,
        title = { Text("Nhập lệnh goodbyedpi") },
        text = {
            Column {
                Text("Dán tham số như trên PC, ví dụ:", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                Text("-9 --dns-addr 77.88.8.8 --dns-port 1253", style = Mono, color = Palette.Cyan)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    textStyle = Mono, colors = fieldColors(), shape = RoundedCornerShape(14.dp), isError = error != null)
                if (error != null) Text(error!!, color = Palette.Red, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = vm.importCommandLine(text)
                if (e == null) onDismiss() else error = e
            }) { Text("Áp dụng") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

@Composable
private fun CustomListDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(vm.customBlacklist()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface2,
        title = { Text("Danh sách tên miền") },
        text = {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 380.dp),
                textStyle = Mono, colors = fieldColors(), shape = RoundedCornerShape(14.dp),
                placeholder = { Text("youtube.com\ngooglevideo.com\ndiscord.com", style = Mono, color = Palette.TextFaint) })
        },
        confirmButton = { TextButton(onClick = { vm.saveCustomBlacklist(text); onDismiss() }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Palette.Stroke)
