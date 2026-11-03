package io.github.m4hdisa.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VlessLinkTest {
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    /** Saved so the build can validate each config with the real `sing-box check`. */
    private fun saveConfig(name: String, server: VlessServer) {
        val dir = File("build/generated-configs").apply { mkdirs() }
        File(dir, "$name.json").writeText(BoxConfig.build(server))
    }

    @Test
    fun reality() {
        val s = VlessLink.parse("vless://$uuid@203.0.113.10:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.example.com&fp=chrome&pbk=abcDEF123&sid=6ba8&type=tcp#Reality%20Server")
        saveConfig("reality", s)
        assertEquals("Reality Server", s.name)
        assertEquals(443, s.outbound.getInt("server_port"))
        assertEquals("xtls-rprx-vision", s.outbound.getString("flow"))
        val tls = s.outbound.getJSONObject("tls")
        assertEquals("www.example.com", tls.getString("server_name"))
        assertEquals("abcDEF123", tls.getJSONObject("reality").getString("public_key"))
        assertFalse(s.outbound.has("transport"))
    }

    @Test
    fun webSocketWithEarlyData() {
        val s = VlessLink.parse("vless://$uuid@example.com:8443?security=tls&sni=cdn.example.com&type=ws&host=cdn.example.com&path=%2Fws%3Fed%3D2048#WS")
        saveConfig("ws", s)
        val t = s.outbound.getJSONObject("transport")
        assertEquals("/ws", t.getString("path"))
        assertEquals(2048, t.getInt("max_early_data"))
        assertEquals("cdn.example.com", t.getJSONObject("headers").getString("Host"))
    }

    @Test
    fun grpc() {
        val s = VlessLink.parse("vless://$uuid@example.com:443?security=tls&type=grpc&serviceName=grpcsvc#gRPC")
        saveConfig("grpc", s)
        assertEquals("grpcsvc", s.outbound.getJSONObject("transport").getString("service_name"))
    }

    @Test
    fun ipv6Address() {
        val s = VlessLink.parse("vless://$uuid@[2001:db8::1]:2053?security=none#v6")
        saveConfig("ipv6", s)
        assertEquals("2001:db8::1", s.host)
        assertEquals(2053, s.port)
    }

    @Test
    fun defaultsAndNames() {
        val s = VlessLink.parse("  vless://$uuid@example.com  ")
        assertEquals(443, s.port)
        assertEquals("example.com:443", s.name)
        assertEquals("سرور من", VlessLink.parse("vless://$uuid@example.com:443#%D8%B3%D8%B1%D9%88%D8%B1%20%D9%85%D9%86").name)
        assertEquals("a+b", VlessLink.parse("vless://$uuid@example.com:443#a+b").name)
    }

    @Test
    fun unknownFingerprintBecomesChrome() {
        val s = VlessLink.parse("vless://$uuid@example.com:443?security=tls&sni=example.com&fp=unsafe#fp")
        assertEquals("chrome", s.outbound.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun linksThatNeedXrayOrAreBroken() {
        val bad = listOf(
            "vless://$uuid@example.com:443?type=xhttp&security=tls" to "Xray",
            "vless://$uuid@example.com:443?encryption=mlkem768x25519plus.native.0rtt.abc" to "Xray",
            "vless://$uuid@example.com:443?type=tcp&headerType=http" to "Xray",
            "vmess://abc" to "vless",
            "vless://example.com:443" to "UUID",
            "vless://$uuid@example.com:99999" to "port",
            "vless://$uuid@example.com:443?security=reality&sni=a.example.com" to "pbk",
        )
        for ((link, word) in bad) {
            val e = assertThrows(link, LinkException::class.java) { VlessLink.parse(link) }
            assertTrue("${e.message} should mention $word", e.message!!.contains(word))
        }
    }

    @Test
    fun configSendsEverythingThroughTheServer() {
        val c = JSONObject(BoxConfig.build(VlessLink.parse("vless://$uuid@203.0.113.10:443?security=none#c")))
        assertEquals("proxy", c.getJSONObject("route").getString("final"))
        assertEquals("proxy", c.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        assertEquals("proxy", c.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"))
        assertTrue(c.getJSONArray("inbounds").getJSONObject(0).getBoolean("auto_route"))
    }
}
