package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxConfigTest {
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
    private fun server(i: Int) = "id$i" to ShareLink.parse("vless://$uuid@203.0.113.${i + 1}:443?security=tls&sni=s$i.example.com#S$i")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    @Test
    fun everyServerSitsBehindOneSelector() {
        val built = Configs.build((0 until 3).map { server(it) }, "id1")
        Configs.write("many", built)
        val c = JSONObject(built.json)
        assertEquals(mapOf("id0" to "s0", "id1" to "s1", "id2" to "s2"), built.tags)
        val outbounds = c.getJSONArray("outbounds").objects().associateBy { it.getString("tag") }
        val selector = outbounds.getValue("proxy")
        assertEquals("selector", selector.getString("type"))
        assertEquals("s1", selector.getString("default"))
        assertEquals(listOf(AUTO, "s0", "s1", "s2"), (0 until selector.getJSONArray("outbounds").length()).map { selector.getJSONArray("outbounds").getString(it) })
        assertEquals("urltest", outbounds.getValue(AUTO).getString("type"))
        assertEquals("proxy", c.getJSONObject("route").getString("final"))
        assertEquals("proxy", c.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
    }

    @Test
    fun autoIsTheDefaultWhenNothingElseIsChosen() {
        val c = JSONObject(Configs.build((0 until 2).map { server(it) }, AUTO).json)
        val selector = c.getJSONArray("outbounds").objects().first { it.getString("tag") == "proxy" }
        assertEquals(AUTO, selector.getString("default"))
    }

    @Test
    fun oneServerNeedsNoAuto() {
        val built = Configs.build(listOf(server(0)), AUTO)
        Configs.write("single", built)
        val tags = JSONObject(built.json).getJSONArray("outbounds").objects().map { it.getString("tag") }
        assertFalse(AUTO in tags)
        val selector = JSONObject(built.json).getJSONArray("outbounds").objects().first { it.getString("tag") == "proxy" }
        assertEquals("s0", selector.getString("default"))
    }

    @Test
    fun iranianSitesGoDirect() {
        val built = Configs.build(listOf(server(0)), "id0", bypassIran = true)
        Configs.write("iran", built)
        val c = JSONObject(built.json)
        val rules = c.getJSONObject("route").getJSONArray("rules").objects()
        assertTrue(rules.any { it.optString("outbound") == "direct" && it.optJSONArray("rule_set")?.toString()?.contains("geoip-ir") == true })
        assertTrue(c.getJSONObject("dns").getJSONArray("rules").objects().any { it.optString("server") == "local" })
        assertEquals(2, c.getJSONObject("route").getJSONArray("rule_set").length())
    }

    @Test
    fun bridgesOwnProxyNeedsAPasswordAndUsesTheServer() {
        val c = JSONObject(Configs.build(listOf(server(0)), "id0").json)
        val inbound = c.getJSONArray("inbounds").objects().first { it.getString("tag") == "bridge-in" }
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals("user", inbound.getJSONArray("users").getJSONObject(0).getString("username"))
        val rules = c.getJSONObject("route").getJSONArray("rules").objects()
        val viaProxy = rules.indexOfFirst { it.optJSONArray("inbound")?.getString(0) == "bridge-in" }
        val iran = rules.indexOfFirst { it.optJSONArray("domain_suffix") != null }
        assertTrue("Bridge's own requests skip the Iran rule", viaProxy in 0 until iran)
        assertEquals("secret", c.getJSONObject("experimental").getJSONObject("clash_api").getString("secret"))
    }

    @Test
    fun serverListRules() {
        val a = Server("a", "A", "vless://a")
        val s1 = Server("s1", "S1", "vless://1", "sub")
        val s2 = Server("s2", "S2", "vless://2", "sub")
        val b = Server("b", "B", "vless://b")
        val replaced = Servers.replace(listOf(a, s1, s2, b), "sub", listOf("vless://2", "vless://3"))
        assertEquals(listOf("a", "s2"), replaced.take(2).map { it.id })
        assertEquals("vless://3", replaced[2].link)
        assertEquals("b", replaced[3].id)
        assertNull(replaced.firstOrNull { it.id == "s1" })

        val fast = Server("f", "F", "vless://f", "public", 50)
        val slow = Server("o", "O", "vless://o", null, 900)
        val unknown = Server("u", "U", "vless://u")
        assertEquals(listOf("o", "u"), Servers.autoGroup(listOf(unknown, slow, fast), setOf("public"), 10).map { it.id })
        assertEquals(listOf("f"), Servers.autoGroup(listOf(fast), setOf("public"), 10).map { it.id })
    }
}
