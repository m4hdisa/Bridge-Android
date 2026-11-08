package io.github.m4hdisa.bridge

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** A WARP failure with a message for the user, in their language. */
class WarpException(val res: Int, message: String) : IOException(message)

/**
 * Runs Aether (shipped as libaether.so, like Xray) for Free WARP, the way Bridge on Windows does:
 * the account is made once and kept, the fast ways through the network are tried at once and
 * the first that carries real traffic wins. Bridge is left out of the VPN, so Aether reaches
 * Cloudflare directly. Psiphon, when chosen, runs inside the WARP tunnel for an exit outside Iran.
 */
class WarpRunner(private val context: Context, private val log: File) {
    private val dir = File(context.filesDir, "warp")
    private val libs = context.applicationInfo.nativeLibraryDir
    private val aether = File(libs, "libaether.so")
    private val psiphonBin = File(libs, "libpsiphon.so")
    private val config get() = File(dir, "aether.toml").path
    private val processes = CopyOnWriteArraySet<Process>()

    @Volatile
    private var cancelled = false

    @Volatile
    private var winner: Process? = null

    /** The exit country Aether or Psiphon reported, e.g. "DE". */
    @Volatile
    var exit: String? = null
        private set

    val available get() = isAvailable(context)

    private class Won(val method: Warp.Method, val port: Int, val process: Process, val psiphonPort: Int?, val gateway: String?)

    private class Lost(val method: Warp.Method, val reason: String)

    /**
     * Connects and returns the local SOCKS port sing-box should use. `progress` gets a string
     * resource for the dashboard; `onDied` runs if the tunnel stops later on.
     */
    fun start(choice: Warp.Choice, region: String?, progress: (Int) -> Unit, onDied: () -> Unit): Int {
        if (!available) throw WarpException(R.string.warp_unavailable, "Free WARP needs Android 10 or newer")
        dir.mkdirs()
        ensureAccount(progress)
        val psiphon = choice == Warp.Choice.Psiphon && psiphonBin.exists()
        val methods = Warp.methods(choice)
        // The way and gateway that worked last time go first, alone and without a scan: that
        // takes seconds. Only if they don't work any more is every way tried.
        val last = lastWay()?.takeIf { (method, _) -> method in methods && (choice != Warp.Choice.Foreign || method.twoHops) }
        val won = last?.let { (method, gateway) ->
            note("Free WARP: trying ${method.label} on $gateway, which worked last time")
            try {
                race(listOf(method), false, psiphon, region, progress, gateway, QUICK_MS)
            } catch (e: WarpException) {
                note("Free WARP: it doesn't work now; looking for another way")
                File(dir, LAST_WAY_FILE).delete()
                null
            }
        } ?: race(methods, choice == Warp.Choice.Foreign, psiphon, region, progress)
        won.gateway?.let { gateway -> runCatching { File(dir, LAST_WAY_FILE).writeText("${won.method.name} $gateway") } }
        winner = won.process
        Thread {
            runCatching { won.process.waitFor() }
            if (!cancelled && winner === won.process) {
                note("WARP core stopped unexpectedly")
                onDied()
            }
        }.apply { isDaemon = true }.start()
        note("Free WARP connected with ${won.method.label}${exit?.let { ", exit $it" } ?: ""} (local SOCKS ${won.port})")
        val port = won.psiphonPort ?: return won.port
        progress(R.string.progress_psiphon)
        note("Free WARP: connecting Psiphon through WARP${region?.let { ", asking for an exit in $it" } ?: ""}")
        val deadline = System.currentTimeMillis() + PSIPHON_READY_MS
        while (System.currentTimeMillis() < deadline) {
            checkCancelled()
            val country = runCatching { traceCountry(port) }.getOrNull()
            if (country != null) {
                exit = country
                note("Free WARP: Psiphon connected, exit $country (local SOCKS $port)")
                return port
            }
            Thread.sleep(2_000)
        }
        throw WarpException(R.string.warp_psiphon_failed, "Psiphon didn't connect through WARP within ${PSIPHON_READY_MS / 1000} s")
    }

    /** Stops every Aether this runner started (each stops its own Psiphon). */
    fun stop() {
        cancelled = true
        winner = null
        for (p in processes) p.destroy()
        processes.clear()
    }

    /**
     * Swaps a public starter account for the user's own, made through the VPN that is up now
     * (where Cloudflare's sign-up can be reached). It is used from the next connection.
     */
    fun replaceStarterAccount(proxy: LocalAccess) {
        if (!File(dir, Warp.STARTER_MARK).exists()) return
        val own = File(dir, "own-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            note("Free WARP: making your own WARP account through the tunnel")
            runToEnd(Warp.registerArgs(File(own, "aether.toml").path, upstream = "socks5://${proxy.user}:${proxy.secret}@127.0.0.1:${proxy.port}"), 60_000)
            val made = File(own, Warp.IDENTITY_FILE)
            if (!made.exists()) return note("Free WARP: your own account could not be made yet; Bridge tries again next time")
            made.copyTo(File(dir, Warp.IDENTITY_FILE), overwrite = true)
            File(dir, Warp.STARTER_MARK).delete()
            note("Free WARP: your own WARP account is ready and is used from the next connection")
        } finally {
            own.deleteRecursively()
        }
    }

