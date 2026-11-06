package io.github.m4hdisa.bridge

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** The running sing-box's Clash API, for switching and testing servers while connected. */
object Clash {
    private fun call(access: LocalAccess, method: String, path: String, body: String? = null, timeoutMs: Int = 10_000): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${access.port}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 3_000
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Authorization", "Bearer ${access.secret}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    fun select(access: LocalAccess, tag: String): Boolean = call(access, "PUT", "/proxies/proxy", JSONObject().put("name", tag).toString()).first in 200..299

    /** A real request through that outbound, in ms, or DELAY_FAILED. */
    fun delay(access: LocalAccess, tag: String, timeoutMs: Int = 5_000): Int = try {
        val url = URLEncoder.encode(BoxConfig.TEST_URL, "UTF-8")
        val (code, text) = call(access, "GET", "/proxies/$tag/delay?url=$url&timeout=$timeoutMs", timeoutMs = timeoutMs + 2_000)
        if (code in 200..299) JSONObject(text).optInt("delay", DELAY_FAILED).takeIf { it > 0 } ?: DELAY_FAILED else DELAY_FAILED
    } catch (_: Exception) {
        DELAY_FAILED
    }

    /** The outbound tag traffic goes through now, looking inside Auto. */
    fun current(access: LocalAccess): String? = try {
        val now = JSONObject(call(access, "GET", "/proxies/proxy").second).optString("now")
        if (now == AUTO) JSONObject(call(access, "GET", "/proxies/$AUTO").second).optString("now").ifEmpty { null } else now.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

class FetchResult(val links: List<String>, val title: String?)

object Subscriptions {
    /** Directly first; if that fails while connected, through the VPN, since many lists are blocked in Iran. */
    fun download(url: String): Page {
        val direct = runCatching { downloadRetrying { Net.direct(url) } }
        val page = direct.getOrNull()
        if (page != null && page.ok) return page
        val session = VpnState.session
        if (session != null) {
            val through = runCatching { downloadRetrying { Net.through(session.proxy, url) } }.getOrNull()
            if (through != null && through.ok) return through
        }
        if (page != null) throw IOException("HTTP ${page.code}")
        throw direct.exceptionOrNull() ?: IOException("no answer")
    }

    /** Some panels answer 5xx now and then (rate limits); one retry usually gets through. */
    private fun downloadRetrying(get: () -> Page): Page {
        val first = get()
        if (first.code < 500) return first
        Thread.sleep(2_000)
        return get()
    }

    fun fetch(url: String): FetchResult {
        val page = download(url)
        var links = ShareLink.extract(page.body)
        if (links.isEmpty()) {
            panelSubscription(url)?.let { other -> links = runCatching { ShareLink.extract(download(other).body) }.getOrDefault(emptyList()) }
        }
        if (links.isEmpty()) throw IOException(noLinksReason(page.body))
        return FetchResult(links, title(page.headers["profile-title"]))
    }

    /** A BPB panel's own address (…/panel, …/login or …/sub) leads to its plain list. Its fragment list is JSON for Xray. */
    fun panelSubscription(url: String): String? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        val m = Regex("^(/[^/]+)/(?:panel|login|sub(?:/(?:normal|raw))?)/?$").find(u.path) ?: return null
        if (u.query?.contains("app=") == true) return null
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.protocol}://${u.host}$port${m.groupValues[1]}/sub/raw?app=xray"
    }

    fun noLinksReason(body: String): String {
        val text = body.trim()
        return when {
            text.isEmpty() -> "The subscription is empty"
            text.startsWith("<") -> "The address answered with a web page, not a subscription. If it is a panel, copy the subscription link from inside it"
            Regex("\"(wireguard|WireGuard)\"").containsMatchIn(text) -> "This subscription only has WireGuard (WARP) configs, which Bridge doesn't import"
            else -> "The subscription has no links Bridge can read"
        }
    }

    private fun title(header: String?): String? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (value.startsWith("base64:")) runCatching { Base64Text.decode(value.removePrefix("base64:")) }.getOrNull() else value
    }
}

/** Free public configs from Bridge's daily list (github.com/m4hdisa/Bridge-configs), tested outside Iran. */
object PublicConfigs {
    const val SUBSCRIPTION_ID = "public"
    private fun where(path: String) = listOf(
        "https://raw.githubusercontent.com/m4hdisa/Bridge-configs/main/$path",
        "https://cdn.jsdelivr.net/gh/m4hdisa/Bridge-configs@main/$path",
    )
    private val CLEAN = where("clean.txt")
    private val CLOUDFLARE = where("cloudflare.txt")
    private const val KEEP_CLEAN = 24
    private const val KEEP_CLOUDFLARE = 8

