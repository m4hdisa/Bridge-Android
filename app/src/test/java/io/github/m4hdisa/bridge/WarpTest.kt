package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.Deflater

/** FakeIP, Free WARP's place in the config, Aether's command lines and subscription usage. */
class WarpTest {
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
    private fun server(i: Int) = "id$i" to ShareLink.parse("vless://$uuid@203.0.113.${i + 1}:443?security=tls&sni=s$i.example.com#S$i")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun input(servers: List<Pair<String, ParsedLink>>, selected: String, warpPort: Int? = null, fakeIp: Boolean = true) = BoxInput(
        servers, selected, servers.map { it.first }, true, File("build/rules").absolutePath.replace('\\', '/'),
        null, LocalAccess(9090, "", "secret"), LocalAccess(2080, "user", "pass"),
        warpPort = warpPort, fakeIp = fakeIp, cachePath = File("build/cache.db").absolutePath.replace('\\', '/'),
    )

    @Test
    fun namesGetMadeUpAnswersAtOnce() {
        val built = BoxConfig.build(input(listOf(server(0)), "id0"))
        Configs.write("fakeip", built)
        val c = JSONObject(built.json)
        val dns = c.getJSONObject("dns")
        val fake = dns.getJSONArray("servers").objects().first { it.getString("type") == "fakeip" }
        assertEquals(BoxConfig.FAKE_RANGE, fake.getString("inet4_range"))
        val rules = dns.getJSONArray("rules").objects()
        // Iranian names come first, so they still get real answers from the phone's DNS.
        val fakeAt = rules.indexOfFirst { it.optString("server") == "fake" }
        assertEquals(rules.size - 1, fakeAt)
        assertTrue(rules.take(fakeAt).all { it.optString("server") == "local" })
        val cache = c.getJSONObject("experimental").getJSONObject("cache_file")
        assertTrue(cache.getBoolean("store_fakeip"))
        // Iran's address for blocked sites is refused before private addresses go direct.
        val route = c.getJSONObject("route").getJSONArray("rules").objects()
        val reject = route.indexOfFirst { it.optString("action") == "reject" && it.optJSONArray("ip_cidr")?.getString(0) == BoxConfig.IRAN_FAKE_RANGE }
        val private = route.indexOfFirst { it.optBoolean("ip_is_private") }
        assertTrue(reject in 0 until private)
    }

    @Test
    fun withoutFakeIpNamesGetRealAnswers() {
        val c = JSONObject(BoxConfig.build(input(listOf(server(0)), "id0", fakeIp = false)).json)
        assertTrue(c.getJSONObject("dns").getJSONArray("servers").objects().none { it.getString("type") == "fakeip" })
        assertFalse(c.getJSONObject("experimental").getJSONObject("cache_file").getBoolean("store_fakeip"))
    }

    @Test
    fun warpIsASocksHopOutsideAuto() {
        val built = BoxConfig.build(input((0 until 2).map { server(it) }, Warp.ID, warpPort = 41000))
        Configs.write("warp", built)
        val outbounds = JSONObject(built.json).getJSONArray("outbounds").objects().associateBy { it.getString("tag") }
        val warp = outbounds.getValue(BoxConfig.WARP_TAG)
        assertEquals("socks", warp.getString("type"))
        assertEquals(41000, warp.getInt("server_port"))
        assertEquals(BoxConfig.WARP_TAG, built.tags[Warp.ID])
        assertEquals(BoxConfig.WARP_TAG, outbounds.getValue("proxy").getString("default"))
        val auto = outbounds.getValue(AUTO).getJSONArray("outbounds")
        assertFalse((0 until auto.length()).any { auto.getString(it) == BoxConfig.WARP_TAG })
    }

    @Test
    fun warpNeedsNoServerOfYourOwn() {
        val built = BoxConfig.build(input(emptyList(), Warp.ID, warpPort = 41000))
        Configs.write("warp-alone", built)
        val outbounds = JSONObject(built.json).getJSONArray("outbounds").objects().associateBy { it.getString("tag") }
        assertNull(outbounds[AUTO])
        assertEquals(BoxConfig.WARP_TAG, outbounds.getValue("proxy").getString("default"))
    }

