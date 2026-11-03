package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the sing-box config. Android itself decides which apps enter the VPN (see
 * BridgeVpnService), so everything that reaches sing-box goes through the server, and apps
 * left out never touch it. Their names resolve over DNS-over-HTTPS through the server too,
 * so Iran's fake answers (10.10.34.x) don't reach them.
 */
object BoxConfig {
    fun build(server: VlessServer): String {
        val outbound = JSONObject(server.outbound.toString()).put("tag", "proxy")
        return JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(JSONObject().put("type", "https").put("tag", "remote").put("server", "1.1.1.1").put("detour", "proxy"))
                            .put(JSONObject().put("type", "local").put("tag", "local")),
                    )
                    .put("final", "remote")
                    .put("strategy", "ipv4_only"),
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("address", JSONArray().put("172.19.0.1/30"))
                        .put("mtu", 9000)
                        .put("auto_route", true)
                        .put("stack", "mixed"),
                ),
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(outbound)
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .put(
                "route",
                JSONObject()
                    .put(
                        "rules",
                        JSONArray()
                            .put(JSONObject().put("action", "sniff"))
                            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct")),
                    )
                    .put("final", "proxy")
                    .put("default_domain_resolver", "local")
                    .put("auto_detect_interface", true),
            )
            .toString(2)
    }
}
