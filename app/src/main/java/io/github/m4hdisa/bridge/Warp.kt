package io.github.m4hdisa.bridge

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * The built-in free WARP server, run by the Aether core (AGPL-3.0, by CluvexStudio:
 * https://github.com/CluvexStudio/Aether), as on Windows (src/main/warp.ts there). Aether finds a
 * Cloudflare WARP gateway this network lets through and serves the tunnel as a local SOCKS5
 * proxy; sing-box reaches it as a SOCKS hop:
 *   app -> VPN -> sing-box -> socks 127.0.0.1 -> Aether -> Cloudflare WARP
 * This file has no Android in it, so unit tests can check it; WarpRunner runs Aether.
 */
object Warp {
    /** The id Free WARP is selected by; it is not in the server list. */
    const val ID = "warp"

    fun isWarp(id: String) = id == ID

    /** Aether's WARP account, kept beside --config. Kept for good: Cloudflare limits new sign-ups. */
    const val IDENTITY_FILE = "aether-masque.toml"

    /** Beside the account while it is a public starter one, until the user's own replaces it. */
    const val STARTER_MARK = "starter-account"

    const val SHARE_SCHEME = "bridge://warp"

    /** Starter accounts, made every day outside Iran by Bridge-configs: GitHub, then a CDN for networks where GitHub's file host is filtered. */
    val STARTER_LIST = listOf(
        "https://raw.githubusercontent.com/m4hdisa/Bridge-configs/main/warp.txt",
        "https://cdn.jsdelivr.net/gh/m4hdisa/Bridge-configs@main/warp.txt",
    )

    /** The countries offered for Psiphon's exit. Psiphon decides which it really has at any time. */
    val PSIPHON_REGIONS = listOf("DE", "NL", "GB", "FR", "SE", "FI", "CH", "AT", "CA", "US", "JP", "SG")

    /** Aether's Android builds need Android 10. */
    const val MIN_SDK = 29

    enum class Method(val args: List<String>, val label: String) {
        H3(listOf("--masque", "--h3", "-4"), "MASQUE over HTTP/3"),
        H2(listOf("--masque", "--h2", "-4"), "MASQUE over HTTP/2"),
        H2_FRAGMENT(listOf("--masque", "--h2", "--fragment", "-4"), "HTTP/2 + fragment"),
        H2_IPV6(listOf("--masque", "--h2", "-6"), "HTTP/2 over IPv6"),

        // Two hops: the second is opened from inside the first, so Cloudflare gives a foreign exit.
        GOOL(listOf("--gool", "--h2", "-4"), "Gool (WARP in WARP)"),
        MIM_H2(listOf("--mim", "--h2", "-4"), "MASQUE in MASQUE over HTTP/2"),
        ;

        val twoHops get() = this == GOOL || this == MIM_H2
    }

    /** What the WARP card offers. */
    enum class Choice { Auto, Foreign, Psiphon }

    private val FAST = listOf(Method.H3, Method.H2, Method.H2_FRAGMENT, Method.H2_IPV6)

    /** The methods raced at once for a choice. Psiphon is there for the exit, so a second WARP hop would only cost speed. */
    fun methods(choice: Choice): List<Method> = when (choice) {
        Choice.Foreign -> listOf(Method.GOOL, Method.MIM_H2) + FAST
        else -> FAST
    }

    class Psiphon(val bin: String, val port: Int, val region: String?)

    /** One attempt's command line. Every question Aether could ask has a flag here, so it never waits for input. */
    fun args(method: Method, port: Int, configFile: String, psiphon: Psiphon? = null, peer: String? = null): List<String> = buildList {
        addAll(method.args)
        // A gateway that worked last time: no scan. For two hops it is the outer one.
        if (peer != null) addAll(listOf(if (method == Method.MIM_H2) "--mim-outer" else "--peer", peer))
        // Stop at the first gateway that passes real traffic; with two hops "verified" keeps them in separate ranges.
        add("--scan")
        add(if (method.twoHops) "verified" else "turbo")
        add("--quick-reconnect")
        add("--bind")
        add("127.0.0.1:$port")
        add("--config")
        add(configFile)
        // Two hops are there for the foreign exit, so they refuse an Iranian one and look again.
        if (method.twoHops) addAll(listOf("--exit-loc", "!IR"))
        if (psiphon != null) {
            addAll(listOf("--psiphon", "--psiphon-bin", psiphon.bin, "--psiphon-bind", "127.0.0.1:${psiphon.port}"))
            psiphon.region?.let { addAll(listOf("--psiphon-region", it)) }
        }
    }

    /** Creates the account once, before parallel attempts would each try to make their own. `upstream` is a socks5:// URL. */
    fun registerArgs(configFile: String, extra: List<String> = emptyList(), upstream: String? = null): List<String> =
        listOf("--register", "masque") + extra + (upstream?.let { listOf("--upstream", it) } ?: emptyList()) + listOf("--config", configFile)

    private val LINE = Regex("^\\[\\S+\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+[^\\]]*\\]\\s*(.*)$")

    /** Aether prints "[time LEVEL target] message"; returns the message alone. */
    fun message(line: String): String = LINE.find(line)?.groupValues?.get(2) ?: line.trim()

    /** Aether binds its port at once but serves only when the tunnel works; this line says so. Gool prints it first for an inner port, so the port must match. */
    fun ready(text: String, port: Int): Boolean = text.contains("socks5 server listening on 127.0.0.1:$port")

    /** The exit country ("masque exit: 104.28.214.161, IR via FRA, ..."). */
    fun exitCountry(text: String): String? = Regex("\\bexit: \\S+, ([A-Z]{2}) via ").find(text)?.groupValues?.get(1)

    /** The Cloudflare gateway Aether connects to ("[+] using cloudflare edge 162.159.198.131:443"). */
    fun gateway(text: String): String? =
        Regex("using cloudflare edge (\\S+:\\d+)").find(text)?.groupValues?.get(1)
            ?: Regex("masque-in-masque ready: (\\S+:\\d+) \\(outer\\)").find(text)?.groupValues?.get(1)

    /** Only Cloudflare's WARP ranges can be a gateway, so a saved one can't point anywhere else. */
    fun isGateway(peer: String): Boolean {
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3}):(\\d{1,5})$").find(peer)
        if (v4 != null) {
            val (a, b, c, d, port) = v4.destructured.toList().map { it.toInt() }
            if (c > 255 || d > 255 || port !in 1..65535) return false
            return (a == 162 && b == 159) || (a == 188 && b == 114 && c in 96..111)
        }
        return Regex("^\\[2606:4700:[0-9a-fA-F:]+\\]:(\\d{1,5})$").find(peer)?.groupValues?.get(1)?.toInt() in 1..65535
    }

    /** Aether's line when it drops a tunnel over its exit country; after a few, two hops won't get a foreign exit here. */
    val EXIT_REFUSED = Regex("exit location [A-Z]{2} rejected")
    const val MAX_EXIT_REFUSED = 5
    val NO_IPV6 = Regex("host has no IPv6 route")

    private val IDENTITY_FIELDS = setOf(
        "device_id", "access_token", "cert_pem", "key_pem", "cert_issued_at", "ipv4", "ipv6", "wg_private_key",
        "wg_peer_public_key", "client_id", "organization", "gateway_proxy", "assigned_endpoint",
    )
    private const val MAX_IDENTITY_BYTES = 8192

    /** Throws unless `text` is a plain Aether account file: only its own fields, with its keys. */
    fun checkIdentity(text: String) {
        val keys = text.lines().mapNotNull { Regex("^([a-z0-9_]+)\\s*=").find(it)?.groupValues?.get(1) }
        keys.firstOrNull { it !in IDENTITY_FIELDS }?.let { throw IllegalArgumentException("This link's WARP account has an unknown field ($it)") }
        for (field in listOf("device_id", "access_token", "cert_pem", "key_pem")) {
            require(field in keys) { "This link's WARP account is missing $field" }
        }
    }

    /** The WARP account inside a Bridge WARP link (the starter list holds them), read without trusting it. */
    fun identityOf(link: String): String {
        val body = link.substringBefore('#')
        require(body.startsWith("$SHARE_SCHEME?")) { "Not a Bridge WARP link" }
        val account = body.removePrefix("$SHARE_SCHEME?").split('&')
            .firstOrNull { it.startsWith("a=") }?.removePrefix("a=")
            ?: throw IllegalArgumentException("This WARP link has no account")
        val identity = inflateRaw(Base64Text.decodeBytes(java.net.URLDecoder.decode(account, "UTF-8")))
        checkIdentity(identity)
        return identity
    }

    private fun inflateRaw(bytes: ByteArray): String {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                require(out.size() <= MAX_IDENTITY_BYTES) { "This WARP link is damaged" }
            }
            return out.toString("UTF-8")
        } finally {
            inflater.end()
        }
    }
}
