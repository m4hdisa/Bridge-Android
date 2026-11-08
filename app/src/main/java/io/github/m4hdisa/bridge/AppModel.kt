package io.github.m4hdisa.bridge

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A short message for the bar at the bottom; `res` is formatted with `args` where it is shown. */
data class Message(@param:StringRes val res: Int, val args: List<Any> = emptyList(), val action: Action? = null, val id: Long = System.nanoTime()) {
    enum class Action { Reconnect }
}

/** An app that can be put on the VPN. */
data class InstalledApp(val label: String, val packageName: String, val icon: Drawable?)

/** Where traffic leaves the VPN. */
data class Exit(val ip: String, val country: String, val city: String?, val at: Pair<Double, Double>?)

/** Traffic through the VPN: bytes a second now, and in all since connecting. */
data class Traffic(val down: Long = 0, val up: Long = 0, val totalDown: Long = 0, val totalUp: Long = 0)

/** A speed test through the VPN, in Mbps; null while not measured yet. */
data class SpeedResult(val running: Boolean = false, val down: Double? = null, val up: Double? = null, val failed: Boolean = false)

/** The last real request through the VPN, repeated while connected (the dashboard's connection check). */
data class Health(
    val state: State = State.Idle,
    val delay: Int = 0,
    val ip: String? = null,
    val country: String? = null,
    val city: String? = null,
    /** Where the exit is on the map, when the lookup said; otherwise the country's middle is used. */
    val at: Pair<Double, Double>? = null,
) {
    enum class State { Idle, Checking, Ok, Failed }
}

/**
 * Everything the screens show and do. Kept by the activity; the VPN itself runs in
 * BridgeVpnService and reports through VpnState.
 */
class AppModel(context: Context) {
    private val app = context.applicationContext
    val store = Store(app)
    private val work = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val parsed = ConcurrentHashMap<String, Result<ParsedLink>>()

    var status by mutableStateOf(VpnState.status)
        private set
    var vpnError by mutableStateOf(VpnState.error)
        private set
    var localError by mutableStateOf<Int?>(null)
        private set
    var servers by mutableStateOf(store.servers)
        private set
    var subscriptions by mutableStateOf(store.subscriptions)
        private set
    var selected by mutableStateOf(store.selected)
        private set
    var perApp by mutableStateOf(store.perApp)
        private set
    var chosenApps by mutableStateOf(store.apps)
        private set
    var bypassIran by mutableStateOf(store.bypassIran)
        private set
    var fakeIp by mutableStateOf(store.fakeIp)
        private set
    var detailedLog by mutableStateOf(store.detailedLog)
        private set
    var warpChoice by mutableStateOf(store.warpChoice)
        private set
    var psiphonRegion by mutableStateOf(store.psiphonRegion)
        private set

    /** Free WARP can run on this phone (Android 10 and up, and a build that has Aether). */
    val warpAvailable = WarpRunner.isAvailable(app)

    /** While connecting: what Bridge is doing now. */
    var progress by mutableStateOf(VpnState.progress)
        private set
    var traffic by mutableStateOf(Traffic())
        private set
    var speed by mutableStateOf(SpeedResult())
        private set

    /** The phone's own country, outside the VPN (Cloudflare's answer), for the map. */
    var homeCountry by mutableStateOf<String?>(null)
        private set

    /** When the VPN came up, for "Connected for …". */
    var connectedAt by mutableStateOf<Long?>(null)
        private set

    /** What Auto is using now, from the Clash API. */
    var currentTag by mutableStateOf<String?>(null)
        private set

    /** What's being done in the background, shown as a bar with this text. */
    var busy by mutableStateOf<Int?>(null)
        private set
    private var busyCount = 0

    var message by mutableStateOf<Message?>(null)
        private set
    var health by mutableStateOf(Health())
        private set
    var installedApps by mutableStateOf<List<InstalledApp>?>(null)
        private set

    /** Set by "Reconnect": start again as soon as the VPN has stopped. */
    private var pendingStart = false
    private var healthRound = 0

    private val stateListener: () -> Unit = { onVpnState() }

    init {
        VpnState.addListener(stateListener)
        onVpnState()
    }

    fun close() {
        VpnState.removeListener(stateListener)
        work.shutdownNow()
    }

