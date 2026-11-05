package io.github.m4hdisa.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class ShareLinkTest {
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    // A REALITY public key sing-box can decode (32 zero bytes).
    private val pbk = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun reality() {
        val s = ShareLink.parse("vless://$uuid@203.0.113.10:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.example.com&fp=chrome&pbk=$pbk&sid=6ba8&type=tcp#Reality%20Server")
        Configs.save("reality", s)
        assertEquals("Reality Server", s.name)
        assertEquals("vless / tcp / reality", s.kind)
        assertEquals(443, s.outbound.getInt("server_port"))
        assertEquals("xtls-rprx-vision", s.outbound.getString("flow"))
        val tls = s.outbound.getJSONObject("tls")
        assertEquals("www.example.com", tls.getString("server_name"))
        assertEquals(pbk, tls.getJSONObject("reality").getString("public_key"))
        assertFalse(s.outbound.has("transport"))
    }

    @Test
    fun webSocketWithEarlyData() {
        val s = ShareLink.parse("vless://$uuid@Example.com:8443?security=tls&sni=cdn.example.com&type=ws&host=cdn.example.com&path=%2Fws%3Fed%3D2048#WS")
        Configs.save("ws", s)
        assertEquals("example.com", s.host)
        val t = s.outbound.getJSONObject("transport")
        assertEquals("/ws", t.getString("path"))
        assertEquals(2048, t.getInt("max_early_data"))
        assertEquals("cdn.example.com", t.getJSONObject("headers").getString("Host"))
    }

    @Test
    fun grpcAndHttpUpgrade() {
        val g = ShareLink.parse("vless://$uuid@example.com:443?security=tls&type=grpc&serviceName=grpcsvc#gRPC")
        Configs.save("grpc", g)
        assertEquals("grpcsvc", g.outbound.getJSONObject("transport").getString("service_name"))
        val h = ShareLink.parse("vless://$uuid@example.com:80/?type=httpupgrade&host=up.example.com&path=%2Fup#hu")
        Configs.save("httpupgrade", h)
        assertEquals("up.example.com", h.outbound.getJSONObject("transport").getString("host"))
    }

    @Test
    fun ipv6Address() {
        val s = ShareLink.parse("vless://$uuid@[2001:db8::1]:2053?security=none#v6")
        Configs.save("ipv6", s)
        assertEquals("2001:db8::1", s.host)
        assertEquals(2053, s.port)
    }

    @Test
    fun defaultsAndNames() {
        val s = ShareLink.parse("  vless://$uuid@example.com  ")
        assertEquals(443, s.port)
        assertEquals("example.com:443", s.name)
        assertEquals("سرور من", ShareLink.parse("vless://$uuid@example.com:443#%D8%B3%D8%B1%D9%88%D8%B1%20%D9%85%D9%86").name)
        assertEquals("a+b", ShareLink.parse("vless://$uuid@example.com:443#a+b").name)
    }

    @Test
    fun unknownFingerprintBecomesChrome() {
        val s = ShareLink.parse("vless://$uuid@example.com:443?security=tls&sni=example.com&fp=unsafe#fp")
        assertEquals("chrome", s.outbound.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun vmess() {
        val json = JSONObject().put("v", "2").put("ps", "VMess WS").put("add", "203.0.113.20").put("port", 8080).put("id", uuid).put("aid", "0").put("net", "ws").put("host", "v.example.com").put("path", "/vm").put("tls", "")
        val s = ShareLink.parse("vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray()))
        Configs.save("vmess", s)
        assertEquals("VMess WS", s.name)
        assertEquals(8080, s.port)
        assertEquals("auto", s.outbound.getString("security"))
        assertEquals("/vm", s.outbound.getJSONObject("transport").getString("path"))
        assertFalse(s.outbound.has("tls"))
    }

    @Test
    fun trojanIsTlsUnlessSaidOtherwise() {
        val s = ShareLink.parse("trojan://p%40ss@203.0.113.30:443?sni=t.example.com#Trojan")
        Configs.save("trojan", s)
        assertEquals("p@ss", s.outbound.getString("password"))
        assertEquals("t.example.com", s.outbound.getJSONObject("tls").getString("server_name"))
        assertEquals("trojan / tcp / tls", s.kind)
    }

    @Test
    fun shadowsocksBothForms() {
        val userInfo = Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:secret".toByteArray()).trimEnd('=')
        val modern = ShareLink.parse("ss://$userInfo@203.0.113.40:8388#SS")
        Configs.save("shadowsocks", modern)
        assertEquals("chacha20-ietf-poly1305", modern.outbound.getString("method"))
        assertEquals("secret", modern.outbound.getString("password"))
        val legacy = ShareLink.parse("ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:pw@203.0.113.41:8389".toByteArray()) + "#Old")
        assertEquals("203.0.113.41", legacy.host)
        assertEquals(8389, legacy.port)
        assertEquals("Old", legacy.name)
    }

    @Test
    fun hysteria2AndTuic() {
        val h = ShareLink.parse("hysteria2://pass@203.0.113.50:443?sni=h.example.com&obfs=salamander&obfs-password=x&mport=20000-30000#HY2")
        Configs.save("hysteria2", h)
        assertEquals("pass", h.outbound.getString("password"))
        assertEquals("salamander", h.outbound.getJSONObject("obfs").getString("type"))
        assertEquals("20000:30000", h.outbound.getJSONArray("server_ports").getString(0))
        val t = ShareLink.parse("tuic://$uuid:pw@203.0.113.51:443?congestion_control=bbr&sni=t.example.com#TUIC")
        Configs.save("tuic", t)
        assertEquals("pw", t.outbound.getString("password"))
        assertEquals("bbr", t.outbound.getString("congestion_control"))
    }

    @Test
    fun linksThatNeedXrayOrAreBroken() {
        val bad = listOf(
            "vless://$uuid@example.com:443?type=xhttp&security=tls" to "Xray",
            "vless://$uuid@example.com:443?encryption=mlkem768x25519plus.native.0rtt.abc" to "Xray",
            "vless://$uuid@example.com:443?type=tcp&headerType=http" to "Xray",
            "vless://$uuid@example.com:443?security=tls&fm=%7B%7D" to "Xray",
            "wireguard://abc@example.com:51820" to "not supported",
            "vmess://not-json" to "VMess",
            "vless://example.com:443" to "UUID",
            "vless://$uuid@example.com:99999" to "port",
            "vless://$uuid@example.com:443?security=reality&sni=a.example.com" to "pbk",
        )
        for ((link, word) in bad) {
            val e = assertThrows(link, LinkException::class.java) { ShareLink.parse(link) }
            assertTrue("${e.message} should mention $word", e.message!!.contains(word))
        }
    }

    @Test
    fun extractingLinks() {
        val a = "vless://$uuid@example.com:443#a"
        val b = "trojan://pw@example.com:443#b"
        assertEquals(listOf(a, b), ShareLink.extract("junk\n$a\r\n  $b  \n$a\n"))
        assertEquals(listOf(a, b), ShareLink.extract(Base64.getEncoder().encodeToString("$a\n$b".toByteArray())))
        assertEquals(listOf(a), ShareLink.extract(Base64.getUrlEncoder().withoutPadding().encodeToString(a.toByteArray())))
        assertTrue(ShareLink.extract("{\"outbounds\":[]}").isEmpty())
        assertTrue(ShareLink.extract("").isEmpty())
    }

    @Test
    fun namesOfBrokenLinks() {
        assertEquals("ok", ShareLink.nameOf("vless://$uuid@example.com:443?type=xhttp#ok"))
    }

    @Test
    fun base64() {
        assertEquals("hello world", Base64Text.decode("aGVsbG8gd29ybGQ"))
        assertEquals("hello world", Base64Text.decode("aGVsbG8g\nd29ybGQ="))
        assertThrows(IllegalArgumentException::class.java) { Base64Text.decode("not base64!") }
    }

    @Test
    fun publicConfigFilter() {
        assertNull(PublicConfigs.refusal(ShareLink.parse("vless://$uuid@example.com:443?security=tls&sni=example.com#ok")))
        assertEquals("not encrypted", PublicConfigs.refusal(ShareLink.parse("vless://$uuid@example.com:80?security=none#plain")))
        assertEquals("doesn't check the server's certificate", PublicConfigs.refusal(ShareLink.parse("trojan://pw@example.com:443?allowInsecure=1#insecure")))
        assertEquals("a local or Iranian address", PublicConfigs.refusal(ShareLink.parse("vless://$uuid@10.10.34.35:443?security=tls#fake")))
        assertEquals("a local or Iranian address", PublicConfigs.refusal(ShareLink.parse("vless://$uuid@site.ir:443?security=tls#ir")))
        val spread = PublicConfigs.spreadOut(
            listOf(Triple("a", "203.0.113.1", 10), Triple("b", "203.0.113.2", 20), Triple("c", "203.0.113.3", 30), Triple("d", "198.51.100.1", 40)),
            10,
        )
        assertEquals(listOf("a", "b", "d"), spread.map { it.first })
    }
}

/** Saves configs so the build can validate each with the real `sing-box check`. */
object Configs {
    private val dir = File("build/generated-configs").apply { mkdirs() }
    private val rules = File("build/rules").absoluteFile

    fun save(name: String, link: ParsedLink) {
        File(dir, "$name.json").writeText(build(listOf("x" to link), "x", bypassIran = false).json)
    }

    fun build(servers: List<Pair<String, ParsedLink>>, selected: String, bypassIran: Boolean = true): BuiltConfig = BoxConfig.build(
        BoxInput(
            servers,
            selected,
            servers.map { it.first },
            bypassIran,
            rules.path.replace('\\', '/'),
            File("build/box.log").absolutePath.replace('\\', '/'),
            LocalAccess(9090, "", "secret"),
            LocalAccess(2080, "user", "pass"),
        ),
    )

    fun write(name: String, config: BuiltConfig) {
        File(dir, "$name.json").writeText(config.json)
    }
}
