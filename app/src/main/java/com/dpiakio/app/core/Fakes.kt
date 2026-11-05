package com.dpiakio.app.core

import java.security.SecureRandom

/** Port of fakepackets.c (fake payload storage and generators). */
class Fakes(opts: DpiOptions) {
    private val rnd = SecureRandom()
    private val custom = ArrayList<ByteArray>()
    val warnings = ArrayList<String>()

    init {
        opts.fakeFromHex.forEach { hex ->
            fromHex(hex)?.let { add(it) } ?: warnings.add("WARNING: bad fake HEX value $hex")
        }
        opts.fakeWithSni.forEach { sni ->
            fromSni(sni)?.let { add(it) } ?: warnings.add("WARNING: bad domain name for SNI: $sni")
        }
        if (opts.fakeGen > 0) {
            if (opts.fakeGen > MAX_FAKES) warnings.add("WARNING: fake generator has failed!")
            else repeat(opts.fakeGen) { add(random(200)) }
        }
    }

    val count get() = custom.size

    private fun add(b: ByteArray) {
        if (custom.size < MAX_FAKES) custom.add(b)
    }

    /**
     * GoodbyeDPI sends every loaded fake (fakes[0..count)) on each request.
     * Without raw sockets only one fake can occupy a sequence range, so the first one is used.
     */
    fun http(): ByteArray = custom.firstOrNull() ?: DEFAULT_HTTP
    fun https(): ByteArray = custom.firstOrNull() ?: DEFAULT_HTTPS

    /** fake_load_from_hex() */
    private fun fromHex(data: String): ByteArray? {
        val s = data.trim()
        if (s.length < 2 || s.length % 2 != 0 || s.length > 1420 * 2) return null
        if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** fake_load_random(): length 8 + rand % maxsize, bytes rand % 0xFF */
    private fun random(maxSize: Int): ByteArray {
        val len = 8 + (rnd.nextInt() and Int.MAX_VALUE) % maxSize
        return ByteArray(len) { ((rnd.nextInt() and Int.MAX_VALUE) % 0xFF).toByte() }
    }

    /** fake_load_from_sni(): Firefox 130 ClientHello with the given SNI. */
    private fun fromSni(domain: String): ByteArray? {
        val name = domain.trim().toByteArray(Charsets.US_ASCII)
        if (name.isEmpty() || name.size > Blacklist.HOST_MAXLEN) return null
        val p0 = PART0
        val p1 = PART1
        val head = 9
        val size = p0.size + p1.size + head + name.size
        val pkt = ByteArray(size)
        System.arraycopy(p0, 0, pkt, 0, p0.size)
        System.arraycopy(p1, 0, pkt, p0.size + head + name.size, p1.size)
        for (i in 0 until size) if (pkt[i] == 0xAA.toByte()) pkt[i] = rnd.nextInt().toByte()
        fun be16(off: Int, v: Int) { pkt[off] = (v shr 8).toByte(); pkt[off + 1] = v.toByte() }
        be16(0x0003, size - 5)
        be16(0x0007, size - 9)
        be16(0x0072, size - 116)
        be16(p0.size + 0, 0x0000)
        be16(p0.size + 2, name.size + 5)
        be16(p0.size + 4, name.size + 3)
        pkt[p0.size + 6] = 0
        be16(p0.size + 7, name.size)
        System.arraycopy(name, 0, pkt, p0.size + head, name.size)
        return pkt
    }

    companion object {
        const val MAX_FAKES = 30

        val DEFAULT_HTTP: ByteArray = ("GET / HTTP/1.1\r\nHost: www.w3.org\r\n" +
            "User-Agent: curl/7.65.3\r\nAccept: */*\r\n" +
            "Accept-Encoding: deflate, gzip, br\r\n\r\n").toByteArray(Charsets.US_ASCII)

        val DEFAULT_HTTPS: ByteArray by lazy { hex(FakeData.HTTPS_HEX) }
        private val PART0: ByteArray by lazy { hex(FakeData.CH_PART0_HEX) }
        private val PART1: ByteArray by lazy { hex(FakeData.CH_PART1_HEX) }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** Port of tcp_get_auto_ttl() from ttltrack.c. Returns 0 when no fake must be sent. */
object AutoTtl {
    fun get(ttl: Int, autoTtl1: Int, autoTtl2: Int, minHops: Int, maxTtl: Int): Int {
        val nhops = when (ttl) {
            in 99..127 -> 128 - ttl
            in 35..63 -> 64 - ttl
            else -> return 0
        }
        if (nhops <= autoTtl1 || nhops < minHops) return 0
        var fake = (nhops - autoTtl2) and 0xFF                     // uint8_t arithmetic
        if (fake < autoTtl2 && nhops <= 9) {
            fake = (nhops - autoTtl1 - ((autoTtl2 - autoTtl1) * (nhops / 10f)).toInt()) and 0xFF
        }
        if (maxTtl != 0 && fake > maxTtl) fake = maxTtl
        return fake
    }

    fun hops(ttl: Int): Int = when (ttl) {
        in 99..127 -> 128 - ttl
        in 35..63 -> 64 - ttl
        in 1..34 -> 64 - ttl
        in 128..255 -> 255 - ttl
        else -> 0
    }
}
