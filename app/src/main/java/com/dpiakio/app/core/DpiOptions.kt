package com.dpiakio.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirror of every runtime flag in GoodbyeDPI's main() (src/goodbyedpi.c).
 * Field names follow the original `do_*` variables.
 */
data class DpiOptions(
    val passiveDpi: Boolean = false,              // -p
    val blockQuic: Boolean = false,               // -q
    val hostReplace: Boolean = false,             // -r  Host -> hoSt
    val hostRemoveSpace: Boolean = false,         // -s
    val additionalSpace: Boolean = false,         // -a  (enables -s)
    val hostMixedCase: Boolean = false,           // -m
    val httpAllPorts: Boolean = false,            // -w
    val fragmentHttp: Boolean = false,            // -f <n>
    val httpFragmentSize: Int = 0,
    val fragmentHttpPersistent: Boolean = false,  // -k <n>
    val persistentNowait: Boolean = false,        // -n
    val fragmentHttps: Boolean = false,           // -e <n>
    val httpsFragmentSize: Int = 0,
    val extraPorts: List<Int> = emptyList(),      // --port
    val ipIds: List<Int> = emptyList(),           // --ip-id
    val dnsV4Addr: String = "",                   // --dns-addr
    val dnsV4Port: Int = 53,                      // --dns-port
    val dnsV6Addr: String = "",                   // --dnsv6-addr
    val dnsV6Port: Int = 53,                      // --dnsv6-port
    val dnsVerbose: Boolean = false,              // --dns-verb
    val blacklists: List<String> = emptyList(),   // --blacklist (source ids, see BlacklistSource)
    val allowNoSni: Boolean = false,              // --allow-no-sni
    val fragBySni: Boolean = false,               // --frag-by-sni
    val fakePacket: Boolean = false,              // implied by fake options
    val setTtl: Int = 0,                          // --set-ttl
    val autoTtl: Boolean = false,                 // --auto-ttl [a1-a2-m]
    val autoTtl1: Int = 0,
    val autoTtl2: Int = 0,
    val autoTtlMax: Int = 0,
    val minTtl: Int = 0,                          // --min-ttl
    val wrongChksum: Boolean = false,             // --wrong-chksum
    val wrongSeq: Boolean = false,                // --wrong-seq
    val nativeFrag: Boolean = false,              // --native-frag
    val reverseFrag: Boolean = false,             // --reverse-frag
    val fakeFromHex: List<String> = emptyList(),  // --fake-from-hex
    val fakeWithSni: List<String> = emptyList(),  // --fake-with-sni
    val fakeGen: Int = 0,                         // --fake-gen
    val fakeResend: Int = 1,                      // --fake-resend
    val maxPayload: Int = 0,                      // --max-payload [n]
) {
    val dnsV4Redirect get() = dnsV4Addr.isNotBlank()
    val dnsV6Redirect get() = dnsV6Addr.isNotBlank()
    val useBlacklist get() = blacklists.isNotEmpty()

    /** Post-getopt defaults, exactly like goodbyedpi.c after the option loop. */
    fun finalized(): DpiOptions {
        var o = this
        if (o.httpFragmentSize == 0) o = o.copy(httpFragmentSize = 2)
        if (o.httpsFragmentSize == 0) o = o.copy(httpsFragmentSize = 2)
        if (o.autoTtl1 == 0) o = o.copy(autoTtl1 = 1)
        if (o.autoTtl2 == 0) o = o.copy(autoTtl2 = 4)
        if (o.autoTtl) {
            if (o.minTtl == 0) o = o.copy(minTtl = 3)
            if (o.autoTtlMax == 0) o = o.copy(autoTtlMax = 10)
        }
        return o
    }

    /** Returns a validation error like GoodbyeDPI would print, or null. */
    fun validate(): String? {
        val f = finalized()
        if (f.nativeFrag && !(f.fragmentHttp || f.fragmentHttps))
            return "Native fragmentation is enabled but fragment sizes are not set."
        if (dnsV4Addr.isNotBlank() && !isIPv4(dnsV4Addr)) return "DNS address parameter error!"
        if (dnsV6Addr.isNotBlank() && !isIPv6(dnsV6Addr)) return "DNSv6 address parameter error!"
        return null
    }

    // ---------------------------------------------------------------- CLI

    /** Render as a goodbyedpi.exe command line (explicit flags, no modeset shorthand). */
    fun toCommandLine(): String {
        val a = mutableListOf<String>()
        if (passiveDpi) a += "-p"
        if (blockQuic) a += "-q"
        if (hostReplace) a += "-r"
        if (additionalSpace) a += "-a" else if (hostRemoveSpace) a += "-s"
        if (hostMixedCase) a += "-m"
        if (fragmentHttp) a += listOf("-f", "${httpFragmentSize.takeIf { it > 0 } ?: 2}")
        if (fragmentHttpPersistent && !nativeFrag) a += listOf("-k", "${httpFragmentSize.takeIf { it > 0 } ?: 2}")
        if (persistentNowait && !nativeFrag) a += "-n"
        if (fragmentHttps) a += listOf("-e", "${httpsFragmentSize.takeIf { it > 0 } ?: 2}")
        if (httpAllPorts) a += "-w"
        extraPorts.forEach { a += listOf("--port", "$it") }
        ipIds.forEach { a += listOf("--ip-id", "$it") }
        if (dnsV4Redirect) {
            a += listOf("--dns-addr", dnsV4Addr)
            if (dnsV4Port != 53) a += listOf("--dns-port", "$dnsV4Port")
        }
        if (dnsV6Redirect) {
            a += listOf("--dnsv6-addr", dnsV6Addr)
            if (dnsV6Port != 53) a += listOf("--dnsv6-port", "$dnsV6Port")
        }
        if (dnsVerbose) a += "--dns-verb"
        blacklists.forEach { a += listOf("--blacklist", BlacklistSource.fileNameOf(it)) }
        if (allowNoSni) a += "--allow-no-sni"
        if (fragBySni) a += "--frag-by-sni"
        if (setTtl > 0) a += listOf("--set-ttl", "$setTtl")
        if (autoTtl) {
            a += "--auto-ttl"
            if (autoTtl1 > 0 && autoTtl2 > 0 && autoTtlMax > 0) a += "$autoTtl1-$autoTtl2-$autoTtlMax"
        }
        if (minTtl > 0 && !(autoTtl && minTtl == 3)) a += listOf("--min-ttl", "$minTtl")
        if (wrongChksum) a += "--wrong-chksum"
        if (wrongSeq) a += "--wrong-seq"
        if (reverseFrag) a += "--reverse-frag" else if (nativeFrag) a += "--native-frag"
        fakeFromHex.forEach { a += listOf("--fake-from-hex", it) }
        fakeWithSni.forEach { a += listOf("--fake-with-sni", it) }
        if (fakeGen > 0) a += listOf("--fake-gen", "$fakeGen")
        if (fakeResend != 1) a += listOf("--fake-resend", "$fakeResend")
        if (maxPayload > 0) a += if (maxPayload == 1200) listOf("--max-payload") else listOf("--max-payload", "$maxPayload")
        return a.joinToString(" ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("p", passiveDpi); put("q", blockQuic); put("r", hostReplace); put("s", hostRemoveSpace)
        put("a", additionalSpace); put("m", hostMixedCase); put("w", httpAllPorts)
        put("f", fragmentHttp); put("fs", httpFragmentSize); put("k", fragmentHttpPersistent)
        put("n", persistentNowait); put("e", fragmentHttps); put("es", httpsFragmentSize)
        put("ports", JSONArray(extraPorts)); put("ipid", JSONArray(ipIds))
        put("d4", dnsV4Addr); put("d4p", dnsV4Port); put("d6", dnsV6Addr); put("d6p", dnsV6Port)
        put("dv", dnsVerbose); put("bl", JSONArray(blacklists)); put("ans", allowNoSni)
        put("fbs", fragBySni); put("fake", fakePacket); put("ttl", setTtl); put("at", autoTtl)
        put("at1", autoTtl1); put("at2", autoTtl2); put("atm", autoTtlMax); put("mt", minTtl)
        put("wc", wrongChksum); put("ws", wrongSeq); put("nf", nativeFrag); put("rf", reverseFrag)
        put("fhex", JSONArray(fakeFromHex)); put("fsni", JSONArray(fakeWithSni)); put("fgen", fakeGen)
        put("fres", fakeResend); put("mp", maxPayload)
    }

    companion object {
        fun fromJson(j: JSONObject) = DpiOptions(
            passiveDpi = j.optBoolean("p"), blockQuic = j.optBoolean("q"), hostReplace = j.optBoolean("r"),
            hostRemoveSpace = j.optBoolean("s"), additionalSpace = j.optBoolean("a"),
            hostMixedCase = j.optBoolean("m"), httpAllPorts = j.optBoolean("w"),
            fragmentHttp = j.optBoolean("f"), httpFragmentSize = j.optInt("fs"),
            fragmentHttpPersistent = j.optBoolean("k"), persistentNowait = j.optBoolean("n"),
            fragmentHttps = j.optBoolean("e"), httpsFragmentSize = j.optInt("es"),
            extraPorts = j.optJSONArray("ports").ints(), ipIds = j.optJSONArray("ipid").ints(),
            dnsV4Addr = j.optString("d4"), dnsV4Port = j.optInt("d4p", 53),
            dnsV6Addr = j.optString("d6"), dnsV6Port = j.optInt("d6p", 53),
            dnsVerbose = j.optBoolean("dv"), blacklists = j.optJSONArray("bl").strings(),
            allowNoSni = j.optBoolean("ans"), fragBySni = j.optBoolean("fbs"),
            fakePacket = j.optBoolean("fake"), setTtl = j.optInt("ttl"), autoTtl = j.optBoolean("at"),
            autoTtl1 = j.optInt("at1"), autoTtl2 = j.optInt("at2"), autoTtlMax = j.optInt("atm"),
            minTtl = j.optInt("mt"), wrongChksum = j.optBoolean("wc"), wrongSeq = j.optBoolean("ws"),
            nativeFrag = j.optBoolean("nf"), reverseFrag = j.optBoolean("rf"),
            fakeFromHex = j.optJSONArray("fhex").strings(), fakeWithSni = j.optJSONArray("fsni").strings(),
            fakeGen = j.optInt("fgen"), fakeResend = j.optInt("fres", 1), maxPayload = j.optInt("mp"),
        )

        private fun JSONArray?.ints(): List<Int> =
            if (this == null) emptyList() else List(length()) { getInt(it) }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else List(length()) { getString(it) }

        fun isIPv4(s: String) = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
            .matchEntire(s.trim())?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true

        fun isIPv6(s: String) = s.contains(':') && runCatching {
            java.net.InetAddress.getByName(s.trim()) is java.net.Inet6Address
        }.getOrDefault(false)
    }
}

/** Modesets -1 … -9, identical to the `case '1'..'9'` branches of goodbyedpi.c. */
object Modesets {
    data class Info(val id: Int, val legacy: Boolean, val flags: String, val title: String, val description: String)

    val all = listOf(
        Info(1, true, "-p -r -s -f 2 -k 2 -n -e 2", "Tương thích nhất", "Chặn passive DPI, hoSt, xoá khoảng trắng, phân mảnh HTTP/HTTPS 2 byte."),
        Info(2, true, "-p -r -s -f 2 -k 2 -n -e 40", "Cân bằng HTTPS", "Như -1 nhưng phân mảnh HTTPS 40 byte, HTTPS nhanh hơn."),
        Info(3, true, "-p -r -s -e 40", "Nhanh HTTP & HTTPS", "Chỉ phân mảnh HTTPS 40 byte, không phân mảnh HTTP."),
        Info(4, true, "-p -r -s", "Nhanh nhất", "Chỉ thủ thuật header HTTP, không phân mảnh."),
        Info(5, false, "-f 2 -e 2 --auto-ttl --reverse-frag --max-payload", "Auto TTL", "Gói giả với TTL tự động theo khoảng cách + phân mảnh đảo ngược."),
        Info(6, false, "-f 2 -e 2 --wrong-seq --reverse-frag --max-payload", "Wrong SEQ", "Gói giả SEQ/ACK sai + phân mảnh đảo ngược."),
        Info(7, false, "-f 2 -e 2 --wrong-chksum --reverse-frag --max-payload", "Wrong checksum", "Gói giả checksum sai + phân mảnh đảo ngược."),
        Info(8, false, "-f 2 -e 2 --wrong-seq --wrong-chksum --reverse-frag --max-payload", "SEQ + checksum", "Hai loại gói giả + phân mảnh đảo ngược."),
        Info(9, false, "-f 2 -e 2 --wrong-seq --wrong-chksum --reverse-frag --max-payload -q", "Mặc định (khuyên dùng)", "Như -8 và chặn QUIC/HTTP3 để ép trình duyệt dùng TCP."),
    )

    fun info(id: Int) = all.firstOrNull { it.id == id }

    /** Apply modeset [id] on top of [base] exactly as the getopt switch does. */
    fun apply(base: DpiOptions, id: Int): DpiOptions = when (id) {
        1 -> base.copy(passiveDpi = true, hostReplace = true, hostRemoveSpace = true,
            fragmentHttp = true, fragmentHttps = true, fragmentHttpPersistent = true, persistentNowait = true)
        2 -> base.copy(passiveDpi = true, hostReplace = true, hostRemoveSpace = true,
            fragmentHttp = true, fragmentHttps = true, fragmentHttpPersistent = true, persistentNowait = true,
            httpsFragmentSize = 40)
        3 -> base.copy(passiveDpi = true, hostReplace = true, hostRemoveSpace = true,
            fragmentHttps = true, httpsFragmentSize = 40)
        4 -> base.copy(passiveDpi = true, hostReplace = true, hostRemoveSpace = true)
        5 -> base.copy(fragmentHttp = true, fragmentHttps = true, reverseFrag = true, nativeFrag = true,
            httpFragmentSize = 2, httpsFragmentSize = 2, fragmentHttpPersistent = true, persistentNowait = true,
            fakePacket = true, autoTtl = true, maxPayload = 1200)
        6 -> base.copy(fragmentHttp = true, fragmentHttps = true, reverseFrag = true, nativeFrag = true,
            httpFragmentSize = 2, httpsFragmentSize = 2, fragmentHttpPersistent = true, persistentNowait = true,
            fakePacket = true, wrongSeq = true, maxPayload = 1200)
        7, 8, 9 -> {
            var o = base
            if (id == 9) o = o.copy(blockQuic = true)
            if (id >= 8) o = o.copy(wrongSeq = true)
            o.copy(fragmentHttp = true, fragmentHttps = true, reverseFrag = true, nativeFrag = true,
                httpFragmentSize = 2, httpsFragmentSize = 2, fragmentHttpPersistent = true, persistentNowait = true,
                fakePacket = true, wrongChksum = true, maxPayload = 1200)
        }
        else -> base
    }

    /** Fresh options for a modeset, keeping DNS / blacklist / port settings of [keep]. */
    fun fresh(id: Int, keep: DpiOptions): DpiOptions = apply(
        DpiOptions(
            dnsV4Addr = keep.dnsV4Addr, dnsV4Port = keep.dnsV4Port,
            dnsV6Addr = keep.dnsV6Addr, dnsV6Port = keep.dnsV6Port,
            blacklists = keep.blacklists, allowNoSni = keep.allowNoSni,
            extraPorts = keep.extraPorts,
        ), id
    )
}

/** The ready-made .cmd scripts shipped in GoodbyeDPI releases. */
object Presets {
    const val YANDEX_DNS_V4 = "77.88.8.8"
    const val YANDEX_DNS_V6 = "2a02:6b8::feed:0ff"
    const val YANDEX_DNS_PORT = 1253

    data class Preset(val id: String, val title: String, val subtitle: String, val script: String,
                      val build: () -> DpiOptions)

    private fun dns(o: DpiOptions) = o.copy(dnsV4Addr = YANDEX_DNS_V4, dnsV4Port = YANDEX_DNS_PORT,
        dnsV6Addr = YANDEX_DNS_V6, dnsV6Port = YANDEX_DNS_PORT)

    private fun ru(o: DpiOptions) = o.copy(blacklists = listOf(BlacklistSource.RUSSIA))

    val all = listOf(
        Preset("russia_blacklist", "Russia Blacklist", "Chỉ áp dụng cho tên miền trong danh sách chặn của Nga",
            "1_russia_blacklist.cmd") { ru(Modesets.apply(DpiOptions(), 5)) },
        Preset("russia_blacklist_dnsredir", "Russia Blacklist + DNS redir", "Danh sách Nga + chuyển hướng DNS sang Yandex :1253",
            "1_russia_blacklist_dnsredir.cmd") { dns(ru(Modesets.apply(DpiOptions(), 5))) },
        Preset("any_country", "Any Country", "Áp dụng cho mọi trang web, mọi quốc gia",
            "2_any_country.cmd") { Modesets.apply(DpiOptions(), 5) },
        Preset("any_country_dnsredir", "Any Country + DNS redir", "Mọi trang web + chuyển hướng DNS chống đầu độc",
            "2_any_country_dnsredir.cmd") { dns(Modesets.apply(DpiOptions(), 5)) },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/**
 * Parser for goodbyedpi.exe command lines. Implements the same getopt_long rules:
 * grouped short options ("-pr"), attached values ("-f2"), "--opt=value",
 * optional arguments for --auto-ttl and --max-payload.
 */
object CommandLine {
    class ParseException(msg: String) : Exception(msg)

    fun parse(cmd: String, start: DpiOptions = DpiOptions()): DpiOptions {
        val argv = tokenize(cmd).let { if (it.firstOrNull()?.endsWith("goodbyedpi.exe", true) == true || it.firstOrNull() == "goodbyedpi") it.drop(1) else it }
        if (argv.isEmpty()) return Modesets.apply(start, 9) // argc == 1 -> mode -9 by default
        var o = start
        var i = 0
        var httpFragSet = 0

        fun setHttpFrag(v: Int) {
            if (httpFragSet == 0) { httpFragSet = v; o = o.copy(httpFragmentSize = v) }
        }
        fun u16(s: String?, msg: String): Int = s?.trim()?.toIntOrNull()?.takeIf { it in 0..0xFFFF } ?: throw ParseException(msg)
        fun u8(s: String?, msg: String): Int = s?.trim()?.toIntOrNull()?.takeIf { it in 0..0xFF } ?: throw ParseException(msg)

        while (i < argv.size) {
            val arg = argv[i++]
            if (arg.startsWith("--")) {
                val eq = arg.indexOf('=')
                val name = if (eq > 0) arg.substring(2, eq) else arg.substring(2)
                var inline: String? = if (eq > 0) arg.substring(eq + 1) else null
                fun req(): String = inline ?: argv.getOrNull(i++) ?: throw ParseException("Option --$name requires an argument")
                fun opt(): String? {
                    if (inline != null) return inline
                    val nx = argv.getOrNull(i)
                    return if (nx != null && !nx.startsWith("-")) { i++; nx } else null
                }
                when (name) {
                    "port" -> {
                        val p = req().toIntOrNull() ?: 0
                        if (p <= 0 || p > 65535) throw ParseException("Port parameter error!")
                        if (p != 80 && p != 443 && p !in o.extraPorts) o = o.copy(extraPorts = o.extraPorts + p)
                    }
                    "ip-id" -> o = o.copy(ipIds = o.ipIds + u16(req(), "IP ID parameter error!"))
                    "dns-addr" -> {
                        val v = req()
                        if (!DpiOptions.isIPv4(v) || o.dnsV4Redirect) throw ParseException("DNS address parameter error!")
                        o = o.copy(dnsV4Addr = v)
                    }
                    "dnsv6-addr" -> {
                        val v = req()
                        if (!DpiOptions.isIPv6(v) || o.dnsV6Redirect) throw ParseException("DNS address parameter error!")
                        o = o.copy(dnsV6Addr = v)
                    }
                    "dns-port" -> {
                        if (!o.dnsV4Redirect) throw ParseException("--dns-port should be used with --dns-addr!")
                        o = o.copy(dnsV4Port = u16(req(), "DNS port parameter error!"))
                    }
                    "dnsv6-port" -> {
                        if (!o.dnsV6Redirect) throw ParseException("--dnsv6-port should be used with --dnsv6-addr!")
                        o = o.copy(dnsV6Port = u16(req(), "DNS port parameter error!"))
                    }
                    "dns-verb" -> o = o.copy(dnsVerbose = true)
                    "blacklist" -> {
                        val src = BlacklistSource.fromFileName(req())
                        if (src !in o.blacklists) o = o.copy(blacklists = o.blacklists + src)
                    }
                    "allow-no-sni" -> o = o.copy(allowNoSni = true)
                    "frag-by-sni" -> o = o.copy(fragBySni = true)
                    "set-ttl" -> o = o.copy(autoTtl = false, autoTtl1 = 0, autoTtl2 = 0, autoTtlMax = 0,
                        fakePacket = true, setTtl = u8(req(), "Set TTL parameter error!"))
                    "min-ttl" -> o = o.copy(fakePacket = true, minTtl = u8(req(), "Set Minimum TTL number of hops parameter error!"))
                    "auto-ttl" -> {
                        o = o.copy(fakePacket = true, autoTtl = true)
                        val v = opt()
                        if (v != null) {
                            if (v.contains('-')) {
                                val parts = v.split('-').filter { it.isNotEmpty() }
                                if (parts.size < 3) throw ParseException("Set Auto TTL parameter error!")
                                o = o.copy(autoTtl1 = u8(parts[0], "Set Auto TTL parameter error!"),
                                    autoTtl2 = u8(parts[1], "Set Auto TTL parameter error!"),
                                    autoTtlMax = u8(parts[2], "Set Auto TTL parameter error!"))
                            } else {
                                val t = u8(v, "Set Auto TTL parameter error!")
                                o = o.copy(autoTtl1 = t, autoTtl2 = t)
                            }
                        }
                    }
                    "wrong-chksum" -> o = o.copy(fakePacket = true, wrongChksum = true)
                    "wrong-seq" -> o = o.copy(fakePacket = true, wrongSeq = true)
                    "native-frag" -> o = o.copy(nativeFrag = true, fragmentHttpPersistent = true, persistentNowait = true)
                    "reverse-frag" -> o = o.copy(reverseFrag = true, nativeFrag = true, fragmentHttpPersistent = true, persistentNowait = true)
                    "max-payload" -> o = o.copy(maxPayload = opt()?.let { u16(it, "Max payload size parameter error!") } ?: 1200)
                    "fake-from-hex" -> o = o.copy(fakeFromHex = o.fakeFromHex + req())
                    "fake-with-sni" -> o = o.copy(fakeWithSni = o.fakeWithSni + req())
                    "fake-gen" -> o = o.copy(fakeGen = u8(req(), "Fake generator parameter error!"))
                    "fake-resend" -> o = o.copy(fakeResend = u8(req(), "Fake resend parameter error!"))
                    "debug-exit" -> { opt() }
                    else -> throw ParseException("Unknown option --$name")
                }
                inline = null
            } else if (arg.startsWith("-") && arg.length > 1) {
                var j = 1
                while (j < arg.length) {
                    val c = arg[j++]
                    fun value(): String {
                        val rest = arg.substring(j)
                        j = arg.length
                        return rest.ifEmpty { argv.getOrNull(i++) ?: throw ParseException("Option -$c requires an argument") }
                    }
                    when (c) {
                        in '1'..'9' -> o = Modesets.apply(o, c - '0')
                        'p' -> o = o.copy(passiveDpi = true)
                        'q' -> o = o.copy(blockQuic = true)
                        'r' -> o = o.copy(hostReplace = true)
                        's' -> o = o.copy(hostRemoveSpace = true)
                        'a' -> o = o.copy(additionalSpace = true, hostRemoveSpace = true)
                        'm' -> o = o.copy(hostMixedCase = true)
                        'f' -> { o = o.copy(fragmentHttp = true); setHttpFrag(u16(value(), "Fragment size should be in range [0 - 0xFFFF]")) }
                        'k' -> { o = o.copy(fragmentHttpPersistent = true, nativeFrag = true); setHttpFrag(u16(value(), "Fragment size should be in range [0 - 0xFFFF]")) }
                        'n' -> o = o.copy(fragmentHttpPersistent = true, persistentNowait = true, nativeFrag = true)
                        'e' -> o = o.copy(fragmentHttps = true, httpsFragmentSize = u16(value(), "Fragment size should be in range [0 - 65535]"))
                        'w' -> o = o.copy(httpAllPorts = true)
                        else -> throw ParseException("Unknown option -$c")
                    }
                }
            } else {
                throw ParseException("Unexpected argument: $arg")
            }
        }
        return o
    }

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var has = false
        for (ch in s) {
            when {
                quote != null -> if (ch == quote) quote = null else cur.append(ch)
                ch == '"' || ch == '\'' -> { quote = ch; has = true }
                ch.isWhitespace() -> { if (has || cur.isNotEmpty()) out += cur.toString(); cur.clear(); has = false }
                else -> cur.append(ch)
            }
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out.filter { it.isNotEmpty() && !it.equals("start", true) && it != "\"\"" }
    }
}
