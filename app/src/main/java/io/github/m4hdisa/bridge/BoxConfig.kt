package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONObject

/** A local port only Bridge may use: the password keeps other apps on the phone out. */
data class LocalAccess(val port: Int, val user: String, val secret: String)

/**
 * What a config is built from. `servers` are already parsed; `auto` are the ids Auto picks from.
 * Servers only Xray can run are reached on `xrayPorts` (by server id) with `xrayAuth`; one without
 * a port is left out. `directIps` are those servers' addresses, kept off the VPN when Bridge's
 * own traffic is in it (the emulator test), so Xray's connections don't loop back into sing-box.
 */
data class BoxInput(
    val servers: List<Pair<String, ParsedLink>>,
    val selected: String,
    val auto: List<String>,
    val bypassIran: Boolean,
    val rulesDir: String?,
    val logPath: String?,
    val clash: LocalAccess?,
    val proxy: LocalAccess?,
    val xrayPorts: Map<String, Int> = emptyMap(),
    val xrayAuth: LocalAccess? = null,
    val directIps: List<String> = emptyList(),
    /** Free WARP's local SOCKS port (Aether, or Psiphon inside it), when it is the server in use. */
    val warpPort: Int? = null,
    /** Instant made-up answers for names (see build); off: real answers over DNS-over-HTTPS. */
    val fakeIp: Boolean = true,
    /** sing-box's cache file, which keeps the made-up answers across reconnects. */
    val cachePath: String? = null,
    /** "info", or "debug" for the detailed log. */
    val logLevel: String = "info",
)

/** The config, and each server's outbound tag in it. */
data class BuiltConfig(val json: String, val tags: Map<String, String>)

/**
 * Builds the sing-box config. Android itself decides which apps enter the VPN (see
 * BridgeVpnService), so everything that reaches sing-box goes through the server, except
 * Iranian sites when bypassIran is on.
 *
 * DNS, with fakeIp (the default, as in Full VPN on Windows): every A/AAAA question gets an
 * instant made-up address from 198.18.0.0/15, and sing-box swaps it back for the name when the
 * app connects, so the server looks the site up itself. A new site then opens without waiting
 * for a lookup through the server first. Other questions, and every one without fakeIp, go over
 * DNS-over-HTTPS through the server, so Iran's fake answers (10.10.34.x) don't reach the apps.
 * Anything still sent to 10.10.34.x (an app that cached Iran's answer) is refused at once
 * instead of hanging.
 *
 * Every server is in the config behind one selector, so switching servers while connected
 * needs no reconnect, and each can be tested through the Clash API.
 */
object BoxConfig {
    const val TEST_URL = "https://www.gstatic.com/generate_204"
    const val WARP_TAG = "warp"
    const val FAKE_RANGE = "198.18.0.0/15"

    /** Where Iran's DNS points blocked sites. */
    const val IRAN_FAKE_RANGE = "10.10.34.0/24"

