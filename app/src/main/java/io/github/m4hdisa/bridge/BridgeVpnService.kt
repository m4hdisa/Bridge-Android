package io.github.m4hdisa.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.nekohasekai.libbox.NetworkInterface as BoxInterface
import io.nekohasekai.libbox.Notification as BoxNotification

/**
 * Runs sing-box inside Android's VPN. Per-App comes from Android itself: sing-box passes the
 * chosen apps back through openTun, which allows only those on the VPN. Every other app keeps
 * using the network as if no VPN were running.
 */
class BridgeVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    private var tun: ParcelFileDescriptor? = null
    private var server: CommandServer? = null
    private var monitor: NetworkMonitor? = null
    private var xray: XrayProcess? = null
    private var warp: WarpRunner? = null

    /** Held while starting and while cleaning up, so a disconnect during a long start waits for it to let go. */
    private val lock = Any()

    @Volatile
    private var stopping = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        if (VpnState.status != VpnState.Status.Stopped) return START_NOT_STICKY
        stopping = false
        VpnState.set(VpnState.Status.Starting)
        showNotification()
        Thread { startVpn() }.start()
        return START_NOT_STICKY
    }

    private fun startVpn(): Unit = synchronized(lock) {
        try {
            val store = Store(this)
            val clash = LocalAccess(freePort(), "", secret())
            val proxy = LocalAccess(freePort(), secret().take(12), secret())
            val log = File(filesDir, LOG_FILE).apply { delete() }
            File(filesDir, XRAY_LOG_FILE).delete()
            val warpLog = File(filesDir, WARP_LOG_FILE).apply { delete() }
            var input = Profile.input(store, Rules.install(this))
                // With FakeIP the made-up answers are kept in one file across connections, since
                // apps keep using them after a reconnect; otherwise each connection has a file of its own.
                .copy(logPath = log.path, clash = clash, proxy = proxy, cachePath = if (store.fakeIp) File(filesDir, FAKEIP_CACHE).path else freshCacheFile().path)
            if (Warp.isWarp(store.selected)) {
                val runner = WarpRunner(this, warpLog).also { warp = it }
                val died = { if (VpnState.status == VpnState.Status.Running) fail(getString(R.string.warp_died)) }
                val saved = Warp.savedId(store.selected)?.let { id -> store.warpConfigs.firstOrNull { it.id == id } }
                val port = if (saved != null) {
                    runner.startSaved(saved, { VpnState.progress(it) }, died) { gateway ->
                        store.warpConfigs = store.warpConfigs.map { if (it.id == saved.id) it.copy(gateway = gateway) else it }
                    }
                } else {
                    runner.start(store.warpChoice, store.psiphonRegion, { VpnState.progress(it) }, died)
                }
                if (stopping) return@synchronized
                input = input.copy(warpPort = port)
            }
            VpnState.progress(R.string.progress_starting)
            val forXray = input.servers.filter { it.second.usesXray }
            if (store.detailedLog) input = input.copy(logLevel = "debug")
            if (forXray.isNotEmpty()) input = startXray(input, forXray, store.includeSelf, store.detailedLog)
            var built = BoxConfig.build(input)
            val monitor = NetworkMonitor(this).also { this.monitor = it }
            monitor.start()
            val server = CommandServer(this, this).also { this.server = it }
            server.start()
            fun overrides() = OverrideOptions().apply {
                // Bridge's own requests (subscriptions, pings) stay outside the VPN, like any app left out.
                val self = if (store.includeSelf) setOf(packageName) else emptySet()
                if (store.perApp) {
                    includePackage = StringList((store.apps + self).toList())
                } else if (self.isEmpty()) {
                    excludePackage = StringList(listOf(packageName))
                }
            }
            try {
                server.startOrReloadService(built.json, overrides())
            } catch (e: Exception) {
                if (e.message?.contains("cache-file") != true) throw e
                // The kept file is still held by a connection that didn't end: start on a new one.
                Log.w(TAG, "cache file busy, starting with a new one", e)
                built = BoxConfig.build(input.copy(cachePath = freshCacheFile().path))
                server.startOrReloadService(built.json, overrides())
            }
            if (stopping) return@synchronized
            VpnState.session = VpnState.Session(clash, proxy, built.tags)
            VpnState.set(VpnState.Status.Running)
            showSpeed(clash)
            // The account a public starter one stood in for, made through the tunnel now that it works.
            warp?.let { w -> Thread { runCatching { w.replaceStarterAccount(proxy) } }.apply { isDaemon = true }.start() }
        } catch (e: WarpException) {
            Log.e(TAG, "WARP failed", e)
            if (!stopping) cleanUp(getString(e.res))
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            if (!stopping) cleanUp(e.message ?: e.toString())
        }
    }

    /**
     * Starts Xray for the servers only it can run, each on its own local SOCKS port, and returns
     * the input with those ports. Their addresses are looked up here first, on the phone's own
     * network: Xray is a plain Linux program to Android and can't use the phone's DNS settings.
     * If Xray can't start, those servers are left out and the rest still connect.
     */
    private fun startXray(input: BoxInput, servers: List<Pair<String, ParsedLink>>, bridgeOnVpn: Boolean, detailed: Boolean): BoxInput {
        val xray = XrayProcess(this).also { this.xray = it }
        val xrayLog = File(filesDir, XRAY_LOG_FILE)
        if (!xray.available) {
            xrayLog.writeText("This build has no Xray, so ${servers.size} servers that need it are left out\n")
            return input
        }
        val ips = resolveAll(servers.map { Xray.server(it.second.xray!!).first })
        val auth = LocalAccess(0, secret().take(12), secret())
        val ports = LinkedHashMap<String, Int>()
        val entries = servers.map { (id, parsed) ->
            val outbound = parsed.xray!!
            val pinned = ips[Xray.server(outbound).first]?.let { Xray.withServerIp(outbound, it) } ?: outbound
            val port = freePort()
            ports[id] = port
            Xray.Entry(port, pinned, if (Xray.needsRelay(pinned)) freePort() else null)
        }
        return try {
            xray.start(Xray.config(entries, auth.user, auth.secret, if (detailed) "info" else "warning"), entries.first().port, xrayLog)
            input.copy(xrayPorts = ports, xrayAuth = auth, directIps = if (bridgeOnVpn) ips.values.distinct() else emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Xray failed", e)
            xrayLog.appendText("Xray couldn't start, so ${servers.size} servers that need it are left out: ${e.message}\n")
            input
        }
    }

    /** Each name's first IPv4 address (or IPv6 if it has none), found in parallel within a few seconds. */
    private fun resolveAll(hosts: List<String>): Map<String, String> {
        val names = hosts.distinct().filter { it.isNotEmpty() }
        if (names.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(minOf(16, names.size))
        return try {
            pool.invokeAll(
                names.map { name ->
                    Callable {
                        val addresses = runCatching { InetAddress.getAllByName(name).toList() }.getOrDefault(emptyList())
                        val pick = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
                        name to pick?.hostAddress
                    }
                },
                RESOLVE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            ).mapNotNull { f -> runCatching { f.get() }.getOrNull() }
                .mapNotNull { (name, ip) -> ip?.let { name to it } }
                .toMap()
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * A cache file of its own for this connection. On Android sing-box always keeps one (by
     * default files/cache.db) and locks it; if an earlier connection didn't let go of its lock,
     * every later start failed with "initialize cache-file: timeout" until the app was closed.
     * Older ones are deleted; one still held by a stuck instance just goes once that ends.
     */
    private fun freshCacheFile(): File {
        File(filesDir, "cache.db").delete()
        cacheDir.listFiles { f -> f.name.startsWith(CACHE_PREFIX) }?.forEach { it.delete() }
        return File(cacheDir, "$CACHE_PREFIX${System.currentTimeMillis()}.db")
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun secret(): String = UUID.randomUUID().toString().replace("-", "")

    private fun stopVpn() = fail(null)

    /** Disconnects, showing `error` if there is one. A start still running is stopped first. */
    private fun fail(error: String?) {
        if (stopping) return
        stopping = true
        VpnState.set(VpnState.Status.Stopping)
        runCatching { warp?.stop() }
        Thread { synchronized(lock) { cleanUp(error) } }.start()
    }

    private fun cleanUp(error: String?) {
        VpnState.session = null
        runCatching { tun?.close() }
        tun = null
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null
        runCatching { xray?.stop() }
        xray = null
        runCatching { warp?.stop() }
        warp = null
        runCatching { monitor?.stop() }
        monitor = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        VpnState.set(VpnState.Status.Stopped, error)
        stopSelf()
    }

    override fun onRevoke() = stopVpn()

    /** The speed in the notification, every second while connected, from the Clash API. */
    private fun showSpeed(clash: LocalAccess) {
        Thread {
            val manager = getSystemService(NotificationManager::class.java)
            while (!stopping && VpnState.session?.clash === clash) {
                runCatching {
                    Clash.traffic(clash, { !stopping && VpnState.session?.clash === clash }) { up, down ->
                        val text = "↓ ${io.github.m4hdisa.bridge.ui.rate(down)}   ↑ ${io.github.m4hdisa.bridge.ui.rate(up)}"
                        if (!stopping) runCatching { manager.notify(NOTIFICATION_ID, notification(text)) }
                    }
                }
                if (!stopping) Thread.sleep(1_000)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        }
        val notification = notification(getString(R.string.status_starting))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeVpnService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(if (VpnState.session != null) R.string.status_on else R.string.status_starting))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(0, getString(R.string.disconnect), stop)
            .build()
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing VPN permission")
        val builder = Builder().setSession(getString(R.string.app_name)).setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val inet4 = options.inet4Address
        while (inet4.hasNext()) inet4.next().let { builder.addAddress(it.address(), it.prefix()) }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) inet6.next().let { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val routes4 = options.inet4RouteAddress
                if (routes4.hasNext()) {
                    while (routes4.hasNext()) builder.addRoute(routes4.next().toIpPrefix())
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }
                val routes6 = options.inet6RouteAddress
                if (routes6.hasNext()) {
                    while (routes6.hasNext()) builder.addRoute(routes6.next().toIpPrefix())
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }
                val exclude4 = options.inet4RouteExcludeAddress
                while (exclude4.hasNext()) builder.excludeRoute(exclude4.next().toIpPrefix())
                val exclude6 = options.inet6RouteExcludeAddress
                while (exclude6.hasNext()) builder.excludeRoute(exclude6.next().toIpPrefix())
            } else {
                val routes4 = options.inet4RouteRange
                while (routes4.hasNext()) routes4.next().let { builder.addRoute(it.address(), it.prefix()) }
                val routes6 = options.inet6RouteRange
                while (routes6.hasNext()) routes6.next().let { builder.addRoute(it.address(), it.prefix()) }
            }
            val include = options.includePackage
            while (include.hasNext()) {
                val name = include.next()
                // An app removed since it was chosen shouldn't stop the VPN from starting.
                try {
                    builder.addAllowedApplication(name)
                } catch (e: NameNotFoundException) {
                    Log.w(TAG, "not installed: $name", e)
                }
            }
            val exclude = options.excludePackage
            while (exclude.hasNext()) {
                val name = exclude.next()
                try {
                    builder.addDisallowedApplication(name)
                } catch (e: NameNotFoundException) {
                    Log.w(TAG, "not installed: $name", e)
                }
            }
        }
        val descriptor = builder.establish() ?: error("android: the app is not prepared or was revoked")
        tun = descriptor
        return descriptor.fd
    }

    private fun RoutePrefix.toIpPrefix(): IpPrefix {
        // Hidden until Android 13, public from then; this code only runs there.
        return IpPrefix(InetAddress.getByName(address()), prefix())
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    /** Keeps sing-box's own connections to the VPN server out of the VPN. */
    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner {
        val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uid = connectivity.getConnectionOwnerUid(ipProtocol, InetSocketAddress(sourceAddress, sourcePort), InetSocketAddress(destinationAddress, destinationPort))
            if (uid == Process.INVALID_UID) error("android: connection owner not found")
            ConnectionOwner().also { it.userId = uid }
        } else {
            Libbox.findConnectionOwner(ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
        }
        val packages = packageManager.getPackagesForUid(owner.userId)
        owner.userName = packages?.firstOrNull() ?: ""
        owner.setAndroidPackageNames(StringList(packages?.toList() ?: emptyList()))
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        monitor?.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        monitor?.setListener(null)
    }

    @Suppress("DEPRECATION")
    override fun getInterfaces(): NetworkInterfaceIterator {
        val system = NetworkInterface.getNetworkInterfaces().toList()
        val result = mutableListOf<BoxInterface>()
        for (network in connectivity.allNetworks) {
            val link = connectivity.getLinkProperties(network) ?: continue
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val found = system.find { it.name == link.interfaceName } ?: continue
            val item = BoxInterface()
            item.name = link.interfaceName
            item.dnsServer = StringList(link.dnsServers.mapNotNull { it.hostAddress })
            item.gateway = StringList(link.routes.filter { it.destination.prefixLength == 0 }.mapNotNull { it.gateway }.filterNot { it.isAnyLocalAddress }.mapNotNull { it.hostAddress })
            item.type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            item.index = found.index
            runCatching { item.mtu = found.mtu }
            item.addresses = StringList(found.interfaceAddresses.map { it.toPrefix() })
            var flags = 0
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (found.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (found.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (found.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            item.flags = flags
            item.metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result.add(item)
        }
        val iterator = result.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext() = iterator.hasNext()

            override fun next() = iterator.next()
        }
    }

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }

    override fun localDNSTransport(): LocalDNSTransport = LocalResolver { monitor?.defaultNetwork }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {}

    override fun readWIFIState(): WIFIState? = null

    override fun sendNotification(notification: BoxNotification) {}

    override fun cancelNotification(identifier: String, typeID: Int) {}

    override fun registerMyInterface(name: String?) {}

    // Features of sing-box that Bridge doesn't use.
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() = error("not supported")

    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int): ShellSession = error("not supported")

    override fun lookupUser(username: String?): PlatformUser = error("not supported")

    override fun lookupSFTPServer(): String = error("not supported")

    override fun readSystemSSHHostKey(): String = error("not supported")

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")

    override fun serviceStop() = stopVpn()

    override fun serviceReload() {}

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message ?: "")
    }

    override fun connectSSHAgent(): Int = -1

    private class StringList(items: List<String>) : StringIterator {
        private val iterator = items.iterator()

        // sing-box doesn't call this.
        override fun len(): Int = 0

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): String = iterator.next()
    }

    companion object {
        const val ACTION_STOP = "io.github.m4hdisa.bridge.STOP"
        const val LOG_FILE = "box.log"
        const val XRAY_LOG_FILE = "xray.log"
        const val WARP_LOG_FILE = "warp.log"
        private const val CACHE_PREFIX = "box-cache-"
        private const val FAKEIP_CACHE = "fakeip-cache.db"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
        private const val TAG = "Bridge"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
    }
}
