package io.github.m4hdisa.bridge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Bridge in the phone's quick settings: one tap connects to the server chosen in the app, or
 * disconnects. When Android hasn't allowed Bridge's VPN yet, the tap opens the app to ask.
 */
class BridgeTileService : TileService() {
    private val listener: () -> Unit = { refresh() }

    override fun onStartListening() {
        super.onStartListening()
        VpnState.addListener(listener)
        refresh()
    }

    override fun onStopListening() {
        VpnState.removeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (VpnState.status) {
            VpnState.Status.Running, VpnState.Status.Starting ->
                startService(Intent(this, BridgeVpnService::class.java).setAction(BridgeVpnService.ACTION_STOP))
            VpnState.Status.Stopping -> Unit
            VpnState.Status.Stopped -> {
                val store = Store(this)
                val ready = VpnService.prepare(this) == null &&
                    (store.servers.isNotEmpty() || Warp.isWarp(store.selected)) &&
                    !(store.perApp && store.apps.isEmpty())
                if (ready) ContextCompat.startForegroundService(this, Intent(this, BridgeVpnService::class.java)) else openApp()
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CONNECT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = when (VpnState.status) {
            VpnState.Status.Running -> Tile.STATE_ACTIVE
            VpnState.Status.Stopped -> Tile.STATE_INACTIVE
            else -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (VpnState.status) {
                    VpnState.Status.Running -> R.string.status_on
                    VpnState.Status.Starting -> R.string.status_starting
                    VpnState.Status.Stopping -> R.string.status_stopping
                    VpnState.Status.Stopped -> R.string.status_off
                },
            )
        }
        tile.updateTile()
    }
}
