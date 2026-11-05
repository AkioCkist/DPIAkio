package com.dpiakio.app.net

import android.net.VpnService
import com.dpiakio.app.core.AppLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

object NativeBridge {
    val loaded: Boolean = runCatching { System.loadLibrary("dpibridge"); true }
        .onFailure { AppLog.e("Không tải được libdpibridge: ${it.message}") }
        .getOrDefault(false)

    @JvmStatic external fun sendFake(fd: Int, fake: ByteArray, real: ByteArray, realOff: Int, n: Int,
                                     ttl: Int, defTtl: Int, v6: Boolean): Int
    @JvmStatic external fun waitSent(fd: Int, maxMs: Int): Int
    @JvmStatic external fun getSockOpt(fd: Int, level: Int, opt: Int): Int
    @JvmStatic external fun openPing(v6: Boolean): Int
    @JvmStatic external fun closeFd(fd: Int)
    @JvmStatic external fun probeTtl(fd: Int, addr: ByteArray, timeoutMs: Int): Int
}

/**
 * Replacement for ttltrack.c: GoodbyeDPI records the TTL of the server's SYN/ACK.
 * A VPN app can't see that, so the destination is pinged once (unprivileged ICMP
 * socket, protected from the VPN) and the reply TTL is cached.
 */
class TtlProber(private val vpn: VpnService, private val pool: ExecutorService) {
    private class Entry(val future: CompletableFuture<Int?>, val time: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun start(addr: ByteArray) {
        if (!NativeBridge.loaded) return
        val key = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val now = System.currentTimeMillis()
        val e = cache[key]
        if (e != null && now - e.time < 10 * 60_000) return
        val f = CompletableFuture<Int?>()
        cache[key] = Entry(f, now)
        if (cache.size > 2048) cache.clear()
        pool.execute {
            var ttl: Int? = null
            try {
                val fd = NativeBridge.openPing(addr.size == 16)
                if (fd >= 0) {
                    if (vpn.protect(fd)) {
                        val r = NativeBridge.probeTtl(fd, addr, 1500)
                        if (r > 0) ttl = r
                    } else NativeBridge.closeFd(fd)
                }
            } catch (t: Throwable) {
                AppLog.d("TTL probe: ${t.message}")
            }
            f.complete(ttl)
        }
    }

    /** Waits briefly for the probe started at connect time. */
    fun get(addr: ByteArray, waitMs: Long = 400): Int? {
        val key = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val e = cache[key] ?: return null
        return try { e.future.get(waitMs, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
    }
}
