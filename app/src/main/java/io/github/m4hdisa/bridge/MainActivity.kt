package io.github.m4hdisa.bridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private val work = Executors.newCachedThreadPool()
    private val stateListener: () -> Unit = { render() }

    private lateinit var nav: BottomNavigationView
    private lateinit var pages: Map<Int, View>
    private lateinit var connect: MaterialButton
    private lateinit var status: TextView
    private lateinit var error: TextView
    private lateinit var serverName: TextView
    private lateinit var serverDetail: TextView
    private lateinit var testResult: TextView
    private lateinit var appsSummary: TextView
    private lateinit var iranSummary: TextView
    private lateinit var busy: View
    private lateinit var busyText: TextView
    private lateinit var serversEmpty: View
    private lateinit var serverList: RecyclerView
    private lateinit var perApp: MaterialSwitch
    private lateinit var appsHint: TextView
    private lateinit var searchBox: View
    private lateinit var appList: RecyclerView
    private lateinit var bypassIran: MaterialSwitch
    private lateinit var languageValue: TextView

    private val serverAdapter = ServerAdapter()
    private val appAdapter = AppAdapter()
    private var apps: List<AppEntry> = emptyList()
    private var appFilter = ""
    private val parsed = HashMap<String, Result<ParsedLink>>()
    private var busyCount = 0
    private var localError: String? = null

    /** Set by "Reconnect": start again as soon as the VPN has stopped. */
    private var pendingStart = false

    /** What Auto is using now, from the Clash API. */
    private var currentTag: String? = null

    private data class AppEntry(val label: String, val packageName: String, val icon: Drawable?)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn() else showError(getString(R.string.vpn_permission_denied))
    }

    // The app works without it; only the "connected" notification stays hidden.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        nav = findViewById(R.id.nav)
        pages = mapOf(
            R.id.nav_home to findViewById(R.id.page_home),
            R.id.nav_servers to findViewById(R.id.page_servers),
            R.id.nav_apps to findViewById(R.id.page_apps),
            R.id.nav_settings to findViewById(R.id.page_settings),
        )
        connect = findViewById(R.id.connect)
        status = findViewById(R.id.status)
        error = findViewById(R.id.error)
        serverName = findViewById(R.id.server_name)
        serverDetail = findViewById(R.id.server_detail)
        testResult = findViewById(R.id.test_result)
        appsSummary = findViewById(R.id.apps_summary)
        iranSummary = findViewById(R.id.iran_summary)
        busy = findViewById(R.id.busy)
        busyText = findViewById(R.id.busy_text)
        serversEmpty = findViewById(R.id.servers_empty)
        serverList = findViewById(R.id.servers)
        perApp = findViewById(R.id.per_app)
        appsHint = findViewById(R.id.apps_hint)
        searchBox = findViewById(R.id.search_box)
        appList = findViewById(R.id.apps)
        bypassIran = findViewById(R.id.bypass_iran)
        languageValue = findViewById(R.id.language_value)

        nav.setOnItemSelectedListener { item ->
            showViews(item.itemId)
            true
        }
        connect.setOnClickListener { onConnectClicked() }
        findViewById<View>(R.id.server_card).setOnClickListener { nav.selectedItemId = R.id.nav_servers }
        findViewById<View>(R.id.apps_card).setOnClickListener { nav.selectedItemId = R.id.nav_apps }
        findViewById<View>(R.id.test).setOnClickListener { testConnection() }
        findViewById<View>(R.id.ping_all).setOnClickListener { measureAll() }
        findViewById<View>(R.id.update_subs).setOnClickListener { updateSubscriptions(onlyStale = false) }
        findViewById<View>(R.id.add_server).setOnClickListener { showAddMenu(it) }
        findViewById<View>(R.id.empty_paste).setOnClickListener { pasteFromClipboard() }
        findViewById<View>(R.id.empty_public).setOnClickListener { askPublic() }

        serverList.layoutManager = LinearLayoutManager(this)
        serverList.adapter = serverAdapter
        appList.layoutManager = LinearLayoutManager(this)
        appList.adapter = appAdapter

        perApp.isChecked = store.perApp
        perApp.setOnCheckedChangeListener { _, checked ->
            store.perApp = checked
            renderApps()
            renderHome()
            noteReconnect()
        }
        findViewById<TextView>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                appFilter = s?.toString()?.trim() ?: ""
                appAdapter.refresh()
            }
        })
        bypassIran.isChecked = store.bypassIran
        bypassIran.setOnCheckedChangeListener { _, checked ->
            store.bypassIran = checked
            renderHome()
            noteReconnect()
        }
        findViewById<View>(R.id.language_row).setOnClickListener { chooseLanguage() }
        findViewById<View>(R.id.log_row).setOnClickListener { showLog() }
        findViewById<TextView>(R.id.version).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        languageValue.text = languageLabel(AppCompatDelegate.getApplicationLocales().toLanguageTags())

        showPage(savedInstanceState?.getInt(PAGE, R.id.nav_home) ?: R.id.nav_home)
        loadApps()
        renderApps()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIncoming(intent)
        updateSubscriptions(onlyStale = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(PAGE, nav.selectedItemId)
    }

    override fun onStart() {
        super.onStart()
        VpnState.addListener(stateListener)
        render()
    }

    override fun onStop() {
        VpnState.removeListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        work.shutdownNow()
        super.onDestroy()
    }

    /** Selecting a tab calls the listener, which shows the page. */
    private fun showPage(id: Int) {
        if (nav.selectedItemId != id) nav.selectedItemId = id else showViews(id)
    }

    private fun showViews(id: Int) {
        pages.forEach { (page, view) -> view.visibility = if (page == id) View.VISIBLE else View.GONE }
    }

    /** Links shared from another app, or opened from a browser. */
    private fun handleIncoming(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        showPage(R.id.nav_servers)
        addText(text)
    }

    // ---------- connecting ----------

    private fun onConnectClicked() {
        if (VpnState.status != VpnState.Status.Stopped) {
            startService(Intent(this, BridgeVpnService::class.java).setAction(BridgeVpnService.ACTION_STOP))
            return
        }
        if (store.servers.isEmpty()) {
            showError(getString(R.string.no_servers))
            showPage(R.id.nav_servers)
            return
        }
        if (store.perApp && store.apps.isEmpty()) {
            showError(getString(R.string.choose_apps))
            return
        }
        val request = VpnService.prepare(this)
        if (request != null) vpnPermission.launch(request) else startVpn()
    }

    private fun startVpn() {
        localError = null
        currentTag = null
        ContextCompat.startForegroundService(this, Intent(this, BridgeVpnService::class.java))
    }

    private fun reconnect() {
        if (VpnState.status == VpnState.Status.Stopped) {
            onConnectClicked()
            return
        }
        pendingStart = true
        startService(Intent(this, BridgeVpnService::class.java).setAction(BridgeVpnService.ACTION_STOP))
    }

    private fun noteReconnect() {
        if (VpnState.status != VpnState.Status.Running) return
        snackbar(getString(R.string.applies_next)).setAction(R.string.reconnect) { reconnect() }.show()
    }

    private fun showError(message: String) {
        localError = message
        renderHome()
    }

    // ---------- drawing ----------

    private fun render() {
        if (isDestroyed) return
        val state = VpnState.status
        if (state == VpnState.Status.Stopped && pendingStart) {
            pendingStart = false
            onConnectClicked()
        }
        if (state == VpnState.Status.Running && store.selected == AUTO) refreshCurrent()
        if (state != VpnState.Status.Running) currentTag = null
        renderHome()
        refreshServers()
    }

    private fun renderHome() {
        val state = VpnState.status
        status.setText(
            when (state) {
                VpnState.Status.Stopped -> R.string.status_off
                VpnState.Status.Starting -> R.string.status_starting
                VpnState.Status.Running -> R.string.status_on
                VpnState.Status.Stopping -> R.string.status_stopping
            },
        )
        val message = VpnState.error?.let { getString(R.string.error_prefix, it) } ?: localError
        error.text = message
        error.visibility = if (message == null) View.GONE else View.VISIBLE
        val on = state == VpnState.Status.Running
        val busyState = state == VpnState.Status.Starting || state == VpnState.Status.Stopping
        val fill = when {
            on -> MaterialColors.getColor(connect, androidx.appcompat.R.attr.colorPrimary)
            busyState -> MaterialColors.getColor(connect, com.google.android.material.R.attr.colorPrimaryContainer)
            else -> MaterialColors.getColor(connect, com.google.android.material.R.attr.colorSecondaryContainer)
        }
        val ink = when {
            on -> MaterialColors.getColor(connect, com.google.android.material.R.attr.colorOnPrimary)
            busyState -> MaterialColors.getColor(connect, com.google.android.material.R.attr.colorOnPrimaryContainer)
            else -> MaterialColors.getColor(connect, com.google.android.material.R.attr.colorOnSecondaryContainer)
        }
        connect.backgroundTintList = ColorStateList.valueOf(fill)
        connect.iconTint = ColorStateList.valueOf(ink)
        connect.isEnabled = state != VpnState.Status.Stopping
        connect.contentDescription = getString(if (state == VpnState.Status.Stopped) R.string.connect else R.string.disconnect)

        val (name, detail) = selectedLabel()
        serverName.text = name
        serverDetail.text = detail
        appsSummary.text = if (store.perApp) getString(R.string.apps_some, store.apps.size) else getString(R.string.apps_all)
        iranSummary.visibility = if (store.bypassIran) View.VISIBLE else View.GONE
    }

    private fun selectedLabel(): Pair<String, String> {
        val servers = store.servers
        if (servers.isEmpty()) return getString(R.string.no_server) to getString(R.string.add_server_hint)
        val server = servers.firstOrNull { it.id == store.selected }
        if (server != null || servers.size == 1) {
            val s = server ?: servers.first()
            return s.name to describe(s)
        }
        val now = currentTag?.let { VpnState.session?.idOf(it) }?.let { id -> servers.firstOrNull { it.id == id } }
        return getString(R.string.auto_name) to (now?.let { getString(R.string.auto_now, it.name) } ?: "")
    }

    private fun parse(server: Server): Result<ParsedLink> = parsed.getOrPut(server.link) { runCatching { ShareLink.parse(server.link) } }

    private fun describe(server: Server): String {
        val p = parse(server)
        val kind = p.getOrNull()?.kind ?: getString(R.string.unusable, p.exceptionOrNull()?.message ?: "")
        return listOfNotNull(kind, delayText(server.delay)).joinToString(" · ")
    }

    private fun delayText(delay: Int?): String? = when {
        delay == null -> null
        delay <= 0 -> getString(R.string.delay_failed)
        else -> getString(R.string.delay_ms, delay)
    }

    private fun delayColor(delay: Int?): Int = ContextCompat.getColor(
        this,
        when {
            delay == null -> android.R.color.transparent
            delay <= 0 || delay >= 800 -> R.color.bad
            delay >= 300 -> R.color.fair
            else -> R.color.good
        },
    )

    private fun refreshServers() {
        val empty = store.servers.isEmpty() && store.subscriptions.isEmpty()
        serversEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        serverAdapter.refresh()
    }

    private fun refreshCurrent() {
        val session = VpnState.session ?: return
        work.execute {
            val tag = Clash.current(session.clash)
            runOnUiThread {
                if (!isDestroyed && VpnState.session === session) {
                    currentTag = tag
                    renderHome()
                    serverAdapter.refresh()
                }
            }
        }
    }

    private fun setBusy(text: Int?) {
        if (text != null) {
            busyCount++
            busyText.setText(text)
        } else {
            busyCount = (busyCount - 1).coerceAtLeast(0)
        }
        busy.visibility = if (busyCount > 0) View.VISIBLE else View.GONE
    }

    private fun snackbar(text: String): Snackbar = Snackbar.make(findViewById(R.id.root), text, Snackbar.LENGTH_LONG).setAnchorView(nav)

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    // ---------- servers ----------

    private fun select(id: String) {
        store.selected = id
        val session = VpnState.session
        if (session != null && VpnState.status == VpnState.Status.Running) {
            val tag = if (id == AUTO) AUTO.takeIf { session.tags.size > 1 } else session.tags[id]
            if (tag == null) {
                snackbar(getString(R.string.reconnect_needed)).setAction(R.string.reconnect) { reconnect() }.show()
            } else {
                work.execute {
                    Clash.select(session.clash, tag)
                    ui { refreshCurrent() }
                }
            }
        }
        renderHome()
        serverAdapter.refresh()
    }

    private fun showAddMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.menu_paste)
            menu.add(0, 2, 1, R.string.menu_enter)
            menu.add(0, 3, 2, R.string.menu_public)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> pasteFromClipboard()
                    2 -> enterLink()
                    else -> askPublic()
                }
                true
            }
        }.show()
    }

    private fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            snackbar(getString(R.string.clipboard_empty)).show()
            return
        }
        addText(text)
    }

    private fun enterLink() {
        val view = layoutInflater.inflate(R.layout.dialog_text, null)
        val input = view.findViewById<TextView>(R.id.dialog_input)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_title)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _ -> addText(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A subscription address, or text with share links in it. */
    private fun addText(text: String) {
        val trimmed = text.trim()
        if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(trimmed)) {
            addSubscription(trimmed)
            return
        }
        val added = store.addLinks(ShareLink.extract(trimmed))
        snackbar(if (added > 0) getString(R.string.added_servers, added) else getString(R.string.added_none)).show()
        refreshServers()
        renderHome()
    }

    private fun addSubscription(url: String) {
        val existing = store.subscriptions.firstOrNull { it.url == url }
        val subscription = existing ?: Subscription(UUID.randomUUID().toString(), url, Uri.parse(url).host ?: url)
        setBusy(R.string.updating)
        work.execute {
            val result = runCatching { Subscriptions.fetch(url) }
            ui {
                setBusy(null)
                result.onSuccess { fetched ->
                    store.replaceSubscription(subscription.copy(name = fetched.title ?: subscription.name, updatedAt = System.currentTimeMillis()), fetched.links)
                    snackbar(getString(R.string.sub_added, fetched.links.size)).show()
                }.onFailure { e ->
                    snackbar(getString(R.string.sub_failed, subscription.name, e.message ?: e.toString())).show()
                }
                refreshServers()
                renderHome()
            }
        }
    }

    /** Every subscription, or (when the app opens) those older than a day. The public list is found again too. */
    private fun updateSubscriptions(onlyStale: Boolean) {
        val now = System.currentTimeMillis()
        val due = store.subscriptions.filter { !onlyStale || now - it.updatedAt > DateUtils.DAY_IN_MILLIS }
        if (due.isEmpty()) {
            if (!onlyStale) snackbar(getString(R.string.subs_updated)).show()
            return
        }
        due.firstOrNull { it.public }?.let { findPublic() }
        val own = due.filter { !it.public }
        if (own.isEmpty()) return
        setBusy(R.string.updating)
        work.execute {
            val failures = mutableListOf<String>()
            for (subscription in own) {
                runCatching { Subscriptions.fetch(subscription.url) }
                    .onSuccess { store.replaceSubscription(subscription.copy(name = it.title ?: subscription.name, updatedAt = System.currentTimeMillis()), it.links) }
                    .onFailure { failures += getString(R.string.sub_failed, subscription.name, it.message ?: it.toString()) }
            }
            ui {
                setBusy(null)
                if (failures.isNotEmpty()) snackbar(failures.first()).show() else if (!onlyStale) snackbar(getString(R.string.subs_updated)).show()
                refreshServers()
                renderHome()
            }
        }
    }

    private fun askPublic() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.public_title)
            .setMessage(getString(R.string.public_warning) + "\n\n" + getString(R.string.public_how))
            .setPositiveButton(R.string.public_confirm) { _, _ -> findPublic() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun findPublic() {
        setBusy(R.string.public_downloading)
        work.execute {
            val result = runCatching {
                PublicConfigs.find { step -> ui { busyText.setText(if (step == "test") R.string.public_testing else R.string.public_downloading) } }
            }
            ui {
                setBusy(null)
                result.onSuccess { found ->
                    if (found.isEmpty()) {
                        snackbar(getString(R.string.public_none)).show()
                    } else {
                        val subscription = Subscription(PublicConfigs.SUBSCRIPTION_ID, "", getString(R.string.public_sub_name), true, System.currentTimeMillis())
                        store.replaceSubscription(subscription, found.map { it.first }) { i, _ -> getString(R.string.public_name, i + 1) }
                        val delays = found.toMap()
                        store.setDelays(store.servers.filter { it.subscription == subscription.id }.mapNotNull { s -> delays[s.link]?.let { s.id to it } }.toMap())
                        snackbar(getString(R.string.public_done, found.size)).show()
                    }
                }.onFailure { e ->
                    snackbar(getString(R.string.public_failed, e.message ?: e.toString())).show()
                }
                refreshServers()
                renderHome()
            }
        }
    }

    /** Through the server when connected (a real request), otherwise how fast each address answers. */
    private fun measureAll() {
        val servers = store.servers
        if (servers.isEmpty()) return
        val session = VpnState.session?.takeIf { VpnState.status == VpnState.Status.Running }
        setBusy(R.string.pinging)
        work.execute {
            val results = ConcurrentHashMap<String, Int>()
            val pool = Executors.newFixedThreadPool(if (session != null) 8 else 32)
            try {
                pool.invokeAll(
                    servers.map { s ->
                        Callable {
                            val tag = session?.tags?.get(s.id)
                            val p = parse(s).getOrNull()
                            val delay = when {
                                session != null && tag != null -> Clash.delay(session.clash, tag)
                                p == null -> DELAY_FAILED
                                // Hysteria2 and TUIC run over UDP, so a TCP connection says nothing about them.
                                p.outbound.optString("type") in setOf("hysteria2", "tuic") -> null
                                else -> Net.tcpDelay(p.host, p.port)
                            }
                            if (delay != null) results[s.id] = delay
                            Unit
                        }
                    },
                )
            } finally {
                pool.shutdownNow()
            }
            store.setDelays(results)
            ui {
                setBusy(null)
                refreshServers()
                renderHome()
            }
        }
    }

    private fun serverMenu(anchor: View, server: Server) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.server_copy)
            menu.add(0, 2, 1, R.string.remove)
            setOnMenuItemClickListener {
                if (it.itemId == 1) copy(server.link) else {
                    store.removeServer(server.id)
                    refreshServers()
                    renderHome()
                }
                true
            }
        }.show()
    }

    private fun subscriptionMenu(anchor: View, subscription: Subscription) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.sub_update)
            if (!subscription.public) menu.add(0, 2, 1, R.string.sub_copy)
            menu.add(0, 3, 2, R.string.remove)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> if (subscription.public) findPublic() else addSubscription(subscription.url)
                    2 -> copy(subscription.url)
                    else -> {
                        store.removeSubscription(subscription.id)
                        refreshServers()
                        renderHome()
                    }
                }
                true
            }
        }.show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bridge", text))
        // Android 13 and up show their own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ---------- home ----------

    private fun testConnection() {
        val session = VpnState.session?.takeIf { VpnState.status == VpnState.Status.Running }
        if (session == null) {
            testResult.setText(R.string.test_needs_connection)
            return
        }
        testResult.setText(R.string.testing)
        work.execute {
            val delay = Clash.delay(session.clash, "proxy", 8_000)
            val trace = runCatching { Net.through(session.proxy, TRACE_URL, 10_000).body }.getOrNull()
            val fields = trace?.lines()?.associate { it.substringBefore('=') to it.substringAfter('=', "") } ?: emptyMap()
            val exit = listOfNotNull(fields["ip"], fields["loc"]?.let { "${flag(it)} $it".trim() }).joinToString(" ")
            ui {
                testResult.text = when {
                    delay > 0 -> getString(R.string.test_result, delay, exit.ifEmpty { "-" })
                    exit.isNotEmpty() -> exit
                    else -> getString(R.string.test_failed)
                }
                refreshCurrent()
            }
        }
    }

    private fun flag(country: String): String {
        if (country.length != 2 || !country.all { it in 'A'..'Z' }) return ""
        return country.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    // ---------- apps ----------

    private fun renderApps() {
        val on = store.perApp
        perApp.isChecked = on
        appsHint.text = if (on) getString(R.string.apps_chosen, store.apps.size) else getString(R.string.per_app_off_hint)
        searchBox.visibility = if (on) View.VISIBLE else View.GONE
        appList.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun loadApps() {
        work.execute {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = packageManager.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, runCatching { it.loadIcon(packageManager) }.getOrNull()) }
                .sortedBy { it.label.lowercase() }
            ui {
                apps = found
                appAdapter.refresh()
            }
        }
    }

    // ---------- settings ----------

    private fun languageLabel(tag: String): String = when {
        tag.startsWith("fa") -> getString(R.string.language_fa)
        tag.startsWith("en") -> getString(R.string.language_en)
        else -> getString(R.string.language_system)
    }

    private fun chooseLanguage() {
        val tags = listOf("", "fa", "en")
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val checked = tags.indexOfFirst { it.isNotEmpty() && current.startsWith(it) }.takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(tags.map { languageLabel(it) }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                AppCompatDelegate.setApplicationLocales(if (tags[which].isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tags[which]))
            }
            .show()
    }

    private fun showLog() {
        val file = File(filesDir, BridgeVpnService.LOG_FILE)
        val text = readTail(file).ifEmpty { getString(R.string.log_empty) }
        val view = layoutInflater.inflate(R.layout.dialog_log, null)
        view.findViewById<TextView>(R.id.log_text).text = text
        val scroll = view.findViewById<ScrollView>(R.id.log_scroll)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy) { _, _ -> copy(text) }
            .show()
    }

    private fun readTail(file: File, max: Long = 64 * 1024): String {
        if (!file.exists()) return ""
        return RandomAccessFile(file, "r").use { f ->
            val start = (f.length() - max).coerceAtLeast(0)
            f.seek(start)
            val bytes = ByteArray((f.length() - start).toInt())
            f.readFully(bytes)
            String(bytes, Charsets.UTF_8).let { if (start > 0) it.substringAfter('\n') else it }
        }
    }

    // ---------- lists ----------

    private sealed class Row {
        class Header(val text: String) : Row()
        class Sub(val subscription: Subscription, val count: Int) : Row()
        object Auto : Row()
        class Item(val server: Server) : Row()
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class ServerAdapter : RecyclerView.Adapter<RowHolder>() {
        private var rows: List<Row> = emptyList()

        fun refresh() {
            val servers = store.servers
            val subscriptions = store.subscriptions
            val list = mutableListOf<Row>()
            if (subscriptions.isNotEmpty()) {
                list += Row.Header(getString(R.string.subscriptions_header))
                subscriptions.forEach { s -> list += Row.Sub(s, servers.count { it.subscription == s.id }) }
            }
            if (servers.isNotEmpty()) {
                list += Row.Header(getString(R.string.servers_header))
                if (servers.size > 1) list += Row.Auto
                servers.forEach { list += Row.Item(it) }
            }
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val layout = if (viewType == 0) R.layout.item_header else R.layout.item_row
            return RowHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val row = rows[position]
            val view = holder.itemView
            if (row is Row.Header) {
                (view as TextView).text = row.text
                return
            }
            val check = view.findViewById<ImageView>(R.id.row_check)
            val title = view.findViewById<TextView>(R.id.row_title)
            val subtitle = view.findViewById<TextView>(R.id.row_subtitle)
            val trailing = view.findViewById<TextView>(R.id.row_trailing)
            view.alpha = 1f
            trailing.text = ""
            view.setOnLongClickListener(null)
            val selected = store.selected
            val selectedExists = store.servers.any { it.id == selected }
            when (row) {
                is Row.Sub -> {
                    check.visibility = View.INVISIBLE
                    title.text = row.subscription.name
                    val updated = if (row.subscription.updatedAt > 0) DateUtils.getRelativeTimeSpanString(row.subscription.updatedAt).toString() else getString(R.string.sub_never)
                    subtitle.text = getString(R.string.sub_subtitle, row.count, updated)
                    view.setOnClickListener { subscriptionMenu(it, row.subscription) }
                }
                Row.Auto -> {
                    check.visibility = if (!selectedExists) View.VISIBLE else View.INVISIBLE
                    title.text = getString(R.string.auto_name)
                    val now = currentTag?.let { VpnState.session?.idOf(it) }?.let { id -> store.servers.firstOrNull { it.id == id } }
                    subtitle.text = if (!selectedExists && now != null) getString(R.string.auto_now, now.name) else ""
                    view.setOnClickListener { select(AUTO) }
                }
                is Row.Item -> {
                    val s = row.server
                    val p = parse(s)
                    check.visibility = if (s.id == selected) View.VISIBLE else View.INVISIBLE
                    title.text = s.name
                    val owner = s.subscription?.let { id -> store.subscriptions.firstOrNull { it.id == id }?.name }
                    val kind = p.getOrNull()?.kind ?: getString(R.string.unusable, p.exceptionOrNull()?.message ?: "")
                    subtitle.text = listOfNotNull(kind, owner).joinToString(" · ")
                    trailing.text = delayText(s.delay) ?: ""
                    trailing.setTextColor(delayColor(s.delay))
                    view.alpha = if (p.isSuccess) 1f else 0.55f
                    view.setOnClickListener { if (p.isSuccess) select(s.id) }
                    view.setOnLongClickListener {
                        serverMenu(it, s)
                        true
                    }
                }
                else -> Unit
            }
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<RowHolder>() {
        private var shown: List<AppEntry> = emptyList()

        fun refresh() {
            val chosen = store.apps
            val filter = appFilter.lowercase()
            shown = apps
                .filter { filter.isEmpty() || it.label.lowercase().contains(filter) || it.packageName.contains(filter) }
                .sortedBy { if (it.packageName in chosen) 0 else 1 }
            notifyDataSetChanged()
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val app = shown[position]
            val view = holder.itemView
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.app_label).text = app.label
            view.findViewById<TextView>(R.id.app_package).text = app.packageName
            val check = view.findViewById<MaterialCheckBox>(R.id.app_check)
            check.isChecked = app.packageName in store.apps
            view.setOnClickListener {
                val chosen = store.apps
                store.apps = if (app.packageName in chosen) chosen - app.packageName else chosen + app.packageName
                check.isChecked = app.packageName in store.apps
                renderApps()
                renderHome()
                noteReconnect()
            }
        }
    }

    companion object {
        private const val PAGE = "page"
        private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    }
}
