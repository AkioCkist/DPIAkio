package com.dpiakio.app.core

/** Byte-level helpers ported 1:1 from goodbyedpi.c. Offsets are indices into the payload. */
object HttpTricks {
    private val METHODS = listOf("GET ", "HEAD ", "POST ", "PUT ", "DELETE ", "CONNECT ", "OPTIONS ")
        .map { it.toByteArray(Charsets.US_ASCII) }

    val HOST_FIND = "\r\nHost: ".toByteArray(Charsets.US_ASCII)
    val HOST_REPLACE = "\r\nhoSt: ".toByteArray(Charsets.US_ASCII)
    val USERAGENT_FIND = "\r\nUser-Agent: ".toByteArray(Charsets.US_ASCII)
    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

    /** dumb_memmem() restricted to [from, to). Returns index or -1. */
    fun indexOf(hay: ByteArray, from: Int, to: Int, needle: ByteArray): Int {
        if (needle.size > to - from) return -1
        outer@ for (i in from..to - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun startsWith(b: ByteArray, len: Int, prefix: ByteArray, prefixOff: Int = 0): Boolean {
        val n = prefix.size - prefixOff
        if (n > len) return false
        for (i in 0 until n) if (b[i] != prefix[prefixOff + i]) return false
        return true
    }

    /**
     * find_http_method_end(): index of the char right after the method name (the space),
     * or -1. [httpFrag] 1/2 also detects the second half of a window-fragmented request.
     */
    fun findMethodEnd(pkt: ByteArray, len: Int, httpFrag: Int): Int {
        for (m in METHODS) {
            if (startsWith(pkt, len, m)) return m.size - 1
            if ((httpFrag == 1 || httpFrag == 2) && startsWith(pkt, len, m, httpFrag))
                return m.size - httpFrag - 1
        }
        return -1
    }

    class Header(val nameIdx: Int, val valueIdx: Int, val valueLen: Int)

    /** find_header_and_get_info(): value length must be 3..HOST_MAXLEN. */
    fun findHeader(pkt: ByteArray, len: Int, name: ByteArray): Header? {
        val begin = indexOf(pkt, 0, len, name)
        if (begin < 0) return null
        val value = begin + name.size
        val rn = indexOf(pkt, value, len, CRLF)
        if (rn >= 0) {
            val vlen = rn - value
            if (vlen >= 3 && vlen <= Blacklist.HOST_MAXLEN) return Header(begin, value, vlen)
        }
        return null
    }

    /** extract_sni(): "very crude" ClientHello SNI extractor. Returns (offset, length). */
    fun extractSni(d: ByteArray, len: Int): IntArray? {
        var ptr = 0
        fun u(i: Int) = d[i].toInt() and 0xFF
        while (ptr + 8 < len) {
            if (u(ptr) == 0 && u(ptr + 1) == 0 && u(ptr + 2) == 0 &&
                u(ptr + 4) == 0 && u(ptr + 6) == 0 && u(ptr + 7) == 0 &&
                u(ptr + 3) - u(ptr + 5) == 2 && u(ptr + 5) - u(ptr + 8) == 3
            ) {
                if (ptr + 8 + u(ptr + 8) > len) return null
                val hn = ptr + 9
                val hl = u(ptr + 8)
                if (hl < 3 || hl > Blacklist.HOST_MAXLEN) return null
                if (hn + hl > len) return null
                for (i in 0 until hl) {
                    val c = d[hn + i].toInt().toChar()
                    if (!(c in '0'..'9' || c in 'a'..'z' || c == '.' || c == '-')) return null
                }
                return intArrayOf(hn, hl)
            }
            ptr++
        }
        return null
    }

    /** mix_case(): uppercase every odd character of the Host value. */
    fun mixCase(pkt: ByteArray, off: Int, len: Int) {
        for (i in 0 until len) if (i % 2 == 1) {
            val c = pkt[off + i].toInt().toChar()
            pkt[off + i] = c.uppercaseChar().code.toByte()
        }
    }

    fun ascii(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)

    fun isTlsHello(d: ByteArray, len: Int): Boolean {
        fun u(i: Int) = d[i].toInt() and 0xFF
        return (len == 2 && u(0) == 0x16 && u(1) == 0x03) ||
            (len >= 3 && u(0) == 0x16 && u(1) == 0x03 && (u(2) == 0x01 || u(2) == 0x03))
    }

    /** --max-payload filter: GET / POST / TLS handshake are always processed. */
    fun alwaysProcess(d: ByteArray, len: Int): Boolean {
        if (len < 4) return false
        fun u(i: Int) = d[i].toInt() and 0xFF
        if (u(0) == 'G'.code && u(1) == 'E'.code && u(2) == 'T'.code && u(3) == ' '.code) return true
        if (u(0) == 'P'.code && u(1) == 'O'.code && u(2) == 'S'.code && u(3) == 'T'.code) return true
        return u(0) == 0x16 && u(1) == 0x03 && u(2) <= 0x03
    }
}