    /** The method and gateway that worked last time, if they are still a WARP method and gateway. */
    private fun lastWay(): Pair<Warp.Method, String>? {
        val parts = runCatching { File(dir, LAST_WAY_FILE).readText().trim().split(' ') }.getOrNull() ?: return null
        val method = Warp.Method.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return null
        val gateway = parts.getOrNull(1)?.takeIf { Warp.isGateway(it) } ?: return null
        return method to gateway
    }

    /** Makes the WARP account ahead of time, when Free WARP is chosen, so connecting needn't wait for it. */
    fun prepareAccount() {
        if (!available) return
        dir.mkdirs()
        runCatching { ensureAccount {} }
    }

    /**
     * Makes the WARP account once. Iran's firewall often resets the TLS handshake to Cloudflare's
     * sign-up service; a fragmented handshake gets through on many networks. If nothing does, a
     * starter account from Bridge's daily list connects, and replaceStarterAccount then makes the
     * user's own through that tunnel.
     */
    private fun ensureAccount(progress: (Int) -> Unit) {
        val identity = File(dir, Warp.IDENTITY_FILE)
        if (identity.exists()) return
        progress(R.string.progress_warp_account)
        // One at a time: an account made ahead of time and a connect must not both sign up.
        synchronized(ACCOUNT_LOCK) { makeAccount(identity) }
    }

    private fun makeAccount(identity: File) {
        if (identity.exists()) return
        note("Free WARP: creating a WARP account (first use only)")
        note("Free WARP: creating the account directly, with TLS fragment")
        runToEnd(Warp.registerArgs(config, listOf("--fragment")), REGISTER_MS)
        if (identity.exists()) return note("Free WARP: account created")
        checkCancelled()
        if (useStarterAccount()) return
        note("Free WARP: creating the account directly")
        runToEnd(Warp.registerArgs(config), REGISTER_MS)
        if (identity.exists()) return note("Free WARP: account created")
        checkCancelled()
        throw WarpException(R.string.warp_no_account, "Free WARP could not create a WARP account: Cloudflare's WARP service is blocked on this network right now")
    }

    private fun useStarterAccount(): Boolean {
        for (url in Warp.STARTER_LIST) {
            try {
                val page = Net.direct(url)
                if (!page.ok) throw IOException("HTTP ${page.code}")
                val links = page.body.lines().map { it.trim() }.filter { it.startsWith(Warp.SHARE_SCHEME) }
                if (links.isEmpty()) throw IOException("the list is empty")
                File(dir, Warp.IDENTITY_FILE).writeText(Warp.identityOf(links.random()))
                File(dir, Warp.STARTER_MARK).writeText(url)
                note("Free WARP: Cloudflare's sign-up is blocked here, so Bridge connects with a starter account from its daily list and then makes your own account through it")
                return true
            } catch (e: Exception) {
                note("Free WARP: the starter accounts from ${URL(url).host} didn't come (${e.message})")
            }
        }
        return false
    }

