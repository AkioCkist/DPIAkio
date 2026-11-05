package com.dpiakio.app.net

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.*
import android.system.StructPollfd
import com.dpiakio.app.core.AppLog
import com.dpiakio.app.core.ConnInfo
import com.dpiakio.app.core.RemoteWriter
import com.dpiakio.app.core.Stats
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * One app TCP connection terminated inside the VPN and re-originated from a protected
 * socket. App → remote bytes go through [com.dpiakio.app.core.Desync] which applies the
 * GoodbyeDPI circumvention tricks on the outgoing segments.
 */
class TcpSession(
    private val eng: TunEngine,
    val key: FlowKey,
    private val clientAddr: ByteArray,
    private val clientPort: Int,
    private val localAddr: ByteArray,       // address the app connected to (reply source)
    private val localPort: Int,
    private val remote: InetAddress,          // where we actually connect
    private val remotePort: Int,
    val clientIsn: Int,
    clientMss: Int,
    private val clientWs: Int,
    synWindow: Int,
) : RemoteWriter {

    enum class State { CONNECTING, SYN_RCVD, ESTABLISHED, CLOSED }

    private val lock = Object()
    @Volatile var state = State.CONNECTING
        private set

    private val v6 = remote is java.net.Inet6Address
    private val remoteBytes = remote.address
    private val iss = Random.nextInt()
    private val ourWs = if (clientWs >= 0) OUR_WS else 0
    private val peerWs = if (clientWs >= 0) clientWs.coerceAtMost(14) else 0
    private val mss = (if (clientMss > 0) clientMss else 536).coerceAtMost(TunEngine.MTU - if (v6) 60 else 40)

    // receive side (app -> remote)
    private var rcvNxt = clientIsn + 1
    private val outQueue = LinkedBlockingQueue<ByteArray>()
    private var queued = 0
    private var advertised = 0
    private var clientFin = false

    // send side (remote -> app)
    private var sndUna = iss
    private var sndNxt = iss
    private var clientWnd = synWindow
    private var inBuf = ByteArray(16384)
    private var inStart = 0                    // inBuf[inStart, inStart+inLen) == seq [sndUna, sndUna+inLen)
    private var inLen = 0
    /** Outgoing packet scratch buffer; every emit() runs under [lock], so one per session is enough. */
    private val txBuf = ByteArray(TunEngine.MTU + 128)
    private var remoteEof = false
    private var finSent = false
    private var finAcked = false

    private var lastProgress = 0L
    private var lastActivity = System.currentTimeMillis()
    private var synAckTime = 0L
    private var synAckCount = 0
    private var retransmits = 0

    // socket
    private var pfd: ParcelFileDescriptor? = null
    private var fd: FileDescriptor? = null
    private var rawFd = -1
    private var defTtl = 64
    private val threadsLeft = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    private val ci = ConnInfo(localPort, "${FlowKey.fmt(InetAddress.getByAddress(localAddr))}:$localPort")

    init {
        Stats.tcpActive.incrementAndGet()
        Stats.tcpTotal.incrementAndGet()
    }

    // ------------------------------------------------------------------ setup

    fun connect() {
        eng.pool.execute {
            try {
                openSocket()
                synchronized(lock) {
                    if (state == State.CLOSED) return@execute
                    state = State.SYN_RCVD
                    sendSynAck()
                }
                threadsLeft.set(2)
                eng.pool.execute { readerLoop() }
                eng.pool.execute { writerLoop() }
            } catch (t: Throwable) {
                AppLog.d("TCP $key: connect failed: ${t.message}")
                synchronized(lock) {
                    emit(rcvNxt, TCP_RST or TCP_ACK, seqOverride = 0)
                }
                closeInternal()
            }
        }
    }

    private fun openSocket() {
        val raw = Os.socket(if (v6) AF_INET6 else AF_INET, SOCK_STREAM, 0)
        val p = ParcelFileDescriptor.dup(raw)
        Os.close(raw)
        pfd = p
        fd = p.fileDescriptor
        rawFd = p.fd
        if (!eng.vpn.protect(rawFd)) throw IOException("protect() failed")
        val f = fd!!
        Os.setsockoptInt(f, IPPROTO_TCP, TCP_NODELAY, 1)
        val fl = Os.fcntlInt(f, F_GETFL, 0)
        Os.fcntlInt(f, F_SETFL, fl or O_NONBLOCK)
        try {
            Os.connect(f, remote, remotePort)
        } catch (e: ErrnoException) {
            if (e.errno != EINPROGRESS) throw e
            val pf = StructPollfd().apply { this.fd = f; events = POLLOUT.toShort() }
            val r = Os.poll(arrayOf(pf), CONNECT_TIMEOUT_MS)
            if (r <= 0) throw IOException("connect timeout")
            if (NativeBridge.loaded) {
                val err = NativeBridge.getSockOpt(rawFd, SOL_SOCKET, SO_ERROR)
                if (err != 0) throw ErrnoException("connect", if (err < 0) -err else err)
            } else {
                Os.getpeername(f) // throws ENOTCONN if the connection failed
            }
        }
        Os.fcntlInt(f, F_SETFL, fl)
        defTtl = if (NativeBridge.loaded) {
            if (v6) NativeBridge.getSockOpt(rawFd, IPPROTO_IPV6, IPV6_UNICAST_HOPS)
            else NativeBridge.getSockOpt(rawFd, IPPROTO_IP, IP_TTL)
        } else 64
        if (defTtl !in 1..255) defTtl = 64
    }

    // ------------------------------------------------------------------ app -> us

    fun onPacket(p: IpPacket) {
        val flags = p.flags
        synchronized(lock) {
            if (state == State.CLOSED) return
            lastActivity = System.currentTimeMillis()

            if (flags and TCP_RST != 0) {
                closeInternalLocked()
                return
            }
            if (flags and TCP_SYN != 0) {
                if (state == State.SYN_RCVD) sendSynAck()
                return
            }
            if (state == State.CONNECTING) return
            if (flags and TCP_ACK == 0) return

            val ack = p.ack
            if (state == State.SYN_RCVD) {
                if (ack == iss + 1) {
                    state = State.ESTABLISHED
                    sndUna = iss + 1
                    lastProgress = System.currentTimeMillis()
                    lock.notifyAll()
                } else {
                    return
                }
            }
            clientWnd = p.window shl peerWs
            if (seqGt(ack, sndUna) && !seqGt(ack, sndNxt)) {
                var acked = ack - sndUna
                val data = minOf(acked, inLen)
                if (data > 0) {
                    // O(1): advance the window start instead of shifting the whole buffer per ACK
                    inStart += data
                    inLen -= data
                    acked -= data
                    if (inLen == 0) {
                        inStart = 0
                        if (inBuf.size > 64 * 1024) inBuf = ByteArray(16384)   // give memory back when drained
                    }
                }
                sndUna = ack
                if (finSent && ack == sndNxt) finAcked = true
                lastProgress = System.currentTimeMillis()
                retransmits = 0
                lock.notifyAll()
            }

            // payload
            var plen = p.tcpPayloadLen
            var poff = p.tcpPayloadOff
            var accepted = true
            if (plen > 0) {
                val seq = p.seq
                if (seq != rcvNxt) {
                    val diff = rcvNxt - seq
                    if (diff > 0 && diff < plen) {
                        poff += diff; plen -= diff
                    } else {
                        plen = 0; accepted = false
                    }
                }
                if (plen > 0 && !clientFin) {
                    val space = QUEUE_MAX - queued
                    if (space <= 0) {
                        plen = 0; accepted = false
                    } else {
                        if (plen > space) { plen = space; accepted = false }
                        outQueue.put(p.buf.copyOfRange(poff, poff + plen))
                        queued += plen
                        rcvNxt += plen
                    }
                }
                sendAck()
            }
            if (flags and TCP_FIN != 0 && accepted && !clientFin) {
                val finSeq = p.seq + p.tcpPayloadLen
                if (finSeq == rcvNxt) {
                    rcvNxt += 1
                    clientFin = true
                    outQueue.put(EOF)
                    sendAck()
                }
            }
            trySendLocked()
            if (clientFin && finAcked) closeInternalLocked()
        }
    }

    // ------------------------------------------------------------------ us -> app

    private fun windowField(): Int {
        val avail = (QUEUE_MAX - queued).coerceAtLeast(0)
        advertised = avail
        return (avail shr ourWs).coerceAtMost(0xFFFF)
    }

    private fun emit(ack: Int, flags: Int, data: ByteArray? = null, off: Int = 0, len: Int = 0,
                     options: ByteArray? = null, seqOverride: Int? = null) {
        val window = if (flags and TCP_SYN != 0) (QUEUE_MAX).coerceAtMost(0xFFFF) else windowField()
        val n = PacketBuilder.tcpInto(txBuf, localAddr, localPort, clientAddr, clientPort,
            seqOverride ?: sndNxt, ack, flags, window, options, data, off, len)
        eng.writeTun(txBuf, n)
    }

    private fun sendAck() = emit(rcvNxt, TCP_ACK)

    private fun sendSynAck() {
        val opts = if (clientWs >= 0) byteArrayOf(2, 4, (mssAdv() shr 8).toByte(), mssAdv().toByte(), 1, 3, 3, ourWs.toByte())
        else byteArrayOf(2, 4, (mssAdv() shr 8).toByte(), mssAdv().toByte())
        emit(rcvNxt, TCP_SYN or TCP_ACK, options = opts, seqOverride = iss)
        sndNxt = iss + 1
        synAckTime = System.currentTimeMillis()
        synAckCount++
    }

    private fun mssAdv() = TunEngine.MTU - if (localAddr.size == 16) 60 else 40

    private fun trySendLocked() {
        if (state != State.ESTABLISHED) return
        var off = sndNxt - sndUna
        if (finSent) return
        var usable = clientWnd - off
        while (off < inLen && usable > 0) {
            val seg = minOf(mss, inLen - off, usable)
            emit(rcvNxt, TCP_ACK or TCP_PSH, inBuf, inStart + off, seg)
            sndNxt += seg
            off += seg
            usable -= seg
        }
        if (remoteEof && !finSent && off == inLen) {
            emit(rcvNxt, TCP_FIN or TCP_ACK)
            sndNxt += 1
            finSent = true
        }
    }

    // ------------------------------------------------------------------ threads

    private fun readerLoop() {
        val buf = ByteArray(65536)
        val f = fd!!
        try {
            synchronized(lock) {
                while (state == State.SYN_RCVD) lock.wait(1000)
                if (state == State.CLOSED) return
            }
            while (true) {
                val n = try { Os.read(f, buf, 0, buf.size) } catch (e: ErrnoException) {
                    if (e.errno == EINTR) continue else throw e
                }
                if (n <= 0) break
                gotData = true
                Stats.bytesDown.addAndGet(n.toLong())
                synchronized(lock) {
                    while (inLen >= IN_MAX && state == State.ESTABLISHED) lock.wait(1000)
                    if (state == State.CLOSED) return
                    if (inStart + inLen + n > inBuf.size) {
                        // compact once (rare), then grow if still too small
                        if (inStart > 0) { System.arraycopy(inBuf, inStart, inBuf, 0, inLen); inStart = 0 }
                        if (inLen + n > inBuf.size) inBuf = inBuf.copyOf(maxOf(inBuf.size * 2, inLen + n))
                    }
                    System.arraycopy(buf, 0, inBuf, inStart + inLen, n)
                    inLen += n
                    trySendLocked()
                }
            }
            synchronized(lock) {
                remoteEof = true
                trySendLocked()
            }
        } catch (t: Throwable) {
            if (fakeSent && !gotData && (t as? ErrnoException)?.errno == ECONNRESET && eng.fakeBroken.putIfAbsent(remote, true) == null) {
                AppLog.w("${FlowKey.fmt(remote)}: máy chủ reset sau gói giả (TTL lọt tới server) — tắt fake cho IP này")
            }
            if (!closed.get()) {
                AppLog.d("TCP $key: remote: ${t.message}")
                abort()
            }
        } finally {
            threadDone()
        }
    }

    private fun writerLoop() {
        val f = fd!!
        try {
            while (true) {
                val chunk = outQueue.take()
                if (chunk === CLOSE) break
                if (chunk === EOF) {
                    runCatching { Os.shutdown(f, SHUT_WR) }
                    break
                }
                eng.desync.process(ci, chunk, chunk.size, this)
                synchronized(lock) {
                    queued -= chunk.size
                    // window update when the advertised window opens up again
                    if (advertised < mss * 2 && QUEUE_MAX - queued >= QUEUE_MAX / 4 && state == State.ESTABLISHED) sendAck()
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) {
                AppLog.d("TCP $key: write: ${t.message}")
                abort()
            }
        } finally {
            threadDone()
        }
    }

    private fun threadDone() {
        if (threadsLeft.decrementAndGet() <= 0) {
            runCatching { pfd?.close() }
        }
    }

    // ------------------------------------------------------------------ RemoteWriter

    override fun write(b: ByteArray, off: Int, len: Int) {
        val f = fd ?: throw IOException("closed")
        var o = off
        var l = len
        while (l > 0) {
            val n = try { Os.write(f, b, o, l) } catch (e: ErrnoException) {
                if (e.errno == EINTR) continue else throw e
            }
            o += n; l -= n
        }
        Stats.bytesUp.addAndGet(len.toLong())
    }

    override fun flushSegment() {
        if (NativeBridge.loaded) NativeBridge.waitSent(rawFd, 50) else Thread.sleep(3)
    }

    private fun setTtl(ttl: Int) {
        val f = fd ?: return
        if (v6) Os.setsockoptInt(f, IPPROTO_IPV6, IPV6_UNICAST_HOPS, ttl)
        else Os.setsockoptInt(f, IPPROTO_IP, IP_TTL, ttl)
    }

    override fun writeWithTtl(b: ByteArray, off: Int, len: Int, ttl: Int): Boolean {
        try {
            setTtl(ttl)
        } catch (e: ErrnoException) {
            return false
        }
        try {
            write(b, off, len)
            flushSegment()
        } finally {
            runCatching { setTtl(defTtl) }
        }
        return true
    }

    override fun sendFake(fake: ByteArray, real: ByteArray, off: Int, n: Int, ttl: Int): Boolean {
        if (!NativeBridge.loaded || rawFd < 0) return false
        if (eng.fakeBroken.containsKey(remote)) return false
        val r = NativeBridge.sendFake(rawFd, fake, real, off, n, ttl, defTtl, v6)
        if (r != n) {
            AppLog.d("TCP $key: sendFake failed ($r)")
            if (r > 0) throw IOException("partial fake")
            return false
        }
        Stats.bytesUp.addAndGet(n.toLong())
        fakeSent = true
        return true
    }

    @Volatile private var fakeSent = false
    @Volatile private var gotData = false

    override fun incomingTtl(): Int? = eng.prober.get(remoteBytes)

    // ------------------------------------------------------------------ lifecycle

    /** Periodic timer from the engine (retransmission, timeouts). */
    fun tick(now: Long) {
        synchronized(lock) {
            if (state == State.CLOSED) return
            when (state) {
                State.SYN_RCVD -> if (now - synAckTime > 1000) {
                    if (synAckCount > 6) { abortLocked(); return }
                    sendSynAck()
                }
                State.ESTABLISHED -> {
                    val outstanding = sndNxt - sndUna
                    if (outstanding > 0 && now - lastProgress > RTO_MS) {
                        if (++retransmits > 12) { abortLocked(); return }
                        // go-back-N from snd_una
                        sndNxt = sndUna
                        if (finSent && !finAcked) finSent = false
                        lastProgress = now
                        trySendLocked()
                    } else if (outstanding == 0 && inLen > 0 && clientWnd <= 0 && now - lastProgress > 1000) {
                        // zero window probe: an old segment makes the app re-announce its window
                        emit(rcvNxt, TCP_ACK, seqOverride = sndNxt - 1)
                        lastProgress = now
                    }
                }
                else -> {}
            }
            val idle = now - lastActivity
            val limit = if (clientFin || finSent) 60_000L else IDLE_TIMEOUT_MS
            if (idle > limit) closeInternalLocked()
        }
    }

    /** Reset towards the app and close (remote error, network change). */
    fun abort() = synchronized(lock) { abortLocked() }

    private fun abortLocked() {
        if (state == State.CLOSED) return
        if (state != State.CONNECTING) emit(rcvNxt, TCP_RST or TCP_ACK)
        closeInternalLocked()
    }

    private fun closeInternal() = synchronized(lock) { closeInternalLocked() }

    private fun closeInternalLocked() {
        if (!closed.compareAndSet(false, true)) return
        state = State.CLOSED
        lock.notifyAll()
        Stats.tcpActive.decrementAndGet()
        eng.removeTcp(key, this)
        outQueue.clear()
        outQueue.put(CLOSE)
        fd?.let { runCatching { Os.shutdown(it, SHUT_RDWR) } }
        if (threadsLeft.get() <= 0) runCatching { pfd?.close() }
    }

    companion object {
        private val EOF = ByteArray(0)
        private val CLOSE = ByteArray(0)
        const val QUEUE_MAX = 256 * 1024
        const val IN_MAX = 512 * 1024
        const val OUR_WS = 3
        const val RTO_MS = 800L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val IDLE_TIMEOUT_MS = 15 * 60_000L

        fun seqGt(a: Int, b: Int) = a - b > 0
    }
}