    fun build(input: BoxInput): BuiltConfig {
        val tags = LinkedHashMap<String, String>()
        val outbounds = JSONArray()
        input.servers.forEachIndexed { i, (id, parsed) ->
            val outbound = if (parsed.usesXray) {
                val port = input.xrayPorts[id] ?: return@forEachIndexed
                JSONObject().put("type", "socks").put("server", "127.0.0.1").put("server_port", port).put("version", "5").also { o ->
                    input.xrayAuth?.let { o.put("username", it.user).put("password", it.secret) }
                }
            } else {
                JSONObject(parsed.outbound.toString())
            }
            val tag = "s$i"
            tags[id] = tag
            outbounds.put(outbound.put("tag", tag))
        }
        // Free WARP is never in Auto: it is only in the config when it was chosen.
        val autoCandidates = tags.values.toList()
        input.warpPort?.let { port ->
            tags[Warp.ID] = WARP_TAG
            // A saved WARP config is the same tunnel to sing-box.
            if (Warp.isWarp(input.selected)) tags[input.selected] = WARP_TAG
            outbounds.put(JSONObject().put("type", "socks").put("tag", WARP_TAG).put("server", "127.0.0.1").put("server_port", port).put("version", "5"))
        }
        require(tags.isNotEmpty()) { "no servers" }
        val autoTags = input.auto.mapNotNull { id -> tags[id]?.takeIf { it != WARP_TAG } }.ifEmpty { autoCandidates }
        val useAuto = autoCandidates.size > 1
        val choices = JSONArray()
        if (useAuto) {
            outbounds.put(
                JSONObject()
                    .put("type", "urltest")
                    .put("tag", AUTO)
                    .put("outbounds", JSONArray(autoTags))
                    .put("url", TEST_URL)
                    .put("interval", "5m")
                    .put("tolerance", 150)
                    .put("idle_timeout", "30m"),
            )
            choices.put(AUTO)
        }
        tags.values.forEach { choices.put(it) }
        val default = tags[input.selected] ?: if (useAuto) AUTO else autoCandidates.firstOrNull() ?: WARP_TAG
        outbounds.put(JSONObject().put("type", "selector").put("tag", "proxy").put("outbounds", choices).put("default", default).put("interrupt_exist_connections", true))
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val iranRules = input.bypassIran && input.rulesDir != null
        val dnsRules = JSONArray()
        val routeRules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        if (input.proxy != null) routeRules.put(JSONObject().put("inbound", JSONArray().put("bridge-in")).put("outbound", "proxy"))
        if (input.directIps.isNotEmpty()) routeRules.put(JSONObject().put("ip_cidr", JSONArray(input.directIps.map { if (':' in it) "$it/128" else "$it/32" })).put("outbound", "direct"))
        routeRules.put(JSONObject().put("ip_cidr", JSONArray().put(IRAN_FAKE_RANGE)).put("action", "reject"))
        // IPv6 comes into the VPN (so it doesn't leak), but most servers and WARP only carry IPv4:
        // refused at once, apps such as Telegram fall back to IPv4 instead of waiting.
        routeRules.put(JSONObject().put("ip_version", 6).put("action", "reject"))
        // Without FakeIP, made-up addresses apps kept from an earlier connection lead nowhere:
        // refused at once, so the app asks for the real address instead of waiting.
        if (!input.fakeIp) routeRules.put(JSONObject().put("ip_cidr", JSONArray().put(FAKE_RANGE)).put("action", "reject"))
        routeRules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (input.bypassIran) {
            dnsRules.put(JSONObject().put("domain_suffix", JSONArray().put(".ir")).put("server", "local"))
            routeRules.put(JSONObject().put("domain_suffix", JSONArray().put(".ir")).put("outbound", "direct"))
            if (iranRules) {
                dnsRules.put(JSONObject().put("rule_set", JSONArray().put("geosite-ir")).put("server", "local"))
                routeRules.put(JSONObject().put("rule_set", JSONArray().put("geosite-ir").put("geoip-ir")).put("outbound", "direct"))
            }
        }

        // After the Iranian rules, so Iranian sites still get their real addresses from the phone's DNS.
        if (input.fakeIp) dnsRules.put(JSONObject().put("query_type", JSONArray().put("A").put("AAAA")).put("server", "fake"))

        val route = JSONObject()
            .put("rules", routeRules)
            .put("final", "proxy")
            .put("default_domain_resolver", "local")
            .put("auto_detect_interface", true)
        if (iranRules) {
            route.put(
                "rule_set",
                JSONArray()
                    .put(JSONObject().put("type", "local").put("tag", "geoip-ir").put("format", "binary").put("path", "${input.rulesDir}/geoip-ir.srs"))
                    .put(JSONObject().put("type", "local").put("tag", "geosite-ir").put("format", "binary").put("path", "${input.rulesDir}/geosite-ir.srs")),
            )
        }

        val inbounds = JSONArray().put(
            JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                // IPv6 too, as on Windows: on networks with IPv6 (Hamrah-e Aval and others) the
                // phone's IPv6 traffic then comes into the VPN as well instead of having no way out.
                .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                .put("mtu", 9000)
                .put("auto_route", true)
                // gVisor for TCP too. "mixed" hands TCP to Android's own network stack, whose replies
                // leave by the app's routes; Bridge is left out of the VPN (so Xray and Aether reach
                // their servers directly), so those replies missed the VPN and no TCP connection ever
                // opened: only UDP (QUIC) got through.
                .put("stack", "gvisor"),
        )
        input.proxy?.let { p ->
            inbounds.put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", "bridge-in")
                    .put("listen", "127.0.0.1")
                    .put("listen_port", p.port)
                    .put("users", JSONArray().put(JSONObject().put("username", p.user).put("password", p.secret))),
            )
        }

        val log = JSONObject().put("level", input.logLevel).put("timestamp", true)
        input.logPath?.let { log.put("output", it) }
        val config = JSONObject()
            .put("log", log)
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(JSONObject().put("type", "https").put("tag", "remote").put("server", "1.1.1.1").put("detour", "proxy"))
                            .put(JSONObject().put("type", "local").put("tag", "local"))
                            .also { if (input.fakeIp) it.put(JSONObject().put("type", "fakeip").put("tag", "fake").put("inet4_range", FAKE_RANGE)) },
                    )
                    .put("rules", dnsRules)
                    .put("final", "remote")
                    .put("strategy", "ipv4_only")
                    // An answer just past its TTL is used at once and refreshed behind the scenes.
                    .put("optimistic", JSONObject().put("enabled", true).put("timeout", "1h")),
            )
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", route)
        val experimental = JSONObject()
        input.clash?.let { c -> experimental.put("clash_api", JSONObject().put("external_controller", "127.0.0.1:${c.port}").put("secret", c.secret)) }
        // store_fakeip keeps the made-up answers across reconnects, so apps that kept one still work.
        input.cachePath?.let { experimental.put("cache_file", JSONObject().put("enabled", true).put("path", it).put("store_fakeip", input.fakeIp)) }
        if (experimental.length() > 0) config.put("experimental", experimental)
        return BuiltConfig(config.toString(2), tags)
    }
}
