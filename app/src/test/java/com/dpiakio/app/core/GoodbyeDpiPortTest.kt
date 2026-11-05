package com.dpiakio.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records what the desync engine writes to the "socket". */
private class RecWriter(val fakeOk: Boolean = false, val ttl: Int? = null) : RemoteWriter {
    val ops = mutableListOf<String>()
    val data = java.io.ByteArrayOutputStream()
    override fun write(b: ByteArray, off: Int, len: Int) {
        ops += "w:" + String(b, off, len, Charsets.ISO_8859_1); data.write(b, off, len)
    }
    override fun flushSegment() { ops += "flush" }
    override fun writeWithTtl(b: ByteArray, off: Int, len: Int, ttl: Int): Boolean {
        ops += "ttl$ttl:" + String(b, off, len, Charsets.ISO_8859_1); data.write(b, off, len); return true
    }
    override fun sendFake(fake: ByteArray, real: ByteArray, off: Int, n: Int, ttl: Int): Boolean {
        if (!fakeOk) return false
        ops += "fake$ttl:$n"; data.write(real, off, n); return true
    }
    override fun incomingTtl(): Int? = ttl
}

class GoodbyeDpiPortTest {
    private val http = "GET /index.html HTTP/1.1\r\nHost: example.com\r\nUser-Agent: curl/8\r\nAccept: */*\r\n\r\n"

    private fun run(opts: DpiOptions, payload: ByteArray, port: Int, w: RecWriter, bl: Blacklist? = null): RecWriter {
        val d = Desync(opts, bl, Fakes(opts), 0)
        d.process(ConnInfo(port, "x"), payload.copyOf(), payload.size, w)
        return w
    }

    @Test fun cmdline_modesets() {
        assertEquals(Modesets.apply(DpiOptions(), 9), CommandLine.parse("-9"))
        assertEquals(Modesets.apply(DpiOptions(), 9), CommandLine.parse(""))
        val o = CommandLine.parse("""start "" goodbyedpi.exe -5 --dns-addr 77.88.8.8 --dns-port 1253 --dnsv6-addr 2a02:6b8::feed:0ff --dnsv6-port 1253 --blacklist ..\russia-blacklist.txt""")
        assertEquals(Presets.byId("russia_blacklist_dnsredir")!!.build(), o)
        val g = CommandLine.parse("-pr -f2 -e 40 --auto-ttl 2-5-12 --max-payload")
        assertTrue(g.passiveDpi && g.hostReplace && g.fragmentHttp && g.fragmentHttps)
        assertEquals(2, g.httpFragmentSize); assertEquals(40, g.httpsFragmentSize)
        assertEquals(listOf(2, 5, 12), listOf(g.autoTtl1, g.autoTtl2, g.autoTtlMax))
        assertEquals(1200, g.maxPayload)
        // round trip
        assertEquals(g, CommandLine.parse(g.toCommandLine()))
        val p9 = Modesets.apply(DpiOptions(), 9)
        assertEquals(p9, CommandLine.parse(p9.toCommandLine()))
    }

    @Test fun autottl_matches_ttltrack() {
        assertEquals(8, AutoTtl.get(52, 1, 4, 3, 10))     // 12 hops
        assertEquals(3, AutoTtl.get(59, 1, 4, 3, 10))     // 5 hops: 5-1-trunc(3*0.5)=3
        assertEquals(0, AutoTtl.get(62, 1, 4, 3, 10))     // 2 hops < min
        assertEquals(10, AutoTtl.get(100, 1, 4, 3, 10))   // 28 hops -> 24 -> max 10
        assertEquals(0, AutoTtl.get(200, 1, 4, 3, 10))
    }

    @Test fun mode1_http_hoSt_removespace_windowfrag() {
        val w = run(Modesets.apply(DpiOptions(), 1).finalized(), http.toByteArray(), 80, RecWriter())
        assertEquals(listOf("w:GE", "flush",
            "w:T /index.html HTTP/1.1\r\nhoSt:example.com\r\nUser-Agent: curl/8 \r\nAccept: */*\r\n\r\n"), w.ops)
    }

    @Test fun additional_space() {
        val o = DpiOptions(additionalSpace = true, hostRemoveSpace = true)
        val w = run(o, http.toByteArray(), 80, RecWriter())
        assertEquals("GET  /index.html HTTP/1.1\r\nHost:example.com\r\nUser-Agent: curl/8\r\nAccept: */*\r\n\r\n",
            String(w.data.toByteArray()))
    }

