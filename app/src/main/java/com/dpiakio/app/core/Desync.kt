package com.dpiakio.app.core

/**
 * Socket-side operations the desync engine needs. Implemented by the TCP session
 * on top of a protected, TCP_NODELAY socket.
 */
interface RemoteWriter {
    /** Plain write (blocking until fully written to the socket buffer). */
    fun write(b: ByteArray, off: Int, len: Int)

    /** Wait until everything written so far left the host, so the next write becomes a new segment. */
    fun flushSegment()

    /** Write with a temporary TTL / Hop Limit (used for out-of-order delivery). */
    fun writeWithTtl(b: ByteArray, off: Int, len: Int, ttl: Int): Boolean

    /**
     * Send [fake] (truncated / zero padded to [n] bytes) with [ttl], then make the kernel
     * retransmit the real bytes real[off, off+n). Returns false if unsupported.
     */
    fun sendFake(fake: ByteArray, real: ByteArray, off: Int, n: Int, ttl: Int): Boolean

    /** Incoming TTL measured for the destination (ttltrack replacement), or null if unknown. */
    fun incomingTtl(): Int?
}

class ConnInfo(val dstPort: Int, val dstLabel: String) {
    var firstData = true
}

/**
 * Port of the outbound TCP part of GoodbyeDPI's main loop (goodbyedpi.c lines ~1224–1440).
 * One call == one outbound TCP segment from the app ("packet").
 */