    private fun ui(block: () -> Unit) = main.post(block)

    private fun reload() {
        servers = store.servers
        subscriptions = store.subscriptions
        selected = store.selected
    }

    fun say(@StringRes res: Int, vararg args: Any, action: Message.Action? = null) {
        message = Message(res, args.toList(), action)
    }

    fun messageShown(m: Message) {
        if (message?.id == m.id) message = null
    }

    private fun markBusy(@StringRes text: Int?) {
        if (text != null) {
            busyCount++
            busy = text
        } else {
            busyCount = (busyCount - 1).coerceAtLeast(0)
            if (busyCount == 0) busy = null
        }
    }

    // ---------- connecting ----------

    val running get() = status == VpnState.Status.Running

    private fun onVpnState() {
        val wasRunning = status == VpnState.Status.Running
        status = VpnState.status
        vpnError = VpnState.error
        progress = VpnState.progress
        if (wasRunning && status == VpnState.Status.Running) return
        if (status == VpnState.Status.Stopped && pendingStart) {
            pendingStart = false
            pendingPermission?.let { connect(it) }
        }
        if (status == VpnState.Status.Running) {
            connectedAt = System.currentTimeMillis()
            findHome()
            startHealthChecks()
            startTraffic()
        } else {
            connectedAt = null
            currentTag = null
            healthRound++
            health = Health()
            traffic = Traffic()
            speed = SpeedResult()
        }
    }

    private var pendingPermission: ((Intent) -> Unit)? = null

    /** Connects, or disconnects when connected. `askPermission` shows Android's VPN prompt. */
    fun toggle(askPermission: (Intent) -> Unit) {
        if (status != VpnState.Status.Stopped) {
            stop()
            return
        }
        connect(askPermission)
    }

    private fun connect(askPermission: (Intent) -> Unit) {
        pendingPermission = askPermission
        if (store.servers.isEmpty() && store.selected != Warp.ID) {
            localError = R.string.no_servers
            return
        }
        if (store.perApp && store.apps.isEmpty()) {
            localError = R.string.choose_apps
            return
        }
        val request = VpnService.prepare(app)
        if (request != null) askPermission(request) else start()
    }

    /** After Android's VPN prompt. */
    fun permissionResult(granted: Boolean) {
        if (granted) start() else localError = R.string.vpn_permission_denied
    }

    private fun start() {
        localError = null
        currentTag = null
        ContextCompat.startForegroundService(app, Intent(app, BridgeVpnService::class.java))
    }

    private fun stop() {
        app.startService(Intent(app, BridgeVpnService::class.java).setAction(BridgeVpnService.ACTION_STOP))
    }

    fun reconnect() {
        if (status == VpnState.Status.Stopped) {
            pendingPermission?.let { connect(it) }
            return
        }
        pendingStart = true
        stop()
    }

    private fun noteReconnect() {
        if (running) say(R.string.applies_next, action = Message.Action.Reconnect)
    }

    // ---------- the connection check ----------

    /** A real request through the VPN now and every 30 seconds while connected; the exit address once per server. */
    private fun startHealthChecks() {
        val round = ++healthRound
        health = Health(Health.State.Checking)
        work.execute {
            var exitFor: String? = null
            while (round == healthRound) {
                val session = VpnState.session ?: break
                val delay = Clash.delay(session.clash, "proxy", 8_000)
                val now = Clash.current(session.clash)
                var ip = health.ip
                var country = health.country
                var city = health.city
                var at = health.at
                if (delay > 0 && (exitFor != now || ip == null)) {
                    val exit = exitLocation(session.proxy)
                    ip = exit?.ip
                    country = exit?.country
                    city = exit?.city
                    at = exit?.at
                    exitFor = now
                    // Remembered for the config's flag; Free WARP isn't in the list.
                    val id = now?.let { session.idOf(it) }
                    if (country != null && id != null && id != AUTO && id != Warp.ID) store.update(id) { it.copy(exit = country) }
                }
                ui {
                    if (round == healthRound) {
                        currentTag = now
                        health = if (delay > 0) Health(Health.State.Ok, delay, ip, country, city, at) else Health(Health.State.Failed)
                        servers = store.servers
                    }
                }
                repeat(30) { if (round == healthRound) Thread.sleep(1_000) }
            }
        }
    }

