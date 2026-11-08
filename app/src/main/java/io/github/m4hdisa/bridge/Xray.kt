package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Links only Xray can run (xhttp, VLESS Encryption, TCP HTTP camouflage, fragmenting), the same
 * way as `xray.ts` in the Windows app. sing-box stays in charge of the VPN, routing and DNS;
 * those servers run in a separate Xray process, and sing-box reaches each one through a local
 * SOCKS port:
 *   app -> VPN -> sing-box (rules) -> socks 127.0.0.1 -> Xray -> VPN server
 */
object Xray {
    class XrayLinkException(message: String) : Exception(message)

    private fun truthy(v: String?) = v == "1" || v == "true"

    private fun list(v: String?): JSONArray? = v?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { JSONArray(it) }

    private fun JSONObject.putIf(key: String, value: Any?): JSONObject {
        if (value != null && value != "") put(key, value)
        return this
    }

    private class Params(val get: (String) -> String?) {
        fun of(key: String): String? = get(key)?.takeIf { it.isNotEmpty() }
    }

    private fun buildStream(
        type: String?,
        security: String?,
        sni: String?,
        fp: String?,
        alpn: String?,
        insecure: Boolean,
        pbk: String?,
        sid: String?,
        spx: String?,
        pqv: String?,
        host: String?,
        path: String?,
        mode: String?,
        extra: String?,
        serviceName: String?,
        authority: String?,
        headerType: String?,
        seed: String?,
    ): JSONObject {
        val stream = JSONObject()
        when (val t = (type ?: "raw").lowercase()) {
            "raw", "tcp", "" -> {
                stream.put("method", "raw")
                if ((headerType ?: "").lowercase() == "http") {
                    val request = JSONObject().put("path", list(path) ?: JSONArray().put("/"))
                    list(host)?.let { request.put("headers", JSONObject().put("Host", it)) }
                    stream.put("rawSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", request)))
                }
            }
            "xhttp", "splithttp" -> {
                stream.put("method", "xhttp")
                val xhttp = JSONObject().putIf("host", host).put("path", path ?: "/").put("mode", mode ?: "auto")
                // A malformed "extra" is left out; the basic settings still work.
                extra?.let { runCatching { xhttp.put("extra", JSONObject(it)) } }
                stream.put("xhttpSettings", xhttp)
            }
            "ws", "websocket" -> {
                stream.put("method", "websocket")
                stream.put("wsSettings", JSONObject().put("path", path ?: "/").putIf("host", host))
            }
            "httpupgrade" -> {
                stream.put("method", "httpupgrade")
                stream.put("httpupgradeSettings", JSONObject().put("path", path ?: "/").putIf("host", host))
            }
            "grpc" -> {
                stream.put("method", "grpc")
                stream.put(
                    "grpcSettings",
                    JSONObject().putIf("serviceName", serviceName ?: path).putIf("authority", authority).also { if (mode == "multi") it.put("multiMode", true) },
                )
            }
            "kcp", "mkcp" -> {
                stream.put("method", "mkcp")
                stream.put("kcpSettings", JSONObject().putIf("seed", seed))
            }
            else -> throw XrayLinkException("Transport \"$t\" is not supported")
        }
        when ((security ?: "none").lowercase()) {
            "tls" -> {
                stream.put("security", "tls")
                stream.put(
                    "tlsSettings",
                    JSONObject()
                        .putIf("serverName", sni ?: host)
                        .put("fingerprint", fp ?: "chrome")
                        .putIf("alpn", list(alpn))
                        .also { if (insecure) it.put("allowInsecure", true) },
                )
            }
            "reality" -> {
                if (pbk == null) throw XrayLinkException("The REALITY link is missing pbk (public key)")
                stream.put("security", "reality")
                stream.put(
                    "realitySettings",
                    JSONObject()
                        .putIf("serverName", sni)
                        // REALITY needs uTLS, so Xray's "unsafe" (plain Go TLS) is not allowed here.
                        .put("fingerprint", fp?.takeIf { it != "unsafe" } ?: "chrome")
                        .put("password", pbk)
                        .putIf("shortId", sid)
                        .putIf("spiderX", spx)
                        .putIf("mldsa65Verify", pqv),
                )
            }
        }
        return stream
    }

    private fun streamFromParams(p: Params, defaultSecurity: String? = null): JSONObject {
        val stream = buildStream(
            type = p.of("type"),
            security = p.get("security") ?: defaultSecurity,
            sni = p.of("sni") ?: p.of("peer"),
            fp = p.of("fp"),
            alpn = p.of("alpn"),
            insecure = truthy(p.of("allowInsecure")) || truthy(p.of("insecure")),
            pbk = p.of("pbk"),
            sid = p.of("sid"),
            spx = p.of("spx"),
            pqv = p.of("pqv"),
            host = p.of("host"),
            path = p.of("path"),
            mode = p.of("mode"),
            extra = p.of("extra"),
            serviceName = p.of("serviceName"),
            authority = p.of("authority"),
            headerType = p.of("headerType"),
            seed = p.of("seed"),
        )
        // Xray's own share links carry finalmask (fragmenting, for example) as JSON in "fm".
        p.of("fm")?.let { text ->
            try {
                stream.put("finalmask", JSONObject(text))
            } catch (_: JSONException) {
                // A malformed "fm" is left out; the basic settings still work.
            }
        }
        return stream
    }

    private class UrlParts(val user: String, val host: String, val port: Int, val params: Params)

    private fun urlParts(link: String): UrlParts {
        val rest = link.substringAfter("://").substringBefore('#')
        val query = rest.substringAfter('?', "")
        val beforeQuery = rest.substringBefore('?')
        val userInfo = beforeQuery.substringBeforeLast('@', "")
        val address = beforeQuery.substringAfterLast('@').substringBefore('/')
        val host: String
        val portText: String
        if (address.startsWith("[")) {
            host = address.substringAfter('[').substringBefore(']')
            portText = address.substringAfter(']', "").removePrefix(":")
        } else {
            host = address.substringBefore(':')
            portText = address.substringAfter(':', "")
        }
        val port = if (portText.isEmpty()) 443 else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw XrayLinkException("The link has an invalid port")
        if (host.isEmpty()) throw XrayLinkException("The link is missing the server address")
        val params = query.split('&').filter { it.isNotEmpty() }.associate { decode(it.substringBefore('='), true) to decode(it.substringAfter('=', ""), true) }
        return UrlParts(decode(userInfo.substringBefore(':'), false), host.lowercase(), port, Params { params[it] })
    }

    private fun decode(text: String, plusIsSpace: Boolean): String = try {
        URLDecoder.decode(if (plusIsSpace) text else text.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }

    /** A share link as an Xray outbound (without a tag). Throws if Xray can't run it. */
    fun outbound(link: String): JSONObject {
        val text = link.trim()
        return when (val scheme = text.substringBefore("://", "").lowercase()) {
            "vless" -> {
                val u = urlParts(text)
                if (u.user.isEmpty()) throw XrayLinkException("The VLESS link is missing the UUID")
                JSONObject()
                    .put("protocol", "vless")
                    .put(
                        "settings",
                        JSONObject().put("address", u.host).put("port", u.port).put("id", u.user)
                            .put("encryption", u.params.of("encryption") ?: "none")
                            .putIf("flow", u.params.of("flow")),
                    )
                    .put("streamSettings", streamFromParams(u.params))
            }
            "trojan" -> {
                val u = urlParts(text)
                if (u.user.isEmpty()) throw XrayLinkException("The Trojan link is missing the password")
                JSONObject()
                    .put("protocol", "trojan")
                    .put("settings", JSONObject().put("address", u.host).put("port", u.port).put("password", u.user))
                    .put("streamSettings", streamFromParams(u.params, defaultSecurity = "tls"))
            }
            "vmess" -> {
                val j = try {
                    JSONObject(Base64Text.decode(text.substringAfter("://")))
                } catch (_: Exception) {
                    throw XrayLinkException("The VMess link is not valid base64 JSON")
                }
                fun s(key: String): String? = if (j.isNull(key)) null else j.opt(key)?.toString()?.takeIf { it.isNotEmpty() }
                val address = s("add") ?: throw XrayLinkException("The VMess link is missing the address")
                val id = s("id") ?: throw XrayLinkException("The VMess link is missing the id")
                JSONObject()
                    .put("protocol", "vmess")
                    .put(
                        "settings",
                        JSONObject().put("address", address.lowercase()).put("port", s("port")?.toIntOrNull() ?: 443).put("id", id).put("security", s("scy") ?: "auto"),
                    )
                    .put(
                        "streamSettings",
                        buildStream(
                            type = s("net"), security = s("tls"), sni = s("sni"), fp = s("fp"), alpn = s("alpn"),
                            insecure = false, pbk = null, sid = null, spx = null, pqv = null,
                            host = s("host"), path = s("path"), mode = s("mode"), extra = null,
                            serviceName = s("path"), authority = null, headerType = s("type"), seed = null,
                        ),
                    )
            }
            else -> throw XrayLinkException("$scheme:// links are not handled by the Xray core in Bridge")
        }
    }

    /** The VPN server an Xray outbound connects to. */
    fun server(o: JSONObject): Pair<String, Int> {
        val s = o.optJSONObject("settings") ?: JSONObject()
        return s.optString("address") to s.optInt("port")
    }

    /** Dials the resolved IP and keeps the server's name for TLS and the HTTP Host. */
    fun withServerIp(o: JSONObject, ip: String): JSONObject {
        val host = server(o).first
        if (host.isEmpty() || host == ip) return o
        val out = JSONObject(o.toString())
        out.getJSONObject("settings").put("address", ip)
        val stream = out.optJSONObject("streamSettings") ?: JSONObject().also { out.put("streamSettings", it) }
        if (stream.optString("security") == "tls") {
            val tls = stream.optJSONObject("tlsSettings") ?: JSONObject().also { stream.put("tlsSettings", it) }
            if (!tls.has("serverName")) tls.put("serverName", host)
        }
        keepHost(stream, host)
        return out
    }

    /** Keeps the server's name in the HTTP Host of ws / httpupgrade / xhttp once Xray dials another address. */
    private fun keepHost(stream: JSONObject, host: String) {
        for (key in listOf("wsSettings", "httpupgradeSettings", "xhttpSettings")) {
            val settings = stream.optJSONObject(key) ?: continue
            if (settings.optString("host").isEmpty()) settings.put("host", host)
        }
    }

    /**
     * Plain VLESS / Trojan: no TLS, no REALITY and no VLESS Encryption. Xray 26 only dials these to
     * a private address, so they go through a local relay inside the same Xray. The traffic is the
     * same; only the hop to the server changes.
     */
    fun needsRelay(o: JSONObject): Boolean {
        if (o.optJSONObject("streamSettings")?.optString("security").orEmpty().isNotEmpty()) return false
        return when (o.optString("protocol")) {
            "vless" -> (o.optJSONObject("settings")?.optString("encryption") ?: "none").ifEmpty { "none" } == "none"
            "trojan" -> true
            else -> false
        }
    }

    /** One server for the Xray process: the local SOCKS port sing-box reaches it on, and its relay port if it needs one. */
    class Entry(val port: Int, val outbound: JSONObject, val relayPort: Int? = null)

    /**
     * One local SOCKS port per server, with a password so other apps on the phone can't use it;
     * each port is routed to its own outbound. Xray's own connections leave outside the VPN,
     * because Bridge itself is left out of it.
     */
    fun config(entries: List<Entry>, user: String, secret: String, logLevel: String = "warning"): JSONObject {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        entries.forEachIndexed { i, e ->
            inbounds.put(
                JSONObject()
                    .put("tag", "in-$i")
                    .put("listen", "127.0.0.1")
                    .put("port", e.port)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "password").put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", secret))).put("udp", true)),
            )
            rules.put(JSONObject().put("inboundTag", JSONArray().put("in-$i")).put("outboundTag", "out-$i"))
            val o = JSONObject(e.outbound.toString())
            if (e.relayPort == null) {
                outbounds.put(o.put("tag", "out-$i"))
                return@forEachIndexed
            }
            val (host, port) = server(o)
            o.getJSONObject("settings").put("address", "127.0.0.1").put("port", e.relayPort)
            keepHost(o.optJSONObject("streamSettings") ?: JSONObject().also { o.put("streamSettings", it) }, host)
            outbounds.put(o.put("tag", "out-$i"))
            inbounds.put(
                JSONObject()
                    .put("tag", "relay-in-$i")
                    .put("listen", "127.0.0.1")
                    .put("port", e.relayPort)
                    .put("protocol", "dokodemo-door")
                    .put("settings", JSONObject().put("address", host).put("port", port).put("network", "tcp,udp")),
            )
            outbounds.put(JSONObject().put("tag", "relay-out-$i").put("protocol", "freedom"))
            rules.put(JSONObject().put("inboundTag", JSONArray().put("relay-in-$i")).put("outboundTag", "relay-out-$i"))
        }
        // Xray writes its log to its output, which Bridge copies into the connection log.
        return JSONObject()
            // At "info" (the detailed log) every connection is written too.
            .put("log", JSONObject().put("loglevel", logLevel).put("access", if (logLevel == "info" || logLevel == "debug") "" else "none"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", rules))
    }
}
