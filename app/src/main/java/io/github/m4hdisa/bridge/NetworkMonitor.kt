package io.github.m4hdisa.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * Tells sing-box which real network (Wi-Fi or mobile data) is in use, so its own connections
 * leave through it and it reconnects when the phone switches. The VPN's own network doesn't
 * count: since Android 9 the plain default-network callback would report it, so newer
 * versions ask for the best network that isn't a VPN.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val thread = HandlerThread("network-monitor").apply { start() }
    private val handler = Handler(thread.looper)
    private var registered = false

    @Volatile
    var defaultNetwork: Network? = null
        private set

    @Volatile
    private var listener: InterfaceUpdateListener? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network)

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == defaultNetwork) update(network)
        }

        override fun onLost(network: Network) {
            if (network == defaultNetwork) update(null)
        }
    }

    fun start() {
        defaultNetwork = connectivity.activeNetwork
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> connectivity.requestNetwork(request, callback, handler)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> connectivity.registerDefaultNetworkCallback(callback, handler)
            else -> connectivity.registerDefaultNetworkCallback(callback)
        }
        registered = true
    }

    fun stop() {
        if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
        listener = null
        thread.quitSafely()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        handler.post { report(defaultNetwork) }
    }

    private fun update(network: Network?) {
        defaultNetwork = network
        report(network)
    }

    private fun report(network: Network?) {
        val listener = listener ?: return
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        // A new network's details can take a moment to appear.
        repeat(10) {
            val name = connectivity.getLinkProperties(network)?.interfaceName
            val index = name?.let { n -> runCatching { NetworkInterface.getByName(n)?.index }.getOrNull() }
            if (name != null && index != null) {
                listener.updateDefaultInterface(name, index, false, false)
                return
            }
            Thread.sleep(100)
        }
    }
}
