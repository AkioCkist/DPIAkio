package com.dpiakio.app.net

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.*
import android.system.StructTimeval
import com.dpiakio.app.core.AppLog
import com.dpiakio.app.core.Desync
import com.dpiakio.app.core.DpiOptions
import com.dpiakio.app.core.Stats
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Userspace NAT between the TUN device and protected sockets — the Android stand-in
 * for WinDivert. Every outgoing TCP segment from apps is handed to [Desync].
 */
class TunEngine(
    val vpn: VpnService,
    private val tun: ParcelFileDescriptor,
    val opts: DpiOptions,
    val desync: Desync,
) {
    private val threadNo = AtomicInteger()
    val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "dpi-io-${threadNo.incrementAndGet()}").apply { isDaemon = true }
    }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dpi-timer").apply { isDaemon = true }
    }
    val prober = TtlProber(vpn, pool)
    /** Destinations where a fake segment reached the server and broke the connection. */
    val fakeBroken = ConcurrentHashMap<InetAddress, Boolean>()

    private val out = FileOutputStream(tun.fileDescriptor)
    private val tcp = ConcurrentHashMap<FlowKey, TcpSession>()
    private val udp = ConcurrentHashMap<FlowKey, UdpSession>()
    @Volatile private var running = false
    @Volatile var underlyingDns: List<InetAddress> = emptyList()

    private val needProbe = opts.finalized().let { o ->
        o.fakePacket && (o.autoTtl || o.minTtl > 0 || o.wrongChksum || o.wrongSeq)
    }

    fun start() {
        running = true
        Thread({ readLoop() }, "dpi-tun").apply { isDaemon = true }.start()
        timer.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            tcp.values.forEach { runCatching { it.tick(now) } }
            udp.values.forEach { runCatching { it.tick(now) } }
        }, 500, 500, TimeUnit.MILLISECONDS)   // RTO is 800 ms; 2 wake-ups/s is enough
    }

    fun stop() {
        running = false
        tcp.values.forEach { runCatching { it.abort() } }
        udp.values.forEach { runCatching { it.close() } }
        timer.shutdownNow()
        pool.shutdownNow()
        runCatching { tun.close() }
    }

    /** Default network changed (Wi-Fi <-> mobile): old sockets are bound to a dead path. */
    fun onNetworkChanged() {
        val n = tcp.size
        tcp.values.forEach { runCatching { it.abort() } }
        udp.values.forEach { runCatching { it.close() } }
        if (n > 0) AppLog.i("Đổi mạng: đã reset $n kết nối TCP để ứng dụng kết nối lại ngay")
    }

    fun writeTun(pkt: ByteArray) = writeTun(pkt, pkt.size)

    fun writeTun(pkt: ByteArray, len: Int) {
        if (!running) return
        try {
            synchronized(out) { out.write(pkt, 0, len) }
        } catch (e: Exception) {
            if (running) AppLog.d("TUN write: ${e.message}")
        }
    }

    fun removeTcp(key: FlowKey, s: TcpSession) { tcp.remove(key, s) }
    fun removeUdp(key: FlowKey, s: UdpSession) { udp.remove(key, s) }

    // ------------------------------------------------------------------ dispatch

    private fun readLoop() {
        val inp = FileInputStream(tun.fileDescriptor)
        val buf = ByteArray(65535)
        while (running) {
            val n = try { inp.read(buf) } catch (e: Exception) {
                if (running) AppLog.e("TUN read: ${e.message}")
                break
            }
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            val p = IpPacket.parse(buf, n) ?: continue
            try {
                when (p.proto) {
                    PROTO_TCP -> onTcp(p)
                    PROTO_UDP -> onUdp(p)
                }
            } catch (t: Throwable) {
                AppLog.d("packet: ${t.message}")
            }
        }
    }

    private fun onTcp(p: IpPacket) {
        val key = FlowKey(PROTO_TCP, p.src, p.srcPort, p.dst, p.dstPort)
        val flags = p.flags
        var s = tcp[key]
        if (s != null && flags and TCP_SYN != 0 && flags and TCP_ACK == 0 && p.seq != s.clientIsn) {
            s.abort() // port reuse with a new ISN
            s = null
        }
        if (s != null) { s.onPacket(p); return }

        if (flags and TCP_SYN != 0 && flags and TCP_ACK == 0) {
            var remote = key.dst
            var remotePort = p.dstPort
            if (p.dst.contentEquals(VIRT_DNS4)) {
                // DNS over TCP to our virtual resolver -> real resolver
                if (p.dstPort != 53) { reset(p); return }
                val t = dnsTarget(false, true)
                remote = t.first; remotePort = t.second
            }
            val o = p.tcpOptions()
            val ns = TcpSession(this, key, p.src, p.srcPort, p.dst, p.dstPort, remote, remotePort,
                p.seq, o[0], o[1], p.window)
            tcp[key] = ns
            if (needProbe && desync.handles(p.dstPort)) prober.start(remote.address)
            ns.connect()
        } else if (flags and TCP_RST == 0) {
            reset(p)
        }
    }

    private fun reset(p: IpPacket) {
        val flags = p.flags
        val pkt = if (flags and TCP_ACK != 0) {
            PacketBuilder.tcp(p.dst, p.dstPort, p.src, p.srcPort, p.ack, 0, TCP_RST, 0)
        } else {
            var ack = p.seq + p.tcpPayloadLen
            if (flags and TCP_SYN != 0) ack += 1
            if (flags and TCP_FIN != 0) ack += 1
            PacketBuilder.tcp(p.dst, p.dstPort, p.src, p.srcPort, 0, ack, TCP_RST or TCP_ACK, 0)
        }
        writeTun(pkt)
    }

    private fun onUdp(p: IpPacket) {
        val off = p.udpPayloadOff
        val len = p.udpPayloadLen
        val b = p.buf

        // -q: FILTER_PASSIVE_BLOCK_QUIC
        if (opts.blockQuic && p.dstPort == 443 && len >= 1200 && (b[off].toInt() and 0xFF) >= 0xC0 &&
            b[off + 1].toInt() == 0 && b[off + 2].toInt() == 0 && b[off + 3].toInt() == 0 && b[off + 4].toInt() == 1
        ) {
            Stats.quicBlocked.incrementAndGet()
            return
        }

        val toVirtual = p.dst.contentEquals(VIRT_DNS4)
        if (p.dstPort == 53 && isDnsQuery(b, off, len)) {
            val (target, port) = dnsTarget(p.v6, toVirtual, InetAddress.getByAddress(p.dst))
            val redirected = (p.v6 && opts.dnsV6Redirect) || (!p.v6 && opts.dnsV4Redirect)
            if (redirected) {
                Stats.dnsRedirected.incrementAndGet()
                if (opts.dnsVerbose) AppLog.i("[DNS] ${FlowKey.fmt(InetAddress.getByAddress(p.dst))}:53 → ${FlowKey.fmt(target)}:$port")
            }
            val q = b.copyOfRange(off, off + len)
            val src = p.src; val sport = p.srcPort; val dst = p.dst
            pool.execute { dnsExchange(q, src, sport, dst, target, port) }
            return
        }
        if (toVirtual) return

        val key = FlowKey(PROTO_UDP, p.src, p.srcPort, p.dst, p.dstPort)
        var s = udp[key]
        if (s == null) {
            s = UdpSession(this, key, p.src, p.srcPort, p.dst, p.dstPort)
            udp[key] = s
            s.start()
        }
        s.send(b, off, len)
    }

    /** dns_is_dns_packet(..., outgoing=1) */
    private fun isDnsQuery(b: ByteArray, off: Int, len: Int): Boolean {
        if (len < 16) return false
        val flags = u16(b, off + 2)
        return (flags and 0xFA00) == 0 && u16(b, off + 6) == 0
    }

    /** Where a DNS query must go: --dns-addr / --dnsv6-addr, else system resolver. */
    private fun dnsTarget(v6: Boolean, toVirtual: Boolean, origDst: InetAddress? = null): Pair<InetAddress, Int> {
        if (!v6 && opts.dnsV4Redirect) return InetAddress.getByName(opts.dnsV4Addr) to opts.dnsV4Port
        if (v6 && opts.dnsV6Redirect) return InetAddress.getByName(opts.dnsV6Addr) to opts.dnsV6Port
        if (!toVirtual && origDst != null) return origDst to 53
        val sys = underlyingDns.firstOrNull { it is Inet4Address } ?: underlyingDns.firstOrNull()
        return (sys ?: InetAddress.getByName(FALLBACK_DNS)) to 53
    }

    private fun dnsExchange(q: ByteArray, client: ByteArray, cport: Int, replySrc: ByteArray, target: InetAddress, port: Int) {
        var fd: FileDescriptor? = null
        try {
            fd = Os.socket(if (target is Inet4Address) AF_INET else AF_INET6, SOCK_DGRAM, 0)
            protect(fd)
            Os.setsockoptTimeval(fd, SOL_SOCKET, SO_RCVTIMEO, StructTimeval.fromMillis(4000))
            Os.connect(fd, target, port)
            Os.write(fd, q, 0, q.size)
            Stats.bytesUp.addAndGet(q.size.toLong())
            val buf = ByteArray(4096)
            val n = Os.read(fd, buf, 0, buf.size)
            if (n > 0) {
                Stats.bytesDown.addAndGet(n.toLong())
                writeTun(PacketBuilder.udp(replySrc, 53, client, cport, buf, 0, n))
            }
        } catch (e: Exception) {
            if (opts.dnsVerbose) AppLog.w("[DNS] ${FlowKey.fmt(target)}:$port lỗi: ${e.message}")
            else AppLog.d("DNS ${FlowKey.fmt(target)}:$port lỗi: ${e.message}")
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    /** VpnService.protect() for a FileDescriptor created by Os.socket(). */
    fun protect(fd: FileDescriptor) {
        val dup = ParcelFileDescriptor.dup(fd)
        try {
            if (!vpn.protect(dup.fd)) throw ErrnoException("protect", EPERM)
        } finally {
            dup.close()
        }
    }

    companion object {
        const val MTU = 1500
        const val VIRT_ADDR4 = "198.18.0.1"
        const val VIRT_DNS4_STR = "198.18.0.53"
        const val VIRT_ADDR6 = "fd00:6470:6961:6b6f::1"
        const val FALLBACK_DNS = "8.8.8.8"
        val VIRT_DNS4: ByteArray = byteArrayOf(198.toByte(), 18, 0, 53)
    }
}

/** Generic UDP relay (QUIC, games, VoIP…). */
class UdpSession(
    private val eng: TunEngine,
    private val key: FlowKey,
    private val client: ByteArray,
    private val clientPort: Int,
    private val dst: ByteArray,
    private val dstPort: Int,
) {
    @Volatile private var fd: FileDescriptor? = null
    @Volatile private var closed = false
    @Volatile private var lastActive = System.currentTimeMillis()
    private val pending = ArrayList<ByteArray>()

    init { Stats.udpActive.incrementAndGet() }

    fun start() {
        eng.pool.execute {
            try {
                val target = key.dst
                val f = Os.socket(if (target is Inet4Address) AF_INET else AF_INET6, SOCK_DGRAM, 0)
                eng.protect(f)
                // Long timeout: idle flows sleep; close() (idle tick / shutdown) closes the fd and wakes the reader.
                Os.setsockoptTimeval(f, SOL_SOCKET, SO_RCVTIMEO, StructTimeval.fromMillis(30_000))
                Os.connect(f, target, dstPort)
                synchronized(pending) {
                    fd = f
                    pending.forEach { runCatching { Os.write(f, it, 0, it.size) } }
                    pending.clear()
                }
                readLoop(f)
            } catch (e: Exception) {
                AppLog.d("UDP $key: ${e.message}")
                close()
            }
        }
    }

    fun send(b: ByteArray, off: Int, len: Int) {
        lastActive = System.currentTimeMillis()
        Stats.bytesUp.addAndGet(len.toLong())
        val f = fd
        if (f == null) {
            synchronized(pending) {
                val f2 = fd
                if (f2 == null) { if (pending.size < 64) pending.add(b.copyOfRange(off, off + len)); return }
                runCatching { Os.write(f2, b, off, len) }
            }
            return
        }
        try { Os.write(f, b, off, len) } catch (_: Exception) {}
    }

    private fun readLoop(f: FileDescriptor) {
        val buf = ByteArray(65535)
        val tx = ByteArray(65535 + 48)   // reply packet buffer, reused (single reader thread)
        while (!closed) {
            val n = try { Os.read(f, buf, 0, buf.size) } catch (e: ErrnoException) {
                if (e.errno == EAGAIN || e.errno == EINTR) continue
                if (e.errno == ECONNREFUSED) continue
                break
            } catch (_: Exception) { break }
            if (n <= 0) continue
            lastActive = System.currentTimeMillis()
            Stats.bytesDown.addAndGet(n.toLong())
            eng.writeTun(tx, PacketBuilder.udpInto(tx, dst, dstPort, client, clientPort, buf, 0, n))
        }
        close()
    }

    fun tick(now: Long) {
        val timeout = if (dstPort == 443) 30_000L else 120_000L
        if (now - lastActive > timeout) close()
    }

    fun close() {
        if (closed) return
        closed = true
        Stats.udpActive.decrementAndGet()
        eng.removeUdp(key, this)
        synchronized(pending) {
            fd?.let { runCatching { Os.close(it) } }
            fd = null
        }
    }
}