    @Test
    fun aetherNeverWaitsForInput() {
        val args = Warp.args(Warp.Method.H2_FRAGMENT, 40000, "/data/warp/aether.toml")
        assertEquals(listOf("--masque", "--h2", "--fragment", "-4", "--scan", "turbo", "--quick-reconnect", "--bind", "127.0.0.1:40000", "--config", "/data/warp/aether.toml"), args)
        val gool = Warp.args(Warp.Method.GOOL, 40001, "c", Warp.Psiphon("/lib/libpsiphon.so", 40002, "DE"))
        assertTrue(gool.containsAll(listOf("--gool", "verified", "--exit-loc", "!IR", "--psiphon", "--psiphon-bind", "127.0.0.1:40002", "--psiphon-region", "DE")))
        assertEquals(listOf("--register", "masque", "--fragment", "--config", "c"), Warp.registerArgs("c", listOf("--fragment")))
        assertEquals(listOf("--register", "masque", "--upstream", "socks5://u:p@127.0.0.1:1", "--config", "c"), Warp.registerArgs("c", upstream = "socks5://u:p@127.0.0.1:1"))
    }

    @Test
    fun aetherLinesAreRead() {
        val line = "[2026-11-07T10:00:00Z INFO  aether::socks] socks5 server listening on 127.0.0.1:40000"
        val text = Warp.message(line)
        assertTrue(Warp.ready(text, 40000))
        assertFalse(Warp.ready(text, 40001))
        assertEquals("IR", Warp.exitCountry("masque exit: 104.28.214.161, IR via FRA, rtt 80ms"))
        assertEquals(Warp.Method.entries.filter { it.twoHops }, Warp.methods(Warp.Choice.Foreign).filter { it.twoHops })
        assertTrue(Warp.methods(Warp.Choice.Psiphon).none { it.twoHops })
    }

    private fun shareLink(identity: String): String {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(identity.toByteArray())
        deflater.finish()
        val buffer = ByteArray(8192)
        val n = deflater.deflate(buffer)
        deflater.end()
        return "${Warp.SHARE_SCHEME}?m=auto&a=" + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.copyOf(n)) + "#Starter"
    }

    @Test
    fun starterAccountsAreReadWithoutTrustingThem() {
        val identity = "device_id = \"d\"\naccess_token = \"t\"\ncert_pem = \"\"\"x\"\"\"\nkey_pem = \"\"\"y\"\"\"\n"
        assertEquals(identity, Warp.identityOf(shareLink(identity)))
        for (bad in listOf(identity + "evil = 1\n", "device_id = \"d\"\n")) {
            try {
                Warp.identityOf(shareLink(bad))
                fail("accepted: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun configsFindTheirCountry() {
        assertEquals("DE", Regions.guess("🇩🇪 Germany Reality"))
        assertEquals("NL", Regions.guess("Amsterdam xhttp"))
        assertEquals("FI", Regions.guess("سرور فنلاند ۲"))
        assertEquals("GB", Regions.guess("UK-3 | ws"))
        assertEquals("TR", Regions.guess("TR-1"))
        assertNull(Regions.guess("Public 7"))
        assertNull(Regions.guess("ME server"))
        assertEquals("DE", Server("i", "Public 1", "x", exit = "DE").country)
    }

    @Test
    fun subscriptionUsage() {
        val u = Usage.parse("upload=1000; download=2000; total=10737418240; expire=1893456000")!!
        assertEquals(3000L, u.used)
        assertEquals(10737418240L, u.total)
        assertEquals(1893456000L, u.expire)
        assertNull(Usage.parse(""))
        assertNull(Usage.parse("upload=0; download=0; total=0; expire=0"))
        val kept = Subscription("s", "https://x", "X", usage = u)
        assertEquals(kept, Subscription.fromJson(kept.toJson()))
    }
}
