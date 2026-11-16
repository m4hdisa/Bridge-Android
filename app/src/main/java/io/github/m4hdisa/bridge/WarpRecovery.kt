package io.github.m4hdisa.bridge

/**
 * When a Free WARP connection that stopped carrying traffic should be connected again. A tunnel
 * the network dropped doesn't always come back by itself, and Psiphon inside it never does, so
 * after two failed checks in a row (with the phone's own internet still working) Bridge connects
 * again: a new WARP tunnel, and Psiphon again inside it. When that keeps failing it waits longer
 * before each next try; a check that works again resets this, so each new drop is fixed at once.
 * Bridge for Windows does the same (recoverWarp in src/main/index.ts).
 */
class WarpRecovery(private val waits: List<Long> = WAITS) {
    private var failed = 0
    private var lastAt = 0L

    /** How many times in a row Bridge has connected again without a check working in between. */
    var tries = 0
        private set

    /**
     * After each check: true when Bridge should connect again now. `internet` asks whether the
     * phone's own internet works; it is only asked when a try is due, since connecting again
     * can't help when the whole network is down.
     */
    fun afterCheck(ok: Boolean, now: Long, internet: () -> Boolean): Boolean {
        if (ok) {
            failed = 0
            tries = 0
            return false
        }
        if (++failed < FAILS) return false
        if (tries > 0 && now - lastAt < waits[minOf(tries, waits.lastIndex)]) return false
        if (!internet()) return false
        tries++
        lastAt = now
        failed = 0
        return true
    }

    companion object {
        /** Failed checks in a row before Bridge connects again. */
        const val FAILS = 2

        /** How often the connection is checked: two failed checks take about 40 seconds. */
        const val CHECK_EVERY_MS = 20_000L

        /** Before each try: none for the first, then 1, 2, 5 and from then on 10 minutes after the last. */
        val WAITS = listOf(0L, 60_000L, 120_000L, 300_000L, 600_000L)
    }
}