    /**
     * Runs the methods at once, each on its own port. The first that works wins; with a foreign
     * exit wanted, one with two hops gets a minute longer, since its exit is outside Iran.
     */
    private fun race(
        methods: List<Warp.Method>,
        foreign: Boolean,
        psiphon: Boolean,
        region: String?,
        progress: (Int) -> Unit,
        peer: String? = null,
        timeoutMs: Long = ATTEMPT_MS,
    ): Won {
        val results = LinkedBlockingQueue<Any>()
        if (peer == null) note("Free WARP: looking for a way through (${methods.joinToString { it.label }})")
        progress(if (peer != null) R.string.progress_starting else R.string.progress_warp_search)
        val state = File(dir, STATE_FILE)
        val missedAt = runCatching { state.readText().trim().toLong() }.getOrDefault(0L)
        val waitForTwoHops = foreign && System.currentTimeMillis() - missedAt >= TWO_HOPS_RETRY_MS
        for (method in methods) {
            val port = freePort()
            val psiphonPort = if (psiphon) freePort() else null
            val args = Warp.args(method, port, config, psiphonPort?.let { Warp.Psiphon(psiphonBin.path, it, region) }, peer)
            var won = false
            var refused = 0
            var gateway: String? = null
            val process = launch(args, "warp ${method.name.lowercase()}") { process, text ->
                if (won) {
                    if (exit == null) exit = Warp.exitCountry(text)
                    return@launch
                }
                Warp.exitCountry(text)?.let { exit = it }
                // The first gateway is the outer hop with two hops, the one to try first next time.
                if (gateway == null) gateway = Warp.gateway(text)
                if (Warp.EXIT_REFUSED.containsMatchIn(text) && ++refused >= Warp.MAX_EXIT_REFUSED) {
                    process.destroy()
                    results.offer(Lost(method, "Cloudflare gave it an Iranian exit $refused times"))
                } else if (Warp.NO_IPV6.containsMatchIn(text)) {
                    process.destroy()
                    results.offer(Lost(method, "this network has no IPv6"))
                } else if (Warp.ready(text, port)) {
                    won = true
                    results.offer(Won(method, port, process, psiphonPort, gateway ?: peer))
                }
            }
            Thread {
                runCatching { process.waitFor() }
                if (!won) results.offer(Lost(method, "stopped"))
            }.apply { isDaemon = true }.start()
        }
        val wanted = methods.filter { it.twoHops }.toMutableSet()
        var pending = methods.size
        var fallback: Won? = null
        var waitUntil = Long.MAX_VALUE
        val deadline = System.currentTimeMillis() + timeoutMs
        val reasons = mutableListOf<String>()
        fun finish(w: Won): Won {
            for (p in processes) if (p !== w.process) p.destroy()
            processes.retainAll(setOf(w.process))
            if (foreign) runCatching { state.writeText(if (w.method.twoHops) "0" else System.currentTimeMillis().toString()) }
            return w
        }
        while (true) {
            checkCancelled()
            val now = System.currentTimeMillis()
            fallback?.let { if (now >= waitUntil) return finish(it) }
            if (fallback == null && now >= deadline) break
            when (val r = results.poll(500, TimeUnit.MILLISECONDS)) {
                is Won -> {
                    if (!waitForTwoHops || r.method.twoHops || wanted.isEmpty()) return finish(r)
                    if (fallback == null) {
                        fallback = r
                        waitUntil = now + TWO_HOPS_WAIT_MS
                        note("Free WARP: ${r.method.label} works; waiting up to ${TWO_HOPS_WAIT_MS / 1000} s for a way with two hops, which exits outside Iran")
                        progress(R.string.progress_warp_foreign)
                    }
                }
                is Lost -> {
                    pending--
                    wanted -= r.method
                    reasons += "${r.method.label}: ${r.reason}"
                    note("Free WARP: ${r.method.label} ${r.reason}")
                    fallback?.let { if (wanted.isEmpty()) return finish(it) }
                    if (pending == 0 && fallback == null) break
                }
            }
        }
        stop()
        cancelled = false
        throw WarpException(R.string.warp_no_way, "Free WARP could not connect on this network. ${reasons.joinToString("; ")}")
    }

    /** Starts Aether with its output in the log; `onLine` sees each message. */
    private fun launch(args: List<String>, tag: String, onLine: (Process, String) -> Unit = { _, _ -> }): Process {
        val builder = ProcessBuilder(listOf(aether.path) + args).directory(dir).redirectErrorStream(true)
        builder.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        val process = builder.start()
        processes += process
        runCatching { process.outputStream.close() }
        Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val text = Warp.message(line)
                        append("[$tag] $text")
                        onLine(process, text)
                    }
                }
            }
            processes -= process
        }.apply { isDaemon = true }.start()
        return process
    }

    /** Runs one Aether to the end, or stops it after `timeoutMs`. */
    private fun runToEnd(args: List<String>, timeoutMs: Long) {
        val process = launch(args, "warp")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (exitCode(process) == null) {
            if (cancelled || System.currentTimeMillis() > deadline) {
                process.destroy()
                break
            }
            Thread.sleep(250)
        }
        processes -= process
    }

    private fun exitCode(p: Process): Int? = try {
        p.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    private fun checkCancelled() {
        if (cancelled) throw IOException("cancelled")
    }

    /** Cloudflare's view of a request through `port`: its country, or null if nothing came through. */
    private fun traceCountry(port: Int): String? {
        val connection = URL(TRACE_URL).openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) as HttpsURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return body.lines().firstOrNull { it.startsWith("loc=") }?.removePrefix("loc=")?.takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    private fun note(text: String) = append(text)

    @Synchronized
    private fun append(text: String) {
        runCatching { log.appendText("$text\n") }
        Log.i(TAG, text)
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    companion object {
        private const val TAG = "Bridge"
        private const val STATE_FILE = "bridge-warp-state"
        private const val LAST_WAY_FILE = "bridge-warp-last"
        private const val QUICK_MS = 20_000L
        private val ACCOUNT_LOCK = Any()
        private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
        private const val ATTEMPT_MS = 75_000L
        private const val REGISTER_MS = 90_000L
        private const val TWO_HOPS_WAIT_MS = 60_000L

        /** After the two-hop ways miss, how long the foreign choice connects without waiting for them. */
        private const val TWO_HOPS_RETRY_MS = 30 * 60 * 1000L
        private const val PSIPHON_READY_MS = 90_000L

        fun isAvailable(context: Context) =
            Build.VERSION.SDK_INT >= Warp.MIN_SDK && File(context.applicationInfo.nativeLibraryDir, "libaether.so").exists()
    }
}
