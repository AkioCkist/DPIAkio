package com.dpiakio.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object AppLog {
    enum class Level { DEBUG, INFO, WARN, ERROR, BYPASS }

    data class Line(val id: Long, val time: String, val level: Level, val text: String)

    private const val MAX = 400
    private val seq = AtomicLong()
    private val buf = ArrayDeque<Line>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    @Volatile var verbose = false

    fun d(text: String) { if (verbose) add(Level.DEBUG, text) }
    fun i(text: String) = add(Level.INFO, text)
    fun w(text: String) = add(Level.WARN, text)
    fun e(text: String) = add(Level.ERROR, text)
    fun bypass(text: String) { if (verbose) add(Level.BYPASS, text) }

    @Synchronized
    fun add(level: Level, text: String) {
        runCatching {
            when (level) {
                Level.ERROR -> android.util.Log.e("DPIAkio", text)
                Level.WARN -> android.util.Log.w("DPIAkio", text)
                else -> android.util.Log.i("DPIAkio", text)
            }
        }
        val line = Line(seq.incrementAndGet(), synchronized(fmt) { fmt.format(Date()) }, level, text)
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        _lines.value = buf.toList()
    }

    @Synchronized
    fun clear() {
        buf.clear()
        _lines.value = emptyList()
    }
}

/** Counters updated by the engine; the service samples them once per second. */
object Stats {
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    val tcpActive = AtomicLong()
    val udpActive = AtomicLong()
    val tcpTotal = AtomicLong()
    val bypassed = AtomicLong()      // packets where a circumvention trick was applied
    val fakesSent = AtomicLong()
    val dnsRedirected = AtomicLong()
    val quicBlocked = AtomicLong()

    data class Snapshot(
        val upSpeed: Long = 0, val downSpeed: Long = 0,
        val up: Long = 0, val down: Long = 0,
        val tcp: Long = 0, val udp: Long = 0, val tcpTotal: Long = 0,
        val bypassed: Long = 0, val fakes: Long = 0, val dns: Long = 0, val quic: Long = 0,
    )

    val snapshot = MutableStateFlow(Snapshot())

    fun reset() {
        listOf(bytesUp, bytesDown, tcpActive, udpActive, tcpTotal, bypassed, fakesSent, dnsRedirected, quicBlocked)
            .forEach { it.set(0) }
        lastSample = 0L
        snapshot.value = Snapshot()
    }

    @Volatile private var lastSample = 0L

    /** Speeds are normalised to the real elapsed time (sampling pauses while no UI observes). */
    fun sample() {
        val prev = snapshot.value
        val up = bytesUp.get()
        val down = bytesDown.get()
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = if (lastSample == 0L) 1000L else (now - lastSample).coerceAtLeast(1L)
        val fresh = lastSample == 0L || dt > 3000L   // first sample after a pause: no fake spike
        lastSample = now
        snapshot.value = Snapshot(
            upSpeed = if (fresh) 0 else ((up - prev.up).coerceAtLeast(0) * 1000 / dt),
            downSpeed = if (fresh) 0 else ((down - prev.down).coerceAtLeast(0) * 1000 / dt),
            up = up, down = down, tcp = tcpActive.get(), udp = udpActive.get(), tcpTotal = tcpTotal.get(),
            bypassed = bypassed.get(), fakes = fakesSent.get(), dns = dnsRedirected.get(), quic = quicBlocked.get(),
        )
    }
}
