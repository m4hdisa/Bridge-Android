package io.github.m4hdisa.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a Free WARP connection that stopped carrying traffic is connected again. */
class WarpRecoveryTest {
    private val up = { true }
    private val down = { false }

    @Test
    fun `two failed checks in a row connect again at once`() {
        val r = WarpRecovery()
        assertFalse(r.afterCheck(false, 0, up))
        assertTrue(r.afterCheck(false, 20_000, up))
        assertEquals(1, r.tries)
    }

    @Test
    fun `a working check in between starts the count again`() {
        val r = WarpRecovery()
        assertFalse(r.afterCheck(false, 0, up))
        assertFalse(r.afterCheck(true, 20_000, up))
        assertFalse(r.afterCheck(false, 40_000, up))
        assertTrue(r.afterCheck(false, 60_000, up))
    }

    @Test
    fun `nothing is tried while the phone's own internet is down`() {
        val r = WarpRecovery()
        var asked = 0
        val counting = { asked++; false }
        assertFalse(r.afterCheck(false, 0, counting))
        assertEquals(0, asked)
        assertFalse(r.afterCheck(false, 20_000, counting))
        assertFalse(r.afterCheck(false, 40_000, counting))
        assertEquals(2, asked)
        assertEquals(0, r.tries)
        // Once it is back, the first try comes at once.
        assertTrue(r.afterCheck(false, 60_000, up))
    }

    @Test
    fun `tries that keep failing wait 1, 2, 5 and then 10 minutes`() {
        val r = WarpRecovery()
        var now = 0L
        // Checks every 20 s, all failing; the times Bridge connects again.
        val at = mutableListOf<Long>()
        while (now <= 40 * 60_000L) {
            if (r.afterCheck(false, now, up)) at += now
            now += 20_000
        }
        val gaps = at.zipWithNext { a, b -> b - a }
        assertEquals(20_000L, at.first())
        // Each gap is the wait, rounded up to the next pair of failed checks.
        assertTrue(gaps[0] in 60_000L..100_000L)
        assertTrue(gaps[1] in 120_000L..160_000L)
        assertTrue(gaps[2] in 300_000L..340_000L)
        assertTrue(gaps.drop(3).all { it in 600_000L..640_000L })
    }

    @Test
    fun `a check that works again makes the next drop connect at once`() {
        val r = WarpRecovery()
        r.afterCheck(false, 0, up)
        assertTrue(r.afterCheck(false, 20_000, up))
        r.afterCheck(false, 40_000, up)
        assertFalse(r.afterCheck(false, 60_000, up))
        assertFalse(r.afterCheck(true, 80_000, up))
        assertEquals(0, r.tries)
        r.afterCheck(false, 100_000, up)
        assertTrue(r.afterCheck(false, 120_000, up))
    }

    @Test
    fun `the waits are the ones Windows uses`() {
        assertEquals(listOf(0L, 60_000L, 120_000L, 300_000L, 600_000L), WarpRecovery.WAITS)
        assertEquals(20_000L, WarpRecovery.CHECK_EVERY_MS)
        assertEquals(2, WarpRecovery.FAILS)
    }
}