    /** Reads the speed every second while connected, from the Clash API. */
    private fun startTraffic() {
        val round = healthRound
        work.execute {
            var totalDown = 0L
            var totalUp = 0L
            while (round == healthRound) {
                val session = VpnState.session ?: break
                runCatching {
                    Clash.traffic(session.clash, { round == healthRound }) { up, down ->
                        totalDown += down
                        totalUp += up
                        val t = Traffic(down, up, totalDown, totalUp)
                        ui { if (round == healthRound) traffic = t }
                    }
                }
                if (round == healthRound) Thread.sleep(1_000)
            }
        }
    }

    /** For PageShots only: how a working connection looks, without a VPN. */
    internal fun pretend(health: Health, home: String, traffic: Traffic, speed: SpeedResult) {
        this.health = health
        homeCountry = home
        this.traffic = traffic
        this.speed = speed
    }

    /** Downloads and uploads through the VPN for a few seconds each. */
    fun testSpeed() {
        val session = VpnState.session?.takeIf { running } ?: return
        if (speed.running) return
        speed = SpeedResult(running = true)
        work.execute {
            val down = runCatching { Net.downloadSpeed(session.proxy) }.getOrNull()
            // Remembered on the config it went through, for the server list.
            val id = Clash.current(session.clash)?.let { session.idOf(it) }
            if (down != null && id != null && id != AUTO && id != Warp.ID) store.update(id) { it.copy(speed = down) }
            ui {
                speed = speed.copy(down = down)
                servers = store.servers
            }
            val up = runCatching { Net.uploadSpeed(session.proxy) }.getOrNull()
            ui { speed = SpeedResult(false, down, up, down == null && up == null) }
        }
    }

    /**
     * Where traffic leaves the VPN: IP, country code and city, from ipwho.is through the VPN, or
     * Cloudflare's own answer (no city) when that is blocked or slow.
     */
    private fun exitLocation(proxy: LocalAccess): Exit? {
        runCatching {
            val o = org.json.JSONObject(Net.through(proxy, "https://ipwho.is/", 8_000).body)
            val code = o.optString("country_code")
            if (o.optBoolean("success") && code.length == 2) {
                val at = if (o.has("latitude") && o.has("longitude")) o.getDouble("latitude") to o.getDouble("longitude") else null
                return Exit(o.optString("ip"), code.uppercase(), o.optString("city").ifEmpty { null }, at)
            }
        }
        return trace { Net.through(proxy, TRACE_URL, 10_000) }
    }

    private fun trace(get: () -> Page): Exit? {
        val body = runCatching { get().body }.getOrNull() ?: return null
        val fields = body.lines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val ip = fields["ip"] ?: return null
        val code = fields["loc"]?.takeIf { it.length == 2 && it != "XX" } ?: return null
        return Exit(ip, code.uppercase(), null, null)
    }

    /**
     * The phone's own country, for the map. Bridge stays outside the VPN, so this asks Cloudflare
     * directly, as Bridge's other requests do; only the country is kept, never the address.
     */
    private fun findHome() {
        if (homeCountry != null) return
        work.execute {
            val country = trace { Net.direct(TRACE_URL, 8_000) }?.country
            if (country != null) ui { homeCountry = country }
        }
    }

    /** Runs the check again at once. */
    fun checkNow() {
        if (running) startHealthChecks()
    }

    // ---------- servers ----------

    fun parse(server: Server): Result<ParsedLink> = parsed.getOrPut(server.link) { runCatching { ShareLink.parse(server.link) } }

    fun select(id: String) {
        store.selected = id
        selected = id
        // The WARP account is made now, so connecting needn't wait for it.
        if (id == Warp.ID && warpAvailable) work.execute { WarpRunner(app, File(app.filesDir, BridgeVpnService.WARP_LOG_FILE)).prepareAccount() }
        val session = VpnState.session
        if (session != null && running) {
            val tag = if (id == AUTO) AUTO.takeIf { session.tags.size > 1 } else session.tags[id]
            if (tag == null) {
                say(R.string.reconnect_needed, action = Message.Action.Reconnect)
            } else {
                work.execute {
                    Clash.select(session.clash, tag)
                    ui { checkNow() }
                }
            }
        }
    }

