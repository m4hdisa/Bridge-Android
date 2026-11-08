package io.github.m4hdisa.bridge

import android.os.Handler
import android.os.Looper

/** The connection's state, shared between the VPN service and the screen. */
object VpnState {
    enum class Status { Stopped, Starting, Running, Stopping }

    /** How to reach the running sing-box, and which outbound tag each server id has in its config. */
    class Session(val clash: LocalAccess, val proxy: LocalAccess, val tags: Map<String, String>) {
        fun idOf(tag: String): String? = if (tag == AUTO) AUTO else tags.entries.firstOrNull { it.value == tag }?.key
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    @Volatile
    var status = Status.Stopped
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var session: Session? = null

    /** While connecting: what Bridge is doing now (a string resource), so a long connect is no mystery. */
    @Volatile
    var progress: Int? = null
        private set

    fun progress(res: Int?) {
        main.post {
            progress = res
            listeners.toList().forEach { it() }
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun set(status: Status, error: String? = null) {
        main.post {
            this.status = status
            this.error = error
            if (status != Status.Starting) progress = null
            listeners.toList().forEach { it() }
        }
    }
}
