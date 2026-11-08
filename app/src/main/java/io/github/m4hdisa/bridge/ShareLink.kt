package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder

/**
 * A share link turned into an outbound for the core that runs it. For servers only Xray can run,
 * `xray` is the Xray outbound and `outbound` only says where the server is; at connect time
 * sing-box gets a SOCKS hop into the Xray process instead.
 */
data class ParsedLink(val name: String, val host: String, val port: Int, val kind: String, val outbound: JSONObject, val xray: JSONObject? = null) {
    val usesXray get() = xray != null
}

open class LinkException(message: String) : Exception(message)

/** sing-box can't run this link, but Xray may. */
private class XrayOnlyException(message: String) : LinkException(message)

/**
 * Reads vless://, vmess://, trojan://, ss://, hysteria2:// and tuic:// links with the same rules
 * as `parser.ts` in the Windows app: sing-box when it can, otherwise Xray (xhttp, VLESS
 * Encryption, TCP with an HTTP header, fragmenting).
 */
object ShareLink {
    private val FINGERPRINTS = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")
    private const val XRAY_ONLY = "is Xray-only"

    fun parse(link: String): ParsedLink {
        val text = link.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        try {
            return when (scheme) {
                "vless" -> vless(text)
                "vmess" -> vmess(text)
                "trojan" -> trojan(text)
                "ss" -> shadowsocks(text)
                "hysteria2", "hy2" -> hysteria2(text)
                "tuic" -> tuic(text)
                "" -> throw LinkException("This is not a share link")
                else -> throw LinkException("$scheme:// links are not supported")
            }
        } catch (e: XrayOnlyException) {
            val xray = try {
                Xray.outbound(text)
            } catch (x: Xray.XrayLinkException) {
                throw LinkException("${e.message}, and Xray can't run it either: ${x.message}")
            }
            return xrayLink(text, scheme, xray)
        }
    }

    private fun xrayLink(link: String, scheme: String, xray: JSONObject): ParsedLink {
        val (host, port) = Xray.server(xray)
        val stream = xray.optJSONObject("streamSettings")
        val name = decode(link.substringAfter('#', ""), plusIsSpace = false).let { n ->
            if (scheme == "vmess") runCatching { JSONObject(Base64Text.decode(link.substringAfter("://"))).optString("ps") }.getOrDefault("") else n
        }
        val transport = stream?.optString("method")?.takeIf { it != "raw" } ?: "tcp"
        val kind = kind(scheme, transport, stream?.optString("security"))
        // Only says where the server is; the real hop is made at connect time.
        val placeholder = JSONObject().put("type", if (scheme == "ss") "shadowsocks" else scheme).put("server", host).put("server_port", port)
        return ParsedLink(name.ifEmpty { "$host:$port" }, host, port, kind, placeholder, xray)
    }

    /**
     * Share links in any text: one per line, a whole base64-encoded subscription, a mix, or a
     * JSON config file (Xray, sing-box or Clash), whose servers are written as links.
     */
    fun extract(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ConfigFile.links(trimmed)
        var body = trimmed
        if ("://" !in trimmed && BASE64.matches(trimmed.filterNot { it.isWhitespace() })) {
            body = try {
                Base64Text.decode(trimmed)
            } catch (_: IllegalArgumentException) {
                trimmed
            }
        }
        return body.lines().map { it.trim() }.filter { LINK.containsMatchIn(it) }.distinct()
    }

    /** The name a link carries, even one that can't be used. */
    fun nameOf(link: String): String = try {
        parse(link).name
    } catch (_: Exception) {
        decode(link.substringAfter('#', ""), plusIsSpace = false).ifEmpty { link.substringAfter("@", link).substringBefore('?').take(40) }
    }

    private val BASE64 = Regex("^[A-Za-z0-9+/=_-]+$")
    private val LINK = Regex("^[a-z0-9]+://", RegexOption.IGNORE_CASE)

    // ---------- helpers ----------

    private class Parts(val user: String, val pass: String, val host: String, val port: Int, val params: Map<String, String>, val name: String) {
        fun param(key: String): String? = params[key]?.takeIf { it.isNotEmpty() }
    }

    private fun parts(link: String): Parts {
        val rest = link.substringAfter("://")
        val name = decode(rest.substringAfter('#', ""), plusIsSpace = false)
        val beforeName = rest.substringBefore('#')
        val params = query(beforeName.substringAfter('?', ""))
        val beforeQuery = beforeName.substringBefore('?')
        val userInfo = beforeQuery.substringBeforeLast('@', "")
        val (host, port) = hostPort(beforeQuery.substringAfterLast('@').substringBefore('/'))
        return Parts(
            decode(userInfo.substringBefore(':'), plusIsSpace = false),
            decode(userInfo.substringAfter(':', ""), plusIsSpace = false),
            host,
            port,
            params,
            name,
        )
    }

