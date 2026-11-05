package com.dpiakio.app.net

import java.net.InetAddress

const val PROTO_TCP = 6
const val PROTO_UDP = 17

const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10

internal fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
internal fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
internal fun u32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
internal fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
internal fun put32(b: ByteArray, i: Int, v: Int) {
    b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte()
}

/** Parsed view of an IPv4 / IPv6 packet read from the TUN device. */
class IpPacket private constructor(
    val buf: ByteArray,
    val v6: Boolean,
    val proto: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val l4Off: Int,
    val l4Len: Int,
) {
    // TCP / UDP common
    val srcPort get() = u16(buf, l4Off)
    val dstPort get() = u16(buf, l4Off + 2)

    // TCP
    val seq get() = u32(buf, l4Off + 4)
    val ack get() = u32(buf, l4Off + 8)
    val tcpHdrLen get() = (u8(buf, l4Off + 12) ushr 4) * 4
    val flags get() = u8(buf, l4Off + 13)
    val window get() = u16(buf, l4Off + 14)
    val tcpPayloadOff get() = l4Off + tcpHdrLen
    val tcpPayloadLen get() = l4Len - tcpHdrLen

    // UDP
    val udpPayloadOff get() = l4Off + 8
    val udpPayloadLen get() = (u16(buf, l4Off + 4) - 8).coerceIn(0, l4Len - 8)

    /** Returns (mss, wscale) from the TCP options; -1 when absent. */
    fun tcpOptions(): IntArray {
        var mss = -1
        var ws = -1
        var i = l4Off + 20
        val end = l4Off + tcpHdrLen
        while (i < end) {
            val kind = u8(buf, i)
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            if (i + 1 >= end) break
            val l = u8(buf, i + 1)
            if (l < 2 || i + l > end) break
            when (kind) {
                2 -> if (l == 4) mss = u16(buf, i + 2)
                3 -> if (l == 3) ws = u8(buf, i + 2)
            }
            i += l
        }
        return intArrayOf(mss, ws)
    }

    companion object {
        fun parse(buf: ByteArray, len: Int): IpPacket? {
            if (len < 20) return null
            when (u8(buf, 0) ushr 4) {
                4 -> {
                    val ihl = (u8(buf, 0) and 0x0F) * 4
                    val total = u16(buf, 2)
                    if (ihl < 20 || total > len || total < ihl) return null
                    // drop fragments (MF set or offset != 0)
                    if ((u16(buf, 6) and 0x3FFF) != 0) return null
                    val proto = u8(buf, 9)
                    val l4Len = total - ihl
                    if (!checkL4(proto, l4Len)) return null
                    return IpPacket(buf, false, proto, buf.copyOfRange(12, 16), buf.copyOfRange(16, 20), ihl, l4Len)
                }
                6 -> {
                    if (len < 40) return null
                    val payloadLen = u16(buf, 4)
                    if (40 + payloadLen > len) return null
                    var next = u8(buf, 6)
                    var off = 40
                    val end = 40 + payloadLen
                    // skip hop-by-hop / routing / destination options
                    while (next == 0 || next == 43 || next == 60) {
                        if (off + 8 > end) return null
                        val n = u8(buf, off)
                        off += (u8(buf, off + 1) + 1) * 8
                        next = n
                    }
                    if (off > end) return null
                    val l4Len = end - off
                    if (!checkL4(next, l4Len)) return null
                    return IpPacket(buf, true, next, buf.copyOfRange(8, 24), buf.copyOfRange(24, 40), off, l4Len)
                }
            }
            return null
        }

        private fun checkL4(proto: Int, l4Len: Int) = when (proto) {
            PROTO_TCP -> l4Len >= 20
            PROTO_UDP -> l4Len >= 8
            else -> false
        }
    }
}

