package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** A VLESS share link turned into a sing-box outbound. The same rules as `parseVless` in the Windows app. */
data class VlessServer(val name: String, val host: String, val port: Int, val outbound: JSONObject)

class LinkException(message: String) : Exception(message)

object VlessLink {
    private val FINGERPRINTS = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")

    fun parse(link: String): VlessServer {
        val text = link.trim()
        if (!text.startsWith("vless://", ignoreCase = true)) throw LinkException("Only vless:// links work in this version")
        val rest = text.substring("vless://".length)
        val name = decode(rest.substringAfter('#', ""), plusIsSpace = false)
        val beforeName = rest.substringBefore('#')
        val params = query(beforeName.substringAfter('?', ""))
        val address = beforeName.substringBefore('?').trimEnd('/')
        if ('@' !in address) throw LinkException("The link is missing the UUID")
        val uuid = decode(address.substringBeforeLast('@'), plusIsSpace = false)
        if (uuid.isEmpty()) throw LinkException("The link is missing the UUID")
        val (host, port) = hostPort(address.substringAfterLast('@'))

        val encryption = (params["encryption"] ?: "none").lowercase()
        if (encryption != "none") throw LinkException("VLESS Encryption needs the Xray core, which comes in a later version")

        val outbound = JSONObject()
            .put("type", "vless")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
        params["flow"]?.takeIf { it.isNotEmpty() }?.let { outbound.put("flow", it) }
        tls(params)?.let { outbound.put("tls", it) }
        transport(params)?.let { outbound.put("transport", it) }
        return VlessServer(name.ifEmpty { "$host:$port" }, host, port, outbound)
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
        return host to port
    }

    private fun query(text: String): Map<String, String> = text.split('&')
        .filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to decode(it.substringAfter('=', ""), plusIsSpace = true) }

    /** URLDecoder always reads + as a space; in the name and UUID part of a link it is a real plus. */
    private fun decode(text: String, plusIsSpace: Boolean): String = try {
        URLDecoder.decode(if (plusIsSpace) text else text.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        text
    }

    private fun tls(p: Map<String, String>): JSONObject? {
        val security = (p["security"] ?: "").lowercase()
        if (security != "tls" && security != "reality" && security != "xtls") return null
        val tls = JSONObject().put("enabled", true)
        (p["sni"]?.takeIf { it.isNotEmpty() } ?: p["host"]?.takeIf { it.isNotEmpty() })?.let { tls.put("server_name", it) }
        if (p["allowInsecure"].truthy() || p["insecure"].truthy()) tls.put("insecure", true)
        list(p["alpn"])?.let { tls.put("alpn", JSONArray(it)) }
        val fingerprint = p["fp"]?.takeIf { it.isNotEmpty() } ?: if (security == "reality") "chrome" else null
        if (fingerprint != null) {
            val known = fingerprint.lowercase().takeIf { it in FINGERPRINTS } ?: "chrome"
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", known))
        }
        if (security == "reality") {
            val key = p["pbk"]?.takeIf { it.isNotEmpty() } ?: throw LinkException("The REALITY link is missing pbk (public key)")
            val reality = JSONObject().put("enabled", true).put("public_key", key)
            p["sid"]?.takeIf { it.isNotEmpty() }?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        return tls
    }

    private fun transport(p: Map<String, String>): JSONObject? {
        val host = p["host"]?.takeIf { it.isNotEmpty() }
        val path = p["path"]?.takeIf { it.isNotEmpty() }
        return when (val type = (p["type"] ?: "tcp").lowercase()) {
            "tcp", "raw", "" -> {
                if ((p["headerType"] ?: "none").lowercase() == "http") throw LinkException("TCP with an HTTP header needs the Xray core, which comes in a later version")
                null
            }
            "ws" -> webSocket(host, path ?: "/")
            "grpc" -> JSONObject().put("type", "grpc").also { t ->
                (p["serviceName"]?.takeIf { it.isNotEmpty() } ?: path)?.let { t.put("service_name", it) }
            }
            "http", "h2" -> JSONObject().put("type", "http").also { t ->
                list(host)?.let { t.put("host", JSONArray(it)) }
                path?.let { t.put("path", it) }
            }
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").also { t ->
                host?.let { t.put("host", it) }
                path?.let { t.put("path", it) }
            }
            "xhttp", "splithttp" -> throw LinkException("xhttp needs the Xray core, which comes in a later version")
            else -> throw LinkException("Transport \"$type\" is not supported")
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

    private fun String?.truthy() = this == "1" || this == "true"

    private fun list(value: String?): List<String>? = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
}