    /** The server traffic goes through now: the chosen one, or the one Auto is on. */
    fun activeServer(): Server? {
        val list = servers
        list.firstOrNull { it.id == selected }?.let { return it }
        if (list.size == 1) return list.first()
        val id = currentTag?.let { VpnState.session?.idOf(it) } ?: return null
        return list.firstOrNull { it.id == id }
    }

    val autoSelected get() = servers.size > 1 && selected != Warp.ID && servers.none { it.id == selected }

    val warpSelected get() = selected == Warp.ID

    /** A subscription address, or text with share links in it (or a JSON config). */
    fun addText(text: String) {
        val trimmed = text.trim()
        if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            addSubscription(trimmed)
            return
        }
        val added = store.addLinks(ShareLink.extract(trimmed))
        reload()
        if (added > 0) say(R.string.added_servers, added) else say(R.string.added_none)
    }

    fun addSubscription(url: String) {
        val existing = store.subscriptions.firstOrNull { it.url == url }
        val subscription = existing ?: Subscription(UUID.randomUUID().toString(), url, Uri.parse(url).host ?: url)
        markBusy(R.string.updating)
        work.execute {
            val result = runCatching { Subscriptions.fetch(url) }
            result.onSuccess { fetched ->
                store.replaceSubscription(subscription.copy(name = fetched.title ?: subscription.name, updatedAt = System.currentTimeMillis(), usage = fetched.usage ?: subscription.usage), fetched.links)
            }
            ui {
                markBusy(null)
                reload()
                result.onSuccess { say(R.string.sub_added, it.links.size) }
                    .onFailure { say(R.string.sub_failed, subscription.name, it.message ?: it.toString()) }
            }
        }
    }

    /** Every subscription, or (when the app opens) those older than a day. The public list is found again too. */
    fun updateSubscriptions(onlyStale: Boolean, then: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        val due = store.subscriptions.filter { !onlyStale || now - it.updatedAt > DateUtils.DAY_IN_MILLIS }
        if (due.isEmpty()) {
            if (!onlyStale && then == null) say(R.string.subs_updated)
            then?.invoke()
            return
        }
        if (due.any { it.public }) findPublic()
        val own = due.filter { !it.public }
        if (own.isEmpty()) return run { then?.invoke() }
        markBusy(R.string.updating)
        work.execute {
            val failures = mutableListOf<Pair<String, String>>()
            for (subscription in own) {
                runCatching { Subscriptions.fetch(subscription.url) }
                    .onSuccess { store.replaceSubscription(subscription.copy(name = it.title ?: subscription.name, updatedAt = System.currentTimeMillis(), usage = it.usage ?: subscription.usage), it.links) }
                    .onFailure { failures += subscription.name to (it.message ?: it.toString()) }
            }
            ui {
                markBusy(null)
                reload()
                val first = failures.firstOrNull()
                if (first != null) say(R.string.sub_failed, first.first, first.second) else if (!onlyStale) say(R.string.subs_updated)
                then?.invoke()
            }
        }
    }

    fun updateSubscription(subscription: Subscription) {
        if (subscription.public) findPublic() else addSubscription(subscription.url)
    }

    fun findPublic() {
        markBusy(R.string.public_downloading)
        work.execute {
            val result = runCatching {
                PublicConfigs.find { step -> ui { busy = if (step == "test") R.string.public_testing else R.string.public_downloading } }
            }
            result.onSuccess { found ->
                if (found.isNotEmpty()) {
                    val subscription = Subscription(PublicConfigs.SUBSCRIPTION_ID, "", app.getString(R.string.public_sub_name), true, System.currentTimeMillis())
                    store.replaceSubscription(subscription, found.map { it.first }) { i, _ -> "Public ${i + 1}" }
                    val delays = found.toMap()
                    store.setDelays(store.servers.filter { it.subscription == subscription.id }.mapNotNull { s -> delays[s.link]?.let { s.id to it } }.toMap())
                }
            }
            ui {
                markBusy(null)
                reload()
                result.onSuccess { found -> if (found.isEmpty()) say(R.string.public_none) else say(R.string.public_done, found.size) }
                    .onFailure { say(R.string.public_failed, it.message ?: it.toString()) }
            }
        }
    }

    /** Through the server when connected (a real request), otherwise how fast each address answers. `only` limits it to some servers. */
    fun measure(only: List<Server>? = null) {
        val list = only ?: store.servers
        if (list.isEmpty()) return
        val session = VpnState.session?.takeIf { running }
        markBusy(R.string.pinging)
        work.execute {
            val results = ConcurrentHashMap<String, Int>()
            val pool = Executors.newFixedThreadPool(if (session != null) 8 else 32)
            try {
                pool.invokeAll(
                    list.map { s ->
                        Callable {
                            val tag = session?.tags?.get(s.id)
                            val p = parse(s).getOrNull()
                            val delay = when {
                                session != null && tag != null -> Clash.delay(session.clash, tag)
                                p == null -> DELAY_FAILED
                                // Hysteria2 and TUIC run over UDP, so a TCP connection says nothing about them.
                                p.outbound.optString("type") in setOf("hysteria2", "tuic") -> null
                                else -> Net.tcpDelay(p.host, p.port)
                            }
                            if (delay != null) results[s.id] = delay
                            Unit
                        }
                    },
                )
            } finally {
                pool.shutdownNow()
            }
            store.setDelays(results)
            ui {
                markBusy(null)
                reload()
            }
        }
    }

    fun removeServer(server: Server) {
        store.removeServer(server.id)
        reload()
    }

    fun removeSubscription(subscription: Subscription) {
        store.removeSubscription(subscription.id)
        reload()
    }

    // ---------- apps ----------

    fun changePerApp(on: Boolean) {
        store.perApp = on
        perApp = on
        if (on || store.apps.isNotEmpty()) localError = null
        noteReconnect()
    }

    fun toggleApp(packageName: String) {
        val chosen = store.apps
        store.apps = if (packageName in chosen) chosen - packageName else chosen + packageName
        chosenApps = store.apps
        localError = null
        noteReconnect()
    }

    fun loadApps() {
        if (installedApps != null) return
        work.execute {
            val pm = app.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != app.packageName }
                .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, runCatching { it.loadIcon(pm) }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
            ui { installedApps = found }
        }
    }

    // ---------- settings ----------

    /** Updates every subscription and then tests every server: the dashboard's refresh. */
    fun refreshAll() {
        updateSubscriptions(onlyStale = false) { measure() }
    }

    fun changeDetailedLog(on: Boolean) {
        store.detailedLog = on
        detailedLog = on
        noteReconnect()
    }

    fun changeFakeIp(on: Boolean) {
        store.fakeIp = on
        fakeIp = on
        noteReconnect()
    }

    fun changeWarpChoice(choice: Warp.Choice) {
        store.warpChoice = choice
        warpChoice = choice
        if (warpSelected) noteReconnect()
    }

    fun changePsiphonRegion(region: String?) {
        store.psiphonRegion = region
        psiphonRegion = region
        if (warpSelected && warpChoice == Warp.Choice.Psiphon) noteReconnect()
    }

    fun changeBypassIran(on: Boolean) {
        store.bypassIran = on
        bypassIran = on
        noteReconnect()
    }

    // ---------- the log ----------

    /** What sing-box and Xray wrote during the last connection. */
    fun readLog(): String {
        val box = readTail(File(app.filesDir, BridgeVpnService.LOG_FILE))
        val xray = readTail(File(app.filesDir, BridgeVpnService.XRAY_LOG_FILE), 32 * 1024)
        val warp = readTail(File(app.filesDir, BridgeVpnService.WARP_LOG_FILE), 32 * 1024)
        return listOf(warp, box, xray).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun readTail(file: File, max: Long = 64 * 1024): String {
        if (!file.exists()) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { f ->
                val start = (f.length() - max).coerceAtLeast(0)
                f.seek(start)
                val bytes = ByteArray((f.length() - start).toInt())
                f.readFully(bytes)
                String(bytes, Charsets.UTF_8).let { if (start > 0) it.substringAfter('\n') else it }
            }
        }.getOrDefault("")
    }

    companion object {
        const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    }
}