class Desync(raw: DpiOptions, private val blacklist: Blacklist?, private val fakes: Fakes,
             private val fallbackFakeTtl: Int) {
    val o = raw.finalized()
    private val processedPorts: Set<Int> = (listOf(80, 443) + o.extraPorts).toSet()

    fun handles(port: Int) = port in processedPorts

    /** Anything to do at all? (mirrors which WinDivert filters get opened) */
    val active = o.fragmentHttp || o.fragmentHttps || o.fakePacket || o.nativeFrag || o.hostReplace ||
        o.hostRemoveSpace || o.hostMixedCase || o.additionalSpace || o.fragmentHttpPersistent

    fun process(ci: ConnInfo, pkt: ByteArray, len: Int, w: RemoteWriter) {
        val first = ci.firstData
        ci.firstData = false
        val port = ci.dstPort

        if (!active || !handles(port) || len <= 0) {
            w.write(pkt, 0, len); return
        }
        // MAXPAYLOADSIZE_TEMPLATE filter: big non-request packets are not even captured.
        if (o.maxPayload > 0 && len >= o.maxPayload && !HttpTricks.alwaysProcess(pkt, len)) {
            w.write(pkt, 0, len); return
        }

        var shouldRecalc = false
        var sniOk = false
        var sniOff = 0
        var fake: ByteArray? = null
        var label: String? = null

        if (((if (o.fragmentHttps) len == o.httpsFragmentSize else false) || len > 16) &&
            port != 80 && (o.fakePacket || o.nativeFrag)
        ) {
            // Outbound packet on 443 (or --port): search for something like a TLS handshake.
            if (HttpTricks.isTlsHello(pkt, len)) {
                var sni: IntArray? = null
                if (o.useBlacklist || o.fragBySni) {
                    sni = HttpTricks.extractSni(pkt, len)
                    sniOk = sni != null
                }
                val host = sni?.let { HttpTricks.ascii(pkt, it[0], it[1]) }
                if ((o.useBlacklist && sniOk && blacklist?.check(host!!) == true) ||
                    (o.useBlacklist && !sniOk && o.allowNoSni) ||
                    !o.useBlacklist
                ) {
                    label = "HTTPS ${host ?: HttpTricks.extractSni(pkt, len)?.let { HttpTricks.ascii(pkt, it[0], it[1]) } ?: ci.dstLabel}"
                    if (sni != null) sniOff = sni[0]
                    if (o.fakePacket) fake = fakes.https()
                    if (o.nativeFrag) shouldRecalc = true
                }
            }
        } else if (len > 16 && (o.httpAllPorts || port == 80) &&
            HttpTricks.findMethodEnd(pkt, len, if (o.fragmentHttp) o.httpFragmentSize else 0) >= 0 &&
            (o.hostReplace || o.hostRemoveSpace || o.hostMixedCase || o.fragmentHttpPersistent || o.fakePacket)
        ) {
            val h = HttpTricks.findHeader(pkt, len, HttpTricks.HOST_FIND)
            if (h != null && h.valueLen > 0 && h.valueLen <= Blacklist.HOST_MAXLEN &&
                (if (o.useBlacklist) blacklist?.check(HttpTricks.ascii(pkt, h.valueIdx, h.valueLen)) == true else true)
            ) {
                var hostAddr = h.valueIdx
                val hostLen = h.valueLen
                label = "HTTP ${HttpTricks.ascii(pkt, hostAddr, hostLen)}"
                if (o.nativeFrag) shouldRecalc = true
                if (o.fakePacket) fake = fakes.http()
                if (o.hostMixedCase) {
                    HttpTricks.mixCase(pkt, hostAddr, hostLen)
                    shouldRecalc = true
                }
                if (o.hostReplace) {
                    System.arraycopy(HttpTricks.HOST_REPLACE, 0, pkt, h.nameIdx, HttpTricks.HOST_REPLACE.size)
                    shouldRecalc = true
                }
                if (o.additionalSpace && o.hostRemoveSpace) {
                    val m = HttpTricks.findMethodEnd(pkt, len, if (o.fragmentHttp) o.httpFragmentSize else 0)
                    if (m >= 0) {
                        // memmove(method_addr + 1, method_addr, host_addr - method_addr - 1)
                        System.arraycopy(pkt, m, pkt, m + 1, hostAddr - m - 1)
                        shouldRecalc = true
                    }
                } else if (o.hostRemoveSpace) {
                    val ua = HttpTricks.findHeader(pkt, len, HttpTricks.USERAGENT_FIND)
                    if (ua != null && ua.valueLen > 0) {
                        val uaAddr = ua.valueIdx
                        val uaLen = ua.valueLen
                        if (uaAddr > hostAddr) {
                            // Move one byte to the LEFT from "Host:" to the end of User-Agent
                            System.arraycopy(pkt, hostAddr, pkt, hostAddr - 1, uaAddr + uaLen - hostAddr)
                            hostAddr -= 1
                            pkt[uaAddr + uaLen - 1] = ' '.code.toByte()
                        } else {
                            // User-Agent goes BEFORE Host: move one byte to the RIGHT
                            System.arraycopy(pkt, uaAddr + uaLen, pkt, uaAddr + uaLen + 1, hostAddr - 1 - (uaAddr + uaLen))
                            pkt[uaAddr + uaLen] = ' '.code.toByte()
                        }
                        shouldRecalc = true
                    }
                }
            }
        }

        // ---- Fake Request Mode (TCP_HANDLE_OUTGOING_FAKE_PACKET) ----
        var fakeTtl = 0
        if (fake != null) fakeTtl = fakeTtl(w)

        // ---- Native fragmentation ----
        if (shouldRecalc && o.nativeFrag) {
            var size = 0
            if (o.fragmentHttp && port == 80) size = o.httpFragmentSize
            else if (o.fragmentHttps && port != 80) size = if (o.fragBySni && sniOk) sniOff else o.httpsFragmentSize
            if (size > 0) {
                Stats.bypassed.incrementAndGet()
                if (fake != null && fakeTtl > 0 && sendFakeThenRest(w, fake, pkt, len, fakeTtl, size)) {
                    AppLog.bypass("$label → fake(ttl=$fakeTtl) + ${if (o.reverseFrag) "reverse-frag" else "native-frag"}")
                    return
                }
                AppLog.bypass("$label → ${if (o.reverseFrag) "reverse-frag" else "native-frag"}($size)")
                sendNativeFragments(pkt, len, size, o.reverseFrag, w)
                return
            }
        }

        if (fake != null && fakeTtl > 0) {
            Stats.bypassed.incrementAndGet()
            if (sendFakeThenRest(w, fake, pkt, len, fakeTtl, 0)) {
                AppLog.bypass("$label → fake(ttl=$fakeTtl)")
                return
            }
        }

        // ---- Legacy (non-native) fragmentation: TCP window shrinking on SYN/ACK ----
        // GoodbyeDPI sets the server window to the fragment size so that the OS sends
        // the first segment cut at that size. Same bytes on the wire: split the first write.
        if (!o.nativeFrag && first) {
            val size = when {
                o.fragmentHttp && port == 80 -> o.httpFragmentSize
                o.fragmentHttps && port != 80 -> o.httpsFragmentSize
                else -> 0
            }
            if (size in 1 until len) {
                if (shouldRecalc || label != null) Stats.bypassed.incrementAndGet()
                AppLog.bypass("${label ?: ci.dstLabel} → window-frag($size)")
                w.write(pkt, 0, size)
                w.flushSegment()
                w.write(pkt, size, len - size)
                return
            }
        }
        if (shouldRecalc) Stats.bypassed.incrementAndGet()
        w.write(pkt, 0, len)
    }

    /** send_native_fragment() twice: step order depends on --reverse-frag. */
    private fun sendNativeFragments(pkt: ByteArray, len: Int, size: Int, reverse: Boolean, w: RemoteWriter) {
        if (size >= len) {
            // step 0 is skipped, step 1 sends the whole packet
            w.write(pkt, 0, len); return
        }
        if (reverse) {
            // Tail must reach the server before the head. The head is first sent with TTL 1
            // (dropped at the first hop, never seen by DPI), the tail follows normally and
            // the kernel retransmits the head afterwards -> server receives tail, then head.
            if (w.writeWithTtl(pkt, 0, size, 1)) {
                w.write(pkt, size, len - size)
                return
            }
        }
        w.write(pkt, 0, size)
        w.flushSegment()
        w.write(pkt, size, len - size)
    }

    /**
     * The fake occupies the same sequence range as the first bytes of the real payload and
     * dies on the way (low TTL); the real bytes for that range are delivered by the
     * kernel's retransmission, after the tail. DPI sees: fake request, then real tail.
     */
    private fun sendFakeThenRest(cur: RemoteWriter, fake: ByteArray, pkt: ByteArray, len: Int, ttl: Int, fragSize: Int): Boolean {
        if (len < 2) return false
        val n = minOf(fake.size, len - 1).coerceAtLeast(minOf(fragSize, len - 1))
        if (!cur.sendFake(fake, pkt, 0, n, ttl)) return false
        Stats.fakesSent.incrementAndGet()
        cur.write(pkt, n, len - n)
        return true
    }

    /** TTL for the fake segment, 0 = do not send a fake. */
    private fun fakeTtl(cur: RemoteWriter): Int {
        var shouldSend = true
        var ttl = o.setTtl
        val incoming = if (o.autoTtl || o.minTtl > 0 || o.wrongChksum || o.wrongSeq) cur.incomingTtl() else null
        if (o.autoTtl || o.minTtl > 0) {
            if (incoming != null) {
                if (o.autoTtl) ttl = AutoTtl.get(incoming, o.autoTtl1, o.autoTtl2, o.minTtl, o.autoTtlMax)
                else if (AutoTtl.get(incoming, 0, 0, o.minTtl, 0) == 0) shouldSend = false
            } else if (o.autoTtl) ttl = 0
        }
        if (!shouldSend) return 0
        // --wrong-chksum / --wrong-seq need raw packet injection. Without root they are
        // emulated by a fake that expires before the server (auto TTL 1-4-10).
        if (ttl == 0 && (o.wrongChksum || o.wrongSeq)) {
            ttl = incoming?.let { AutoTtl.get(it, 1, 4, 3, 10) } ?: 0
            if (ttl == 0 && incoming == null) ttl = fallbackFakeTtl
        }
        // Safety: a fake that reaches the server would corrupt the stream.
        if (incoming != null && ttl > 0) {
            val hops = AutoTtl.hops(incoming)
            if (hops in 1..ttl) ttl = if (hops > 2) hops - 1 else 0
        }
        return ttl
    }

}