    @Test fun mixcase_and_useragent_before_host() {
        val req = "GET / HTTP/1.1\r\nUser-Agent: uax\r\nHost: example.com\r\n\r\n"
        val w = run(DpiOptions(hostMixedCase = true, hostRemoveSpace = true), req.toByteArray(), 80, RecWriter())
        assertEquals("GET / HTTP/1.1\r\nUser-Agent: uax \r\nHost:eXaMpLe.cOm\r\n\r\n", String(w.data.toByteArray()))
    }

    @Test fun sni_extract_default_fake() {
        val ch = Fakes.DEFAULT_HTTPS
        val s = HttpTricks.extractSni(ch, ch.size)
        assertNotNull(s)
        assertEquals("www.w3.org", HttpTricks.ascii(ch, s!![0], s[1]))
        val f = Fakes(DpiOptions(fakeWithSni = listOf("example.org"))).https()
        val s2 = HttpTricks.extractSni(f, f.size)!!
        assertEquals("example.org", HttpTricks.ascii(f, s2[0], s2[1]))
        assertEquals(116 + 523 + 9 + 11, f.size)
    }

    @Test fun mode9_https_reverse_frag_without_fake() {
        val ch = Fakes.DEFAULT_HTTPS
        val w = run(Modesets.apply(DpiOptions(), 9), ch, 443, RecWriter(fakeOk = false))
        assertEquals("ttl1:" + String(ch, 0, 2, Charsets.ISO_8859_1), w.ops[0])
        assertEquals("w:" + String(ch, 2, ch.size - 2, Charsets.ISO_8859_1), w.ops[1])
        assertArrayEquals(ch, w.data.toByteArray())
    }

    @Test fun mode5_https_fake_with_autottl() {
        val ch = ByteArray(1400).also { Fakes.DEFAULT_HTTPS.copyInto(it) }
        val w = run(Modesets.apply(DpiOptions(), 5), ch, 443, RecWriter(fakeOk = true, ttl = 52))
        assertEquals("fake8:517", w.ops[0])
        assertArrayEquals(ch, w.data.toByteArray())
        // unknown TTL -> no fake, reverse frag only (like ttltrack miss)
        val w2 = run(Modesets.apply(DpiOptions(), 5), ch, 443, RecWriter(fakeOk = true, ttl = null))
        assertTrue(w2.ops[0].startsWith("ttl1:"))
    }

    @Test fun mode2_legacy_https_40() {
        val ch = Fakes.DEFAULT_HTTPS
        val w = run(Modesets.apply(DpiOptions(), 2), ch, 443, RecWriter())
        assertEquals(40, w.ops[0].length - 2)
        assertEquals("flush", w.ops[1])
    }

    @Test fun blacklist_filters_sni() {
        val bl = Blacklist().apply { addLines(sequenceOf("w3.org")) }
        val o = Modesets.apply(DpiOptions(blacklists = listOf("custom")), 9)
        val hit = run(o, Fakes.DEFAULT_HTTPS, 443, RecWriter(), bl)
        assertTrue(hit.ops[0].startsWith("ttl1:"))
        val bl2 = Blacklist().apply { addLines(sequenceOf("example.com")) }
        val miss = run(o, Fakes.DEFAULT_HTTPS, 443, RecWriter(), bl2)
        assertEquals(1, miss.ops.size)
        assertTrue(bl.check("sub.www.w3.org"))
        assertFalse(bl.check("w3.org.evil"))
    }

    @Test fun frag_by_sni_cuts_before_hostname() {
        val ch = Fakes.DEFAULT_HTTPS
        val o = Modesets.apply(DpiOptions(fragBySni = true), 9)
        val w = run(o, ch, 443, RecWriter())
        val sni = HttpTricks.extractSni(ch, ch.size)!!
        assertEquals(sni[0], w.ops[0].length - "ttl1:".length)
    }

    @Test fun max_payload_skips_bulk_data() {
        val big = ByteArray(1300) { 'x'.code.toByte() }
        val w = run(Modesets.apply(DpiOptions(), 9), big, 443, RecWriter())
        assertEquals(1, w.ops.size)
    }
}
