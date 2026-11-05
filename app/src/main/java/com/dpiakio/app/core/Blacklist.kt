package com.dpiakio.app.core

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where a --blacklist comes from. Stored as a plain id in [DpiOptions.blacklists]. */
object BlacklistSource {
    const val RUSSIA = "russia"     // russia-blacklist.txt shipped with GoodbyeDPI 0.2.2 (assets)
    const val CUSTOM = "custom"     // user-edited list
    const val REMOTE = "remote"     // downloaded by "0_russia_update_blacklist_file.cmd" equivalent

    const val UPDATE_URL = "https://antizapret.prostovpn.org/domains-export.txt"

    fun fileNameOf(id: String) = when (id) {
        RUSSIA -> "russia-blacklist.txt"
        REMOTE -> "russia-blacklist-updated.txt"
        else -> "custom-blacklist.txt"
    }

    fun fromFileName(name: String): String {
        val n = name.substringAfterLast('\\').substringAfterLast('/').lowercase()
        return when {
            n.contains("updated") -> REMOTE
            n.contains("russia") -> RUSSIA
            else -> CUSTOM
        }
    }

    fun title(id: String) = when (id) {
        RUSSIA -> "Russia blacklist (kèm theo bản phát hành)"
        REMOTE -> "Russia blacklist (cập nhật từ antizapret)"
        else -> "Danh sách tuỳ chỉnh"
    }

    fun customFile(ctx: Context) = File(ctx.filesDir, "custom-blacklist.txt")
    fun remoteFile(ctx: Context) = File(ctx.filesDir, "russia-blacklist-updated.txt")

    fun readCustom(ctx: Context): String =
        customFile(ctx).takeIf { it.exists() }?.readText() ?: ""

    fun writeCustom(ctx: Context, text: String) = customFile(ctx).writeText(text)

    /** Equivalent of the release's bitsadmin download script. Blocking; call off the main thread. */
    fun downloadRemote(ctx: Context): Int {
        val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val tmp = File(ctx.filesDir, "remote.tmp")
            conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            val count = tmp.useLines { l -> l.count { it.trim().length >= 2 } }
            if (count == 0) throw IllegalStateException("Danh sách rỗng")
            tmp.renameTo(remoteFile(ctx))
            return count
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Port of blackwhitelist.c: exact hostname set; a host matches if it or any of its
 * parent domains is in the set ("sub.example.com" matches "example.com").
 */
class Blacklist {
    private val hosts = HashSet<String>(1 shl 17)
    val size get() = hosts.size

    fun addLines(lines: Sequence<String>): Int {
        var cnt = 0
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n').trim()
            if (line.length > HOST_MAXLEN || line.length < 2) continue
            if (line.startsWith("#")) continue
            if (hosts.add(line)) cnt++
        }
        return cnt
    }

    fun load(ctx: Context, id: String): Int = when (id) {
        BlacklistSource.RUSSIA -> ctx.assets.open("russia-blacklist.txt").bufferedReader().useLines { addLines(it) }
        BlacklistSource.REMOTE -> BlacklistSource.remoteFile(ctx).takeIf { it.exists() }
            ?.bufferedReader()?.useLines { addLines(it) } ?: -1
        else -> BlacklistSource.customFile(ctx).takeIf { it.exists() }
            ?.bufferedReader()?.useLines { addLines(it) } ?: -1
    }

    /** blackwhitelist_check_hostname() */
    fun check(host: String): Boolean {
        if (host.length > HOST_MAXLEN) return false
        if (hosts.contains(host)) return true
        var dot = host.indexOf('.')
        while (dot >= 0) {
            if (hosts.contains(host.substring(dot + 1))) return true
            dot = host.indexOf('.', dot + 1)
        }
        return false
    }

    companion object {
        const val HOST_MAXLEN = 253
    }
}
