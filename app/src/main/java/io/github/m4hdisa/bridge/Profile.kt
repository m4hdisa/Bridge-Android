package io.github.m4hdisa.bridge

import android.content.Context
import java.io.File
import java.io.FileNotFoundException

/** Turns what the user set into what BoxConfig needs. */
object Profile {
    /** sing-box keeps every server in memory; a few hundred is far more than anyone picks from. */
    private const val MAX_SERVERS = 300

    /** Auto tests its servers every few minutes, so it gets the best few only. */
    private const val MAX_AUTO = 30

    fun input(store: Store, rulesDir: String?): BoxInput {
        val servers = store.servers
        val parsed = servers.mapNotNull { s -> runCatching { s.id to ShareLink.parse(s.link) }.getOrNull() }
        val selected = store.selected
        // Free WARP needs no server of the user's own; BridgeVpnService adds its port.
        if (parsed.isEmpty() && Warp.isWarp(selected)) return BoxInput(emptyList(), selected, emptyList(), store.bypassIran, rulesDir, null, null, null, fakeIp = store.fakeIp)
        if (parsed.isEmpty()) throw LinkException(if (servers.isEmpty()) "Add a server first" else "None of the servers can be used in this version")
        val kept = parsed.take(MAX_SERVERS).let { first ->
            if (first.any { it.first == selected }) first else first + parsed.filter { it.first == selected }
        }
        val usable = kept.map { it.first }.toSet()
        val publicIds = store.subscriptions.filter { it.public }.map { it.id }.toSet()
        val auto = Servers.autoGroup(servers.filter { it.id in usable }, publicIds, MAX_AUTO).map { it.id }
        return BoxInput(kept, selected, auto, store.bypassIran, rulesDir, null, null, null, fakeIp = store.fakeIp)
    }
}

/** The Iranian site and address lists (Chocolate4U/Iran-sing-box-rules), shipped in the APK and copied out for sing-box. */
object Rules {
    private val FILES = listOf("geoip-ir.srs", "geosite-ir.srs")

    /** The folder holding both files, or null if this build has none. */
    fun install(context: Context): String? {
        val dir = File(context.filesDir, "rules").apply { mkdirs() }
        val stamp = File(dir, "version")
        val version = BuildConfig.VERSION_CODE.toString()
        if (stamp.exists() && stamp.readText() == version && FILES.all { File(dir, it).length() > 0 }) return dir.path
        return try {
            for (name in FILES) {
                context.assets.open("rules/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            stamp.writeText(version)
            dir.path
        } catch (_: FileNotFoundException) {
            null
        }
    }
}