object PacketBuilder {
    private fun sum(b: ByteArray, off: Int, len: Int, init: Long): Long {
        var s = init
        var i = off
        val end = off + len
        while (i + 1 < end) { s += ((u8(b, i) shl 8) or u8(b, i + 1)).toLong(); i += 2 }
        if (i < end) s += (u8(b, i) shl 8).toLong()
        return s
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while ((s ushr 16) != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    private fun writeIpHeader(pkt: ByteArray, src: ByteArray, dst: ByteArray, proto: Int, l4Len: Int): Int {
        if (src.size == 4) {
            pkt[0] = 0x45; pkt[1] = 0
            put16(pkt, 2, 20 + l4Len)
            put16(pkt, 4, 0); put16(pkt, 6, 0x4000) // DF
            pkt[8] = 64; pkt[9] = proto.toByte()
            put16(pkt, 10, 0)
            System.arraycopy(src, 0, pkt, 12, 4)
            System.arraycopy(dst, 0, pkt, 16, 4)
            put16(pkt, 10, fold(sum(pkt, 0, 20, 0)))
            return 20
        }
        pkt[0] = 0x60; pkt[1] = 0; pkt[2] = 0; pkt[3] = 0
        put16(pkt, 4, l4Len)
        pkt[6] = proto.toByte(); pkt[7] = 64
        System.arraycopy(src, 0, pkt, 8, 16)
        System.arraycopy(dst, 0, pkt, 24, 16)
        return 40
    }

    private fun pseudo(src: ByteArray, dst: ByteArray, proto: Int, l4Len: Int): Long {
        var s = sum(src, 0, src.size, 0)
        s = sum(dst, 0, dst.size, s)
        return s + proto + l4Len
    }

    fun tcp(
        src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        options: ByteArray? = null,
        data: ByteArray? = null, dataOff: Int = 0, dataLen: Int = 0,
    ): ByteArray {
        val optLen = options?.size ?: 0
        val tcpLen = 20 + optLen + dataLen
        val ipLen = if (src.size == 4) 20 else 40
        val pkt = ByteArray(ipLen + tcpLen)
        val o = writeIpHeader(pkt, src, dst, PROTO_TCP, tcpLen)
        put16(pkt, o, srcPort); put16(pkt, o + 2, dstPort)
        put32(pkt, o + 4, seq); put32(pkt, o + 8, ack)
        pkt[o + 12] = (((20 + optLen) / 4) shl 4).toByte()
        pkt[o + 13] = flags.toByte()
        put16(pkt, o + 14, window.coerceIn(0, 0xFFFF))
        put16(pkt, o + 16, 0); put16(pkt, o + 18, 0)
        if (options != null) System.arraycopy(options, 0, pkt, o + 20, optLen)
        if (data != null && dataLen > 0) System.arraycopy(data, dataOff, pkt, o + 20 + optLen, dataLen)
        put16(pkt, o + 16, fold(sum(pkt, o, tcpLen, pseudo(src, dst, PROTO_TCP, tcpLen))))
        return pkt
    }

    fun udp(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int, data: ByteArray, off: Int, len: Int): ByteArray {
        val udpLen = 8 + len
        val ipLen = if (src.size == 4) 20 else 40
        val pkt = ByteArray(ipLen + udpLen)
        val o = writeIpHeader(pkt, src, dst, PROTO_UDP, udpLen)
        put16(pkt, o, srcPort); put16(pkt, o + 2, dstPort)
        put16(pkt, o + 4, udpLen); put16(pkt, o + 6, 0)
        System.arraycopy(data, off, pkt, o + 8, len)
        var c = fold(sum(pkt, o, udpLen, pseudo(src, dst, PROTO_UDP, udpLen)))
        if (c == 0) c = 0xFFFF
        put16(pkt, o + 6, c)
        return pkt
    }
}

/** Hashable flow key. */
data class FlowKey(val proto: Int, val src: InetAddress, val srcPort: Int, val dst: InetAddress, val dstPort: Int) {
    override fun toString() = "${fmt(src)}:$srcPort → ${fmt(dst)}:$dstPort"

    companion object {
        fun fmt(a: InetAddress): String = a.hostAddress ?: "?"
    }
}