    /** Why a public config is left out, or null: the same checks as on Windows. */
    fun refusal(p: ParsedLink): String? {
        val o = p.outbound
        val host = p.host
        if ((':' !in host && '.' !in host) || host.endsWith(".ir") || host == "localhost" || isPrivate(host)) return "a local or Iranian address"
        p.xray?.let { x ->
            val stream = x.optJSONObject("streamSettings")
            val security = stream?.optString("security").orEmpty()
            val settings = x.optJSONObject("settings")
            val protocol = x.optString("protocol")
            val vlessEncrypted = protocol == "vless" && settings?.optString("encryption").orEmpty().ifEmpty { "none" } != "none"
            if (security.isEmpty() && (protocol == "trojan" || (protocol == "vless" && !vlessEncrypted))) return "not encrypted"
            if (protocol == "vmess" && settings?.optString("security") in setOf("none", "zero")) return "not encrypted"
            if (stream?.optJSONObject("tlsSettings")?.optBoolean("allowInsecure") == true) return "doesn't check the server's certificate"
            return null
        }
        val type = o.optString("type")
        if (type == "vmess" && o.optString("security") in setOf("none", "zero")) return "not encrypted"
        if (type == "shadowsocks" && o.optString("method") in setOf("none", "plain")) return "not encrypted"
        val tls = o.optJSONObject("tls")
        if (tls?.optBoolean("insecure") == true) return "doesn't check the server's certificate"
        if ((type == "vless" || type == "trojan") && tls?.optBoolean("enabled") != true) return "not encrypted"
        return null
    }

    private fun isPrivate(host: String): Boolean {
        if (!Regex("^[0-9.]+$").matches(host) && ':' !in host) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress
    }

    private fun downloadList(urls: List<String>): List<String> {
        var last: Exception? = null
        for (url in urls) {
            try {
                val links = ShareLink.extract(Subscriptions.download(url).body)
                if (links.isNotEmpty()) return links
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("the list is empty")
    }

    /** Downloads the lists, measures every config's address on this network and keeps the fastest, spread over many networks. */
    fun find(progress: (String) -> Unit): List<Pair<String, Int>> {
        progress("download")
        val clean = downloadList(CLEAN)
        val cloudflare = runCatching { downloadList(CLOUDFLARE) }.getOrDefault(emptyList())
        val usable = clean.mapNotNull { link -> runCatching { ShareLink.parse(link) }.getOrNull()?.takeIf { refusal(it) == null }?.let { link to it } }
        progress("test")
        val pool = Executors.newFixedThreadPool(48)
        val measured = try {
            pool.invokeAll(usable.map { (link, p) -> Callable { Triple(link, p, Net.tcpDelay(p.host, p.port)) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val picked = spreadOut(measured.filter { it.third > 0 }.sortedBy { it.third }.map { Triple(it.first, it.second.host, it.third) }, KEEP_CLEAN)
        // They all share one Cloudflare address, so one measurement says how they reach it.
        val viaCloudflare = cloudflare.mapNotNull { link -> runCatching { ShareLink.parse(link) }.getOrNull()?.takeIf { refusal(it) == null }?.let { link to it } }.take(KEEP_CLOUDFLARE)
        val cloudflareDelay = viaCloudflare.firstOrNull()?.second?.let { Net.tcpDelay(it.host, it.port) } ?: DELAY_FAILED
        return picked + if (cloudflareDelay > 0) viaCloudflare.map { it.first to cloudflareDelay } else emptyList()
    }

    /** At most two per /24 (or host name), since filtering often hits a whole operator's range at once. */
    fun spreadOut(sorted: List<Triple<String, String, Int>>, max: Int): List<Pair<String, Int>> {
        val ranges = mutableMapOf<String, Int>()
        val picked = mutableListOf<Pair<String, Int>>()
        for ((link, host, delay) in sorted) {
            if (picked.size >= max) break
            val range = if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(host)) host.substringBeforeLast('.') else host
            if ((ranges[range] ?: 0) >= 2) continue
            ranges[range] = (ranges[range] ?: 0) + 1
            picked += link to delay
        }
        return picked
    }
}
