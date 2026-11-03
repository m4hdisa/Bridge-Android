package io.github.m4hdisa.bridge

import android.os.Handler
import android.os.Looper

/** The connection's state, shared between the VPN service and the screen. */
object VpnState {
    enum class Status { Stopped, Starting, Running, Stopping }

    private val main = Handler(Looper.getMainLooper())

    var status = Status.Stopped
        private set
    var error: String? = null
        private set
    var listener: (() -> Unit)? = null

    fun set(status: Status, error: String? = null) {
        main.post {
            this.status = status
            this.error = error
            listener?.invoke()
        }
    }
}
