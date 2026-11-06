package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Some subscriptions (BPB Panel's Normal and Fragment, many others) answer with whole client
 * config files instead of share links. This pulls the servers out of Xray, sing-box and Clash
 * configs in JSON and writes them as share links, which is what the rest of Bridge works with.
 * The same rules as `configfile.ts` in the Windows app.
 */
object ConfigFile {
    private class Parts(
        val scheme: String,
        val user: String,
        val host: String,
        val port: Int,
        val name: String,
        val network: String? = null,
        val path: String? = null,
        val hostHeader: String? = null,
        val serviceName: String? = null,
        val mode: String? = null,
        val security: String? = null,
        val sni: String? = null,
        val fp: String? = null,
        val alpn: String? = null,
        val insecure: Boolean = false,
        val pbk: String? = null,
        val sid: String? = null,
        val spx: String? = null,
        val flow: String? = null,
        val encryption: String? = null,
        /** VMess only: how it encrypts. */
        val cipher: String? = null,
        /** Xray's finalmask, as JSON: fragmenting the TLS hello, for example. */
        val finalmask: String? = null,
    )

    private fun b64(text: String): String = Base64Text.encode(text)

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun str(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        else -> v.toString().takeIf { it.isNotEmpty() }
    }

    private fun alpnText(v: Any?): String? = when (v) {
        is JSONArray -> (0 until v.length()).joinToString(",") { v.optString(it) }.takeIf { it.isNotEmpty() }
        else -> str(v)
    }

    private fun earlyData(path: String?, ms: Any?): String? {
        val n = (ms as? Number)?.toInt() ?: str(ms)?.toIntOrNull() ?: 0
        if (n <= 0 || path == null || Regex("[?&]ed=").containsMatchIn(path)) return path
        return "$path${if ('?' in path) "&" else "?"}ed=$n"
    }

    private fun bracket(host: String) = if (':' in host) "[$host]" else host

    private fun toLink(p: Parts): String {
        if (p.scheme == "vmess") {
            val j = JSONObject()
                .put("v", "2").put("ps", p.name).put("add", p.host).put("port", p.port).put("id", p.user).put("aid", 0)
                .put("scy", p.cipher ?: "auto").put("net", p.network ?: "tcp").put("type", "none").put("host", p.hostHeader ?: "")
                .put("path", p.path ?: p.serviceName ?: "").put("tls", if (p.security == "tls") "tls" else "")
                .put("sni", p.sni ?: "").put("alpn", p.alpn ?: "").put("fp", p.fp ?: "")
            return "vmess://${b64(j.toString())}"
        }
        val params = mutableListOf<Pair<String, String>>()
        fun add(key: String, value: String?) {
            if (!value.isNullOrEmpty()) params += key to value
        }
        if (p.scheme == "vless") add("encryption", p.encryption ?: "none")
        add("type", p.network ?: "tcp")
        add("security", p.security ?: "none")
        add("host", p.hostHeader)
        add("path", p.path)
        add("serviceName", p.serviceName)
        add("mode", p.mode)
        add("sni", p.sni)
        add("fp", p.fp)
        add("alpn", p.alpn)
        add("allowInsecure", if (p.insecure) "1" else null)
        add("pbk", p.pbk)
        add("sid", p.sid)
        add("spx", p.spx)
        add("flow", p.flow)
        add("fm", p.finalmask)
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "${p.scheme}://${enc(p.user)}@${bracket(p.host)}:${p.port}?$query#${enc(p.name)}"
    }

    private fun ssLink(method: String, password: String, host: String, port: Int, name: String) =
        "ss://${b64("$method:$password")}@${bracket(host)}:$port#${enc(name)}"

    /** What fragmenting looks like when another outbound does it, in the terms of finalmask. "" means none; null means it can't be written as a link. */
    private fun dialerFinalmask(outbound: JSONObject, all: JSONArray): String? {
        val tag = str(outbound.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.opt("dialerProxy")) ?: return ""
        val other = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.firstOrNull { it.optString("tag") == tag }
        // A chain through another server can't be written as a link.
        val fragment = other?.optJSONObject("settings")?.optJSONObject("fragment") ?: return null
        val settings = JSONObject().put("packets", fragment.opt("packets")).put("length", fragment.opt("length")).put("delay", fragment.opt("interval"))
        return JSONObject().put("tcp", JSONArray().put(JSONObject().put("type", "fragment").put("settings", settings))).toString()
    }

    private fun fromXrayOutbound(o: JSONObject, all: JSONArray, name: String): String? {
        val dialed = dialerFinalmask(o, all) ?: return null
        val s = o.optJSONObject("settings") ?: JSONObject()
        val st = o.optJSONObject("streamSettings") ?: JSONObject()
        val target = s.optJSONArray("vnext")?.optJSONObject(0) ?: s.optJSONArray("servers")?.optJSONObject(0) ?: s
        val user = target.optJSONArray("users")?.optJSONObject(0) ?: target
        val host = str(target.opt("address")) ?: return null
        val port = target.optInt("port").takeIf { it in 1..65535 } ?: return null
        val protocol = o.optString("protocol")
        if (protocol == "shadowsocks") {
            val method = str(user.opt("method"))
            val password = str(user.opt("password"))
            return if (method != null && password != null) ssLink(method, password, host, port, name) else null
        }
        if (protocol != "vless" && protocol != "vmess" && protocol != "trojan") return null
        val credential = str(if (protocol == "trojan") user.opt("password") else user.opt("id")) ?: return null
        val network = str(st.opt("network")) ?: str(st.opt("method")) ?: "tcp"
        val transport = st.optJSONObject("wsSettings") ?: st.optJSONObject("httpupgradeSettings") ?: st.optJSONObject("xhttpSettings") ?: st.optJSONObject("splithttpSettings") ?: JSONObject()
        val tls = st.optJSONObject("tlsSettings") ?: JSONObject()
        val reality = st.optJSONObject("realitySettings") ?: JSONObject()
        return toLink(
            Parts(
                scheme = protocol,
                user = credential,
                host = host,
                port = port,
                name = name,
                network = when (network) {
                    "raw" -> "tcp"
                    "websocket" -> "ws"
                    else -> network
                },
                path = str(transport.opt("path")),
                hostHeader = str(transport.opt("host")) ?: str(transport.optJSONObject("headers")?.opt("Host")),
                serviceName = str(st.optJSONObject("grpcSettings")?.opt("serviceName")),
                mode = str(transport.opt("mode")),
                security = str(st.opt("security")),
                sni = str(tls.opt("serverName")) ?: str(reality.opt("serverName")),
                fp = str(tls.opt("fingerprint")) ?: str(reality.opt("fingerprint")),
                alpn = alpnText(tls.opt("alpn")),
                insecure = tls.optBoolean("allowInsecure"),
                pbk = str(reality.opt("password")) ?: str(reality.opt("publicKey")),
                sid = str(reality.opt("shortId")),
                spx = str(reality.opt("spiderX")),
                flow = str(user.opt("flow")),
                encryption = str(user.opt("encryption")),
                cipher = str(user.opt("security")),
                finalmask = st.optJSONObject("finalmask")?.toString() ?: dialed.ifEmpty { null },
            ),
        )
    }

    private fun fromSingBoxOutbound(o: JSONObject): String? {
        val host = str(o.opt("server")) ?: return null
        val port = o.optInt("server_port").takeIf { it in 1..65535 } ?: return null
        val name = str(o.opt("tag")) ?: "$host:$port"
        val type = o.optString("type")
        if (type == "shadowsocks") {
            val method = str(o.opt("method"))
            val password = str(o.opt("password"))
            return if (method != null && password != null) ssLink(method, password, host, port, name) else null
        }
        if (type != "vless" && type != "vmess" && type != "trojan") return null
        val credential = str(if (type == "trojan") o.opt("password") else o.opt("uuid")) ?: return null
        val t = o.optJSONObject("transport") ?: JSONObject()
        val tls = o.optJSONObject("tls") ?: JSONObject()
        val reality = tls.optJSONObject("reality")
        return toLink(
            Parts(
                scheme = type,
                user = credential,
                host = host,
                port = port,
                name = name,
                network = str(t.opt("type")),
                path = earlyData(str(t.opt("path")), t.opt("max_early_data")),
                hostHeader = str(t.optJSONObject("headers")?.opt("Host")) ?: str(t.opt("host")),
                serviceName = str(t.opt("service_name")),
                security = if (tls.optBoolean("enabled")) (if (reality?.optBoolean("enabled") == true) "reality" else "tls") else null,
                sni = str(tls.opt("server_name")),
                fp = str(tls.optJSONObject("utls")?.opt("fingerprint")),
                alpn = alpnText(tls.opt("alpn")),
                insecure = tls.optBoolean("insecure"),
                pbk = str(reality?.opt("public_key")),
                sid = str(reality?.opt("short_id")),
                flow = str(o.opt("flow")),
                cipher = str(o.opt("security")),
            ),
        )
    }

    private fun fromClashProxy(o: JSONObject): String? {
        val host = str(o.opt("server")) ?: return null
        val port = o.optInt("port").takeIf { it in 1..65535 } ?: return null
        val name = str(o.opt("name")) ?: "$host:$port"
        val type = o.optString("type")
        if (type == "ss") {
            val cipher = str(o.opt("cipher"))
            val password = str(o.opt("password"))
            return if (cipher != null && password != null) ssLink(cipher, password, host, port, name) else null
        }
        if (type != "vless" && type != "vmess" && type != "trojan") return null
        val credential = str(if (type == "trojan") o.opt("password") else o.opt("uuid")) ?: return null
        val ws = o.optJSONObject("ws-opts") ?: JSONObject()
        val reality = o.optJSONObject("reality-opts")
        return toLink(
            Parts(
                scheme = type,
                user = credential,
                host = host,
                port = port,
                name = name,
                network = str(o.opt("network")),
                path = earlyData(str(ws.opt("path")), ws.opt("max-early-data")),
                hostHeader = str(ws.optJSONObject("headers")?.opt("Host")),
                serviceName = str(o.optJSONObject("grpc-opts")?.opt("grpc-service-name")),
                // Trojan is always TLS in Clash, so the file doesn't have to say so.
                security = when {
                    reality != null -> "reality"
                    o.optBoolean("tls") || type == "trojan" -> "tls"
                    else -> null
                },
                sni = str(o.opt("servername")) ?: str(o.opt("sni")),
                fp = str(o.opt("client-fingerprint")),
                alpn = alpnText(o.opt("alpn")),
                insecure = o.optBoolean("skip-cert-verify"),
                pbk = str(reality?.opt("public-key")),
                sid = str(reality?.opt("short-id")),
                flow = str(o.opt("flow")),
                cipher = str(o.opt("cipher")),
            ),
        )
    }

    /** Without the name, so the same server under two names counts once. */
    private fun withoutName(link: String) = if (link.startsWith("vmess://")) link else link.substringBefore('#')

    /** The servers in a JSON config file (or a list of them), as share links. Empty if it isn't one. */
    fun links(text: String): List<String> {
        val trimmed = text.trim()
        val configs: List<JSONObject> = try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                trimmed.startsWith("{") -> listOf(JSONObject(trimmed))
                else -> return emptyList()
            }
        } catch (_: Exception) {
            return emptyList()
        }
        val links = mutableListOf<String?>()
        for (config in configs) {
            config.optJSONArray("proxies")?.let { p -> (0 until p.length()).forEach { links += p.optJSONObject(it)?.let(::fromClashProxy) } }
            val outbounds = config.optJSONArray("outbounds") ?: continue
            for (i in 0 until outbounds.length()) {
                val o = outbounds.optJSONObject(i) ?: continue
                links += when {
                    o.has("type") -> fromSingBoxOutbound(o)
                    o.has("protocol") -> fromXrayOutbound(o, outbounds, str(config.opt("remarks")) ?: str(o.opt("tag")) ?: "Config")
                    else -> null
                }
            }
        }
        val seen = HashSet<String>()
        return links.filterNotNull().filter { seen.add(withoutName(it)) }
    }
}
