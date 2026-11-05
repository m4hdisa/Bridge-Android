package io.github.m4hdisa.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A server the user added, by hand or from a subscription. Only the link is kept; it is parsed when needed. */
data class Server(val id: String, val name: String, val link: String, val subscription: String? = null, val delay: Int? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("link", link).also { o ->
        subscription?.let { o.put("subscription", it) }
        delay?.let { o.put("delay", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Server(
            o.getString("id"),
            o.optString("name"),
            o.getString("link"),
            o.optString("subscription").ifEmpty { null },
            if (o.has("delay")) o.getInt("delay") else null,
        )

        fun fromLink(link: String, subscription: String? = null, name: String? = null) =
            Server(UUID.randomUUID().toString(), name ?: ShareLink.nameOf(link), link.trim(), subscription)
    }
}

/** A subscription URL. `public` marks the free public configs, which Auto uses only when there is nothing else. */
data class Subscription(val id: String, val url: String, val name: String, val public: Boolean = false, val updatedAt: Long = 0) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("name", name).put("public", public).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = Subscription(o.getString("id"), o.getString("url"), o.optString("name"), o.optBoolean("public"), o.optLong("updatedAt"))
    }
}

/** A measured delay that failed. */
const val DELAY_FAILED = -1

/** The choice that lets sing-box pick the fastest working server. */
const val AUTO = "auto"

/** Everything the user set. Stays on the phone; apps may not back it up (allowBackup is off). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    init {
        migrate()
    }

    @get:Synchronized @set:Synchronized
    var servers: List<Server>
        get() = readArray("servers").map { Server.fromJson(it) }
        set(value) = prefs.edit().putString("servers", JSONArray(value.map { it.toJson() }).toString()).apply()

    @get:Synchronized @set:Synchronized
    var subscriptions: List<Subscription>
        get() = readArray("subscriptions").map { Subscription.fromJson(it) }
        set(value) = prefs.edit().putString("subscriptions", JSONArray(value.map { it.toJson() }).toString()).apply()

    /** A server id, or AUTO. */
    var selected: String
        get() = prefs.getString("selected", AUTO) ?: AUTO
        set(value) = prefs.edit().putString("selected", value).apply()

    /** On: only the chosen apps use the VPN. Off: every app does. */
    var perApp: Boolean
        get() = prefs.getBoolean("perApp", false)
        set(value) = prefs.edit().putBoolean("perApp", value).apply()

    var apps: Set<String>
        get() = prefs.getStringSet("apps", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("apps", value).apply()

    /** Iranian sites and addresses skip the VPN, as on Windows. */
    var bypassIran: Boolean
        get() = prefs.getBoolean("bypassIran", true)
        set(value) = prefs.edit().putBoolean("bypassIran", value).apply()

    /** For the emulator test only: Bridge's own traffic normally skips the VPN, so it can reach servers directly. */
    var includeSelf: Boolean
        get() = prefs.getBoolean("includeSelf", false)
        set(value) = prefs.edit().putBoolean("includeSelf", value).apply()

    @Synchronized
    fun addLinks(links: List<String>, subscription: String? = null): Int {
        val known = servers.map { it.link }.toSet()
        val added = links.map { it.trim() }.filter { it !in known }.distinct().map { Server.fromLink(it, subscription) }
        servers = servers + added
        return added.size
    }

    @Synchronized
    fun replaceSubscription(subscription: Subscription, links: List<String>, names: (Int, String) -> String? = { _, _ -> null }) {
        servers = Servers.replace(servers, subscription.id, links, names)
        subscriptions = subscriptions.filter { it.id != subscription.id } + subscription
    }

    @Synchronized
    fun removeSubscription(id: String) {
        servers = servers.filter { it.subscription != id }
        subscriptions = subscriptions.filter { it.id != id }
        if (servers.none { it.id == selected }) selected = AUTO
    }

    @Synchronized
    fun removeServer(id: String) {
        servers = servers.filter { it.id != id }
        if (selected == id) selected = AUTO
    }

    @Synchronized
    fun setDelays(delays: Map<String, Int>) {
        servers = servers.map { s -> delays[s.id]?.let { s.copy(delay = it) } ?: s }
    }

    private fun readArray(key: String): List<JSONObject> {
        val text = prefs.getString(key, null) ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /** 0.1.0 kept one link. */
    private fun migrate() {
        val old = prefs.getString("link", null) ?: return
        if (old.isNotBlank() && !prefs.contains("servers")) {
            val server = Server.fromLink(old)
            prefs.edit().putString("servers", JSONArray(listOf(server.toJson())).toString()).putString("selected", server.id).apply()
        }
        prefs.edit().remove("link").apply()
    }
}

/** Server list rules that don't need Android, so unit tests can check them. */
object Servers {
    /** The subscription's new links, keeping the id (and so the selection) of links it already had. */
    fun replace(servers: List<Server>, subscription: String, links: List<String>, names: (Int, String) -> String? = { _, _ -> null }): List<Server> {
        val old = servers.filter { it.subscription == subscription }.associateBy { it.link }
        val fresh = links.map { it.trim() }.distinct().mapIndexed { i, link ->
            val name = names(i, link)
            old[link]?.let { if (name != null) it.copy(name = name) else it } ?: Server.fromLink(link, subscription, name)
        }
        val firstAt = servers.indexOfFirst { it.subscription == subscription }.takeIf { it >= 0 } ?: servers.size
        val others = servers.filter { it.subscription != subscription }
        val before = others.take(servers.take(firstAt).count { it.subscription != subscription })
        return before + fresh + others.drop(before.size)
    }

    /**
     * The servers Auto picks from: the user's own, or the public ones when there are none. The best
     * measured first, so the group stays small enough to test often.
     */
    fun autoGroup(servers: List<Server>, publicSubscriptions: Set<String>, max: Int): List<Server> {
        val own = servers.filter { it.subscription !in publicSubscriptions }
        val pool = own.ifEmpty { servers }
        return pool.sortedBy { s -> s.delay?.takeIf { it > 0 } ?: Int.MAX_VALUE }.take(max)
    }
}
