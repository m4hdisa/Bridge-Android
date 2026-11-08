package io.github.m4hdisa.bridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/**
 * Runs on an emulator in CI against a VLESS server on the build machine (10.0.2.2 from the
 * emulator). The VPN permission is granted beforehand with appops, since nobody can tap "OK".
 */
@RunWith(AndroidJUnit4::class)
class ConnectTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args = InstrumentationRegistry.getArguments()
    private val uuid = args.getString("uuid") ?: "b831381d-6324-4d53-ad4f-8cda48b30811"
    private val port = args.getString("port") ?: "10808"
    private val live = "vless://$uuid@10.0.2.2:$port?security=none&type=tcp#Live"

    // Nothing listens on port 9.
    private val dead = "vless://$uuid@10.0.2.2:9?security=none&type=tcp#Dead"

    @Before
    fun reset() {
        stopVpn()
        // Forget an error from an earlier test.
        VpnState.set(VpnState.Status.Stopped)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun stop() = stopVpn()

    private fun waitFor(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            Thread.sleep(250)
        }
        fail("timed out waiting for $what (status ${VpnState.status}, error ${VpnState.error})")
    }

    private fun startVpn(timeoutMs: Long = 30_000) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, BridgeVpnService::class.java)) }
            waitFor(timeoutMs, "the VPN to start") { VpnState.status == VpnState.Status.Running || VpnState.error != null }
        }
        assertNull("start error", VpnState.error)
        assertNotNull(VpnState.session)
    }

    private fun stopVpn() {
        if (VpnState.status == VpnState.Status.Stopped) return
        context.startService(Intent(context, BridgeVpnService::class.java).setAction(BridgeVpnService.ACTION_STOP))
        waitFor(15_000, "the VPN to stop") { VpnState.status == VpnState.Status.Stopped }
    }

    private fun <T> retrying(times: Int, block: () -> T): T {
        var last: Throwable? = null
        repeat(times) {
            try {
                return block()
            } catch (e: Throwable) {
                last = e
                Thread.sleep(2_000)
            }
        }
        throw AssertionError("failed $times times", last)
    }

    @Test
    fun connectsThroughTheServer() {
        val store = Store(context)
        store.addLinks(listOf(live))
        store.selected = store.servers.single().id
        // Only Bridge itself on the VPN, so this test's own requests go through the TUN.
        store.perApp = true
        store.includeSelf = true
        startVpn()
        val session = VpnState.session!!

        val page = retrying(5) { URL("https://example.com/").readText() }
        assertTrue("example.com through the TUN", page.contains("Example Domain"))

        val trace = retrying(3) { Net.through(session.proxy, "https://www.cloudflare.com/cdn-cgi/trace").body }
        assertTrue("Bridge's own proxy reaches the internet: $trace", trace.contains("ip="))

        assertTrue("Clash API measures the server", Clash.delay(session.clash, "proxy") > 0)
    }

    @Test
    fun autoSkipsADeadServer() {
        val store = Store(context)
        store.addLinks(listOf(dead, live))
        store.selected = AUTO
        store.perApp = true
        store.includeSelf = true
        startVpn()
        val session = VpnState.session!!
        val liveTag = session.tags.getValue(store.servers.first { it.link == live }.id)
        waitFor(30_000, "Auto to pick the live server") { Clash.current(session.clash) == liveTag }
        val page = retrying(5) { URL("https://example.com/").readText() }
        assertTrue(page.contains("Example Domain"))
    }

    /** A server only Xray can run: VLESS over xhttp with REALITY, the way many panels hand them out. */
    @Test
    fun connectsThroughXray() {
        val xrayPort = args.getString("xrayPort")?.takeIf { it.isNotEmpty() } ?: return
        val publicKey = args.getString("realityKey")?.takeIf { it.isNotEmpty() } ?: return
        val link = "vless://$uuid@10.0.2.2:$xrayPort?type=xhttp&path=%2Fbridge&security=reality&sni=www.google.com&fp=chrome&pbk=$publicKey#XrayLive"
        assertTrue("an xhttp link runs on Xray", ShareLink.parse(link).usesXray)
        val store = Store(context)
        store.addLinks(listOf(link))
        store.selected = store.servers.single().id
        store.perApp = true
        store.includeSelf = true
        startVpn()
        val session = VpnState.session!!
        val page = retrying(5) { URL("https://example.com/").readText() }
        assertTrue("example.com through Xray", page.contains("Example Domain"))
        assertTrue("Clash API measures the Xray server", Clash.delay(session.clash, "proxy", 8_000) > 0)
    }

    /**
     * Free WARP for real: Aether makes a WARP account at Cloudflare, finds a way through and
     * serves it; Cloudflare then says the request came over WARP. Aether runs as Bridge, so Bridge
     * stays off the VPN, as on a phone, and the check goes through Bridge's own local proxy.
     */
    @Test
    fun connectsThroughWarp() {
        if (!WarpRunner.isAvailable(context)) return
        val store = Store(context)
        store.selected = Warp.ID
        store.perApp = false
        store.includeSelf = false
        val log = File(context.filesDir, BridgeVpnService.WARP_LOG_FILE)
        try {
            startVpn(240_000)
            val session = VpnState.session!!
            val trace = retrying(5) { Net.through(session.proxy, "https://www.cloudflare.com/cdn-cgi/trace").body }
            assertTrue("traffic leaves through WARP: $trace", Regex("^warp=(on|plus)$", RegexOption.MULTILINE).containsMatchIn(trace))
            assertTrue("Clash API measures WARP", Clash.delay(session.clash, "proxy", 10_000) > 0)
        } catch (e: Throwable) {
            val tail = runCatching { log.readText().takeLast(6000) }.getOrDefault("")
            throw AssertionError("${e.message}\n--- warp.log ---\n$tail", e)
        }
    }

    @Test
    fun everyPageOpens() {
        val store = Store(context)
        store.addLinks(listOf(live, dead))
        store.perApp = true
        val shots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        for (language in listOf("en", "fa")) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (screen in Screen.entries) {
                    scenario.onActivity { it.show(screen) }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    Thread.sleep(700)
                    scenario.onActivity { activity ->
                        val view = activity.window.decorView
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        File(shots, "$language-${screen.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        assertEquals(screen, activity.shown)
                    }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
    }
}
