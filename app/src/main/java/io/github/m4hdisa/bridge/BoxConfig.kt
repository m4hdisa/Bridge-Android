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
)

/** The config, and each server's outbound tag in it. */
data class BuiltConfig(val json: String, val tags: Map<String, String>)

/**
 * Builds the sing-box config. Android itself decides which apps enter the VPN (see
 * BridgeVpnService), so everything that reaches sing-box goes through the server, except
 * Iranian sites when bypassIran is on. Names resolve over DNS-over-HTTPS through the server,
 * so Iran's fake answers (10.10.34.x) don't reach the apps.
 *
 * Every server is in the config behind one selector, so switching servers while connected
 * needs no reconnect, and each can be tested through the Clash API.
 */
object BoxConfig {
    const val TEST_URL = "https://www.gstatic.com/generate_204"

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
        require(tags.isNotEmpty()) { "no servers" }
        val autoTags = input.auto.mapNotNull { tags[it] }.ifEmpty { tags.values.toList() }
        val useAuto = tags.size > 1
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
        val default = tags[input.selected] ?: if (useAuto) AUTO else tags.values.first()
        outbounds.put(JSONObject().put("type", "selector").put("tag", "proxy").put("outbounds", choices).put("default", default).put("interrupt_exist_connections", true))
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val iranRules = input.bypassIran && input.rulesDir != null
        val dnsRules = JSONArray()
        val routeRules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        if (input.proxy != null) routeRules.put(JSONObject().put("inbound", JSONArray().put("bridge-in")).put("outbound", "proxy"))
        if (input.directIps.isNotEmpty()) routeRules.put(JSONObject().put("ip_cidr", JSONArray(input.directIps.map { if (':' in it) "$it/128" else "$it/32" })).put("outbound", "direct"))
        routeRules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (input.bypassIran) {
            dnsRules.put(JSONObject().put("domain_suffix", JSONArray().put(".ir")).put("server", "local"))
            routeRules.put(JSONObject().put("domain_suffix", JSONArray().put(".ir")).put("outbound", "direct"))
            if (iranRules) {
                dnsRules.put(JSONObject().put("rule_set", JSONArray().put("geosite-ir")).put("server", "local"))
                routeRules.put(JSONObject().put("rule_set", JSONArray().put("geosite-ir").put("geoip-ir")).put("outbound", "direct"))
            }
        }

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
                .put("address", JSONArray().put("172.19.0.1/30"))
                .put("mtu", 9000)
                .put("auto_route", true)
                .put("stack", "mixed"),
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

        val log = JSONObject().put("level", "info").put("timestamp", true)
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
                            .put(JSONObject().put("type", "local").put("tag", "local")),
                    )
                    .put("rules", dnsRules)
                    .put("final", "remote")
                    .put("strategy", "ipv4_only"),
            )
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", route)
        input.clash?.let { c ->
            config.put("experimental", JSONObject().put("clash_api", JSONObject().put("external_controller", "127.0.0.1:${c.port}").put("secret", c.secret)))
        }
        return BuiltConfig(config.toString(2), tags)
    }
}