    private fun hostPort(text: String): Pair<String, Int> {
        val host: String
        val portText: String
        if (text.startsWith("[")) {
            host = text.substringAfter('[').substringBefore(']')
            portText = text.substringAfter(']', "").removePrefix(":")
        } else {
            host = text.substringBefore(':')
            portText = text.substringAfter(':', "")
        }
        if (host.isEmpty()) throw LinkException("The link is missing the server address")
        val port = if (portText.isEmpty()) 443 else portText.toIntOrNull()
        if (port == null || port !in 1..65535) throw LinkException("The link has an invalid port")
        return host.lowercase() to port
    }

    private fun query(text: String): Map<String, String> = text.split('&')
        .filter { it.isNotEmpty() }
        .associate { decode(it.substringBefore('='), plusIsSpace = true) to decode(it.substringAfter('=', ""), plusIsSpace = true) }

    /** URLDecoder always reads + as a space; in the name and user part of a link it is a real plus. */
    private fun decode(text: String, plusIsSpace: Boolean): String = try {
        URLDecoder.decode(if (plusIsSpace) text else text.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }

    private fun String?.truthy() = this == "1" || this == "true"

    private fun list(value: String?): List<String>? = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    private fun kind(vararg parts: String?): String = parts.filter { !it.isNullOrEmpty() && it != "none" }.joinToString(" / ")

    private fun tls(
        security: String?,
        sni: String?,
        fp: String?,
        alpn: String?,
        insecure: Boolean,
        publicKey: String? = null,
        shortId: String? = null,
        fallbackSni: String? = null,
    ): JSONObject? {
        val mode = (security ?: "").lowercase()
        if (mode != "tls" && mode != "reality" && mode != "xtls") return null
        val tls = JSONObject().put("enabled", true)
        (sni?.takeIf { it.isNotEmpty() } ?: fallbackSni?.takeIf { it.isNotEmpty() })?.let { tls.put("server_name", it) }
        if (insecure) tls.put("insecure", true)
        list(alpn)?.let { tls.put("alpn", JSONArray(it)) }
        val fingerprint = fp?.takeIf { it.isNotEmpty() } ?: if (mode == "reality") "chrome" else null
        if (fingerprint != null) {
            // Panels sometimes send fingerprints sing-box refuses to start with, such as Xray's "unsafe".
            val known = fingerprint.lowercase().takeIf { it in FINGERPRINTS } ?: "chrome"
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", known))
        }
        if (mode == "reality") {
            val key = publicKey?.takeIf { it.isNotEmpty() } ?: throw LinkException("The REALITY link is missing pbk (public key)")
            val reality = JSONObject().put("enabled", true).put("public_key", key)
            shortId?.takeIf { it.isNotEmpty() }?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        return tls
    }

    private fun transport(type: String?, host: String?, path: String?, serviceName: String?, headerType: String?): JSONObject? {
        val h = host?.takeIf { it.isNotEmpty() }
        val p = path?.takeIf { it.isNotEmpty() }
        return when (val t = (type ?: "tcp").lowercase()) {
            "tcp", "raw", "" -> {
                if ((headerType ?: "none").lowercase() == "http") throw XrayOnlyException("TCP with an HTTP header $XRAY_ONLY")
                null
            }
            "ws" -> webSocket(h, p ?: "/")
            "grpc" -> JSONObject().put("type", "grpc").also { o ->
                (serviceName?.takeIf { it.isNotEmpty() } ?: p)?.let { o.put("service_name", it) }
            }
            "http", "h2" -> JSONObject().put("type", "http").also { o ->
                list(h)?.let { o.put("host", JSONArray(it)) }
                p?.let { o.put("path", it) }
            }
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").also { o ->
                h?.let { o.put("host", it) }
                p?.let { o.put("path", it) }
            }
            "xhttp", "splithttp" -> throw XrayOnlyException("xhttp $XRAY_ONLY")
            "kcp", "mkcp" -> throw XrayOnlyException("mKCP $XRAY_ONLY")
            else -> throw LinkException("Transport \"$t\" is not supported")
        }
    }

    private fun webSocket(host: String?, rawPath: String): JSONObject {
        val t = JSONObject().put("type", "ws")
        var path = rawPath
        // Xray-style early data: /path?ed=2048
        Regex("[?&]ed=(\\d+)").find(path)?.let { match ->
            t.put("max_early_data", match.groupValues[1].toInt())
            t.put("early_data_header_name", "Sec-WebSocket-Protocol")
            path = path.replace(match.value, "").removeSuffix("?").ifEmpty { "/" }
        }
        t.put("path", path)
        host?.let { t.put("headers", JSONObject().put("Host", it)) }
        return t
    }

    private fun result(name: String, host: String, port: Int, kind: String, outbound: JSONObject) =
        ParsedLink(name.ifEmpty { "$host:$port" }, host, port, kind, outbound)

    // ---------- protocols ----------

    private fun vless(link: String): ParsedLink {
        val u = parts(link)
        if (u.user.isEmpty()) throw LinkException("The link is missing the UUID")
        val encryption = (u.param("encryption") ?: "none").lowercase()
        // Xray's post-quantum VLESS Encryption is not in sing-box; connecting without it just times out.
        if (encryption != "none") throw XrayOnlyException("VLESS Encryption $XRAY_ONLY")
        // Xray's fragment and masking settings change how the connection looks; without them it may be blocked.
        if (u.param("fm") != null) throw XrayOnlyException("This link's fragment setting $XRAY_ONLY")
        val outbound = JSONObject().put("type", "vless").put("server", u.host).put("server_port", u.port).put("uuid", u.user)
        u.param("flow")?.let { outbound.put("flow", it) }
        tls(u.param("security"), u.param("sni"), u.param("fp"), u.param("alpn"), u.param("allowInsecure").truthy() || u.param("insecure").truthy(), u.param("pbk"), u.param("sid"), u.param("host"))
            ?.let { outbound.put("tls", it) }
        transport(u.param("type"), u.param("host"), u.param("path"), u.param("serviceName"), u.param("headerType"))?.let { outbound.put("transport", it) }
        return result(u.name, u.host, u.port, kind("vless", u.param("type") ?: "tcp", u.param("security")), outbound)
    }

    private fun vmess(link: String): ParsedLink {
        val j = try {
            JSONObject(Base64Text.decode(link.substringAfter("://")))
        } catch (_: IllegalArgumentException) {
            throw LinkException("The VMess link is not valid base64 JSON")
        } catch (_: JSONException) {
            throw LinkException("The VMess link is not valid base64 JSON")
        }
        fun s(key: String): String = if (j.isNull(key)) "" else j.opt(key)?.toString() ?: ""
        val server = s("add").lowercase()
        val port = s("port").ifEmpty { "443" }.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw LinkException("The link has an invalid port")
        if (server.isEmpty() || s("id").isEmpty()) throw LinkException("The VMess link is missing the address or id")
        val outbound = JSONObject()
            .put("type", "vmess")
            .put("server", server)
            .put("server_port", port)
            .put("uuid", s("id"))
            .put("security", s("scy").ifEmpty { "auto" })
            .put("alter_id", s("aid").toIntOrNull() ?: 0)
        tls(s("tls"), s("sni"), s("fp"), s("alpn"), s("allowInsecure").truthy() || s("skip-cert-verify").truthy(), fallbackSni = s("host"))?.let { outbound.put("tls", it) }
        transport(s("net"), s("host"), s("path"), s("path"), s("type"))?.let { outbound.put("transport", it) }
        return result(s("ps"), server, port, kind("vmess", s("net").ifEmpty { "tcp" }, s("tls")), outbound)
    }

    private fun trojan(link: String): ParsedLink {
        val u = parts(link)
        if (u.user.isEmpty()) throw LinkException("The Trojan link is missing the password")
        val outbound = JSONObject().put("type", "trojan").put("server", u.host).put("server_port", u.port).put("password", u.user)
        // Trojan is always TLS unless the link says otherwise.
        val security = u.param("security") ?: "tls"
        tls(security, u.param("sni") ?: u.param("peer"), u.param("fp"), u.param("alpn"), u.param("allowInsecure").truthy() || u.param("insecure").truthy(), u.param("pbk"), u.param("sid"), u.host)
            ?.let { outbound.put("tls", it) }
        transport(u.param("type"), u.param("host"), u.param("path"), u.param("serviceName"), u.param("headerType"))?.let { outbound.put("transport", it) }
        return result(u.name, u.host, u.port, kind("trojan", u.param("type") ?: "tcp", security), outbound)
    }

    private fun shadowsocks(link: String): ParsedLink {
        var rest = link.substringAfter("://")
        val name = decode(rest.substringAfter('#', ""), plusIsSpace = false)
        rest = rest.substringBefore('#')
        val queryText = rest.substringAfter('?', "")
        rest = rest.substringBefore('?').removeSuffix("/")
        // The old form: everything before # is base64(method:password@host:port).
        if ('@' !in rest) rest = decodeOrFail(rest)
        val at = rest.lastIndexOf('@')
        if (at < 0) throw LinkException("The Shadowsocks link is malformed")
        var userInfo = decode(rest.substring(0, at), plusIsSpace = false)
        if (':' !in userInfo) userInfo = decodeOrFail(userInfo)
        val method = userInfo.substringBefore(':')
        val password = userInfo.substringAfter(':', "")
        val match = Regex("^\\[?([^\\]]+?)]?:(\\d+)$").find(rest.substring(at + 1))
        if (match == null || method.isEmpty()) throw LinkException("The Shadowsocks link is malformed")
        val server = match.groupValues[1].lowercase()
        val port = match.groupValues[2].toInt().takeIf { it in 1..65535 } ?: throw LinkException("The link has an invalid port")
        val outbound = JSONObject().put("type", "shadowsocks").put("server", server).put("server_port", port).put("method", method).put("password", password)
        query(queryText)["plugin"]?.takeIf { it.isNotEmpty() }?.let { plugin ->
            val pluginName = plugin.substringBefore(';')
            val supported = mapOf("obfs-local" to "obfs-local", "simple-obfs" to "obfs-local", "v2ray-plugin" to "v2ray-plugin")
            outbound.put("plugin", supported[pluginName] ?: throw LinkException("The Shadowsocks plugin \"$pluginName\" is not supported"))
            outbound.put("plugin_opts", plugin.substringAfter(';', ""))
        }
        return result(name, server, port, kind("shadowsocks", method), outbound)
    }

    private fun decodeOrFail(text: String): String = try {
        Base64Text.decode(text)
    } catch (_: IllegalArgumentException) {
        throw LinkException("The Shadowsocks link is malformed")
    }

    private fun hysteria2(link: String): ParsedLink {
        val u = parts(link)
        val outbound = JSONObject().put("type", "hysteria2").put("server", u.host).put("server_port", u.port)
        val password = if (u.pass.isNotEmpty()) "${u.user}:${u.pass}" else u.user
        if (password.isNotEmpty()) outbound.put("password", password)
        val tls = JSONObject().put("enabled", true).put("server_name", u.param("sni") ?: u.host)
        if (u.param("insecure").truthy()) tls.put("insecure", true)
        tls.put("alpn", JSONArray(list(u.param("alpn")) ?: listOf("h3")))
        outbound.put("tls", tls)
        u.param("obfs")?.takeIf { it != "none" }?.let { outbound.put("obfs", JSONObject().put("type", it).put("password", u.params["obfs-password"] ?: "")) }
        u.param("mport")?.let { ports -> outbound.put("server_ports", JSONArray(ports.split(',').map { it.trim().replace('-', ':') })) }
        return result(u.name, u.host, u.port, "hysteria2", outbound)
    }

    private fun tuic(link: String): ParsedLink {
        val u = parts(link)
        if (u.user.isEmpty()) throw LinkException("The TUIC link is missing the UUID")
        val outbound = JSONObject().put("type", "tuic").put("server", u.host).put("server_port", u.port).put("uuid", u.user)
        if (u.pass.isNotEmpty()) outbound.put("password", u.pass)
        u.param("congestion_control")?.let { outbound.put("congestion_control", it) }
        u.param("udp_relay_mode")?.let { outbound.put("udp_relay_mode", it) }
        val tls = JSONObject().put("enabled", true).put("server_name", u.param("sni") ?: u.host)
        if (u.param("allow_insecure").truthy() || u.param("insecure").truthy()) tls.put("insecure", true)
        tls.put("alpn", JSONArray(list(u.param("alpn")) ?: listOf("h3")))
        outbound.put("tls", tls)
        return result(u.name, u.host, u.port, "tuic", outbound)
    }
}

/** Base64 in either alphabet, with or without padding; android.util.Base64 isn't there in unit tests. */
object Base64Text {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(input: String): String = decodeBytes(input).toString(Charsets.UTF_8)

    fun decodeBytes(input: String): ByteArray {
        val text = input.replace('-', '+').replace('_', '/').filterNot { it.isWhitespace() }.trimEnd('=')
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "not base64" }
            buffer = ((buffer shl 6) or value) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** Standard alphabet, padded. */
    fun encode(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        for (i in bytes.indices step 3) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes.getOrNull(i + 1)?.toInt()?.and(0xFF)
            val b2 = bytes.getOrNull(i + 2)?.toInt()?.and(0xFF)
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or ((b1 ?: 0) shr 4)])
            out.append(if (b1 == null) '=' else ALPHABET[((b1 and 0x0F) shl 2) or ((b2 ?: 0) shr 6)])
            out.append(if (b2 == null) '=' else ALPHABET[b2 and 0x3F])
        }
        return out.toString()
    }
}
