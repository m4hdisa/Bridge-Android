package io.github.m4hdisa.bridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m4hdisa.bridge.AUTO
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.R
import io.github.m4hdisa.bridge.Server
import io.github.m4hdisa.bridge.Subscription
import io.github.m4hdisa.bridge.Warp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAB_ALL = "all"
private const val TAB_MANUAL = "manual"

/** What a bottom sheet of this page is about. */
private sealed interface Sheet {
    data class Config(val server: Server) : Sheet
    data class Source(val subscription: Subscription?, val servers: List<Server>) : Sheet
    data object WarpOptions : Sheet
}

/**
 * The Servers page: Free WARP and Auto on top, then one source at a time behind tabs that stay in
 * view, its usage in one card and its servers fastest first. Choosing one goes back to the
 * dashboard; everything else about a server or a source is in a sheet behind "more".
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ServersPage(model: AppModel, onAdd: () -> Unit, onPublic: () -> Unit, onPicked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers = model.servers
    val subscriptions = model.subscriptions
    val manual = servers.filter { s -> s.subscription == null || subscriptions.none { it.id == s.subscription } }
    fun inTab(id: String) = when (id) {
        TAB_ALL -> servers
        TAB_MANUAL -> manual
        else -> servers.filter { it.subscription == id }
    }
    var tab by remember {
        val chosen = servers.firstOrNull { it.id == model.selected }
        mutableStateOf(chosen?.let { s -> if (subscriptions.any { it.id == s.subscription }) s.subscription else TAB_MANUAL } ?: TAB_ALL)
    }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var qr by remember { mutableStateOf<Server?>(null) }
    var showUnusable by remember(tab) { mutableStateOf(false) }
    val tabs = buildList {
        add(TAB_ALL)
        addAll(subscriptions.filter { s -> servers.any { it.subscription == s.id } || s.id in model.updating }.map { it.id })
        if (manual.isNotEmpty()) add(TAB_MANUAL)
    }
    if (tab !in tabs) tab = TAB_ALL
    val subscription = subscriptions.firstOrNull { it.id == tab }
    fun pick(id: String) {
        model.select(id)
        // A moment to see the tick, then back to the connect key.
        scope.launch {
            delay(220)
            onPicked()
        }
    }
    fun label(id: String): String? = when (id) {
        TAB_ALL, TAB_MANUAL -> null
        else -> subscriptions.firstOrNull { it.id == id }?.let { if (it.public) context.getString(R.string.public_short) else it.name }
    }
    fun sourceName(s: Server): String = subscriptions.firstOrNull { it.id == s.subscription }?.let { if (it.public) context.getString(R.string.public_short) else it.name } ?: context.getString(R.string.manual_short)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (searching) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, stringResource(R.string.search_configs), Modifier.weight(1f), focus = true)
                HeaderAction(Icons.X, stringResource(R.string.close), {
                    searching = false
                    query = ""
                })
            }
        } else {
            AppHeader(stringResource(R.string.nav_servers), { HeaderTile(Icons.GlobeHemisphereWestFill) }) {
                HeaderAction(Icons.Search, stringResource(R.string.search_configs), { searching = true }, enabled = servers.isNotEmpty())
                HeaderAction(Icons.Zap, stringResource(R.string.test_all), { model.measure() }, enabled = servers.isNotEmpty())
                HeaderAction(Icons.Plus, stringResource(R.string.add_servers), onAdd)
            }
        }
        Spacer(Modifier.height(10.dp))
        val usable = servers.filter { model.parse(it).isSuccess }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (searching) {
                val needle = query.trim().lowercase()
                val hits = usable.filter { s -> needle.isEmpty() || "${s.name} ${s.country.orEmpty()} ${model.parse(s).getOrNull()?.kind.orEmpty()}".lowercase().contains(needle) }
                if (hits.isEmpty()) {
                    item { NothingFound(stringResource(R.string.no_configs_match), stringResource(R.string.no_configs_match_hint)) }
                } else {
                    items(fastestFirst(hits), key = { it.id }) { s ->
                        ServerRow(model, s, sourceName(s), { pick(s.id) }, { sheet = Sheet.Config(s) })
                    }
                }
                return@LazyColumn
            }
            item(key = "smart") { SmartTiles(model, usable.size, { pick(it) }, { sheet = Sheet.WarpOptions }) }
            if (servers.isEmpty() && subscriptions.isEmpty()) {
                item(key = "empty") { EmptyServers(model, onAdd, onPublic) }
                return@LazyColumn
            }
            stickyHeader(key = "tabs") {
                Row(Modifier.fillMaxWidth().background(Bridge.background).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (id in tabs) {
                        val name = when (id) {
                            TAB_ALL -> stringResource(R.string.filter_all)
                            TAB_MANUAL -> stringResource(R.string.manual_short)
                            else -> label(id).orEmpty()
                        }
                        ChipTab(name, inTab(id).count { model.parse(it).isSuccess }, tab == id, { tab = id })
                    }
                }
            }
            if (tab != TAB_ALL) {
                item(key = "panel") {
                    SourcePanel(model, subscription, inTab(tab)) { sheet = Sheet.Source(subscription, inTab(tab)) }
                    Spacer(Modifier.height(8.dp))
                }
            }
            val here = inTab(tab)
            val good = here.filter { model.parse(it).isSuccess }
            if (subscription != null && subscription.id in model.updating) {
                items(good.size.coerceAtLeast(3)) { LoadingRow() }
            } else {
                items(fastestFirst(good), key = { it.id }) { s ->
                    ServerRow(model, s, if (tab == TAB_ALL) sourceName(s) else null, { pick(s.id) }, { sheet = Sheet.Config(s) })
                }
            }
            val bad = here.filter { model.parse(it).isFailure }
            if (bad.isNotEmpty()) {
                item(key = "unusable") {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(ItemShape).clickable { showUnusable = !showUnusable }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.ChevronDown, null, Modifier.size(16.dp), tint = Bridge.mutedForeground)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.unsupported_count, bad.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (showUnusable) {
                    items(bad, key = { it.id }) { s ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.unusable, model.parse(s).exceptionOrNull()?.message ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    qr?.let { server -> QrDialog(server.link, server.name) { qr = null } }
    when (val open = sheet) {
        is Sheet.Config -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Bridge.popover) {
            ConfigSheet(model, open.server, sourceName(open.server), onQr = {
                sheet = null
                qr = open.server
            }, onDone = { sheet = null })
        }
        is Sheet.Source -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Bridge.popover) {
            SourceSheet(model, open.subscription, open.servers) { sheet = null }
        }
        Sheet.WarpOptions -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Bridge.popover) {
            WarpSheet(model)
        }
        null -> Unit
    }
}

/** Measured ones first, quickest on top; then those not measured; then those that didn't answer. */
private fun fastestFirst(servers: List<Server>): List<Server> = servers.sortedBy { s ->
    val delay = s.delay
    when {
        delay == null -> Int.MAX_VALUE - 1
        delay <= 0 -> Int.MAX_VALUE
        else -> delay
    }
}

@Composable
private fun SmartTiles(model: AppModel, usable: Int, onPick: (String) -> Unit, onWarpOptions: () -> Unit) {
    val warp = model.warpAvailable
    val auto = usable > 1
    if (!warp && !auto) return
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (warp) {
            SmartTile(
                model.warpSelected,
                { Image(painterResource(R.drawable.ic_cloudflare), null, Modifier.size(width = 24.dp, height = 14.dp)) },
                stringResource(R.string.warp_name),
                stringResource(warpLabel(model.warpChoice)),
                { onPick(Warp.ID) },
                onWarpOptions,
                // It also holds the "more" button, so it gets the extra room.
                Modifier.weight(if (auto) 1.25f else 1f),
            )
        }
        if (auto) {
            SmartTile(
                model.autoSelected,
                { Icon(Icons.Zap, null, Modifier.size(20.dp), tint = Bridge.brand) },
                stringResource(R.string.auto_name),
                stringResource(R.string.auto_tile, usable),
                { onPick(AUTO) },
                null,
                Modifier.weight(1f),
            )
        }
    }
}

private fun warpLabel(choice: Warp.Choice) = when (choice) {
    Warp.Choice.Auto -> R.string.warp_auto
    Warp.Choice.Foreign -> R.string.warp_foreign
    Warp.Choice.Psiphon -> R.string.warp_psiphon
}

@Composable
private fun SmartTile(on: Boolean, lead: @Composable () -> Unit, title: String, line: String, onClick: () -> Unit, onMore: (() -> Unit)?, modifier: Modifier) {
    Row(
        modifier
            .heightIn(min = 64.dp)
            .clip(CardShape)
            .background(if (on) Bridge.primary.copy(alpha = 0.16f) else Bridge.card)
            .border(1.dp, if (on) Bridge.brand.copy(alpha = 0.55f) else Bridge.border, CardShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(onClick = onClick).padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { lead() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onMore != null) IconAction(Icons.Ellipsis, stringResource(R.string.more), onMore)
    }
}

/** The tab's own source: how much is left, how it is doing, and what can be done with it. */
@Composable
private fun SourcePanel(model: AppModel, subscription: Subscription?, servers: List<Server>, onMore: () -> Unit) {
    val context = LocalContext.current
    val usage = subscription?.usage
    BridgeCard(modifier = Modifier.padding(top = 2.dp), padding = PaddingValues(14.dp)) {
        if (usage != null) {
            val left = usage.left()
            val days = usage.daysLeft()
            val low = usage.total > 0 && usage.used.toFloat() / usage.total >= 0.9f
            Row(verticalAlignment = Alignment.Bottom) {
                if (left != null) {
                    Text(Formatter.formatShortFileSize(context, left), style = BigNumber, color = if (low) Bridge.warning else Bridge.foreground)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (usage.total > 0) stringResource(R.string.left_of, Formatter.formatShortFileSize(context, usage.total)) else stringResource(R.string.left_only),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                } else {
                    Text(stringResource(R.string.stat_unlimited), style = BigNumber)
                }
                Spacer(Modifier.weight(1f))
                if (days != null) {
                    Text(if (days <= 0) stringResource(R.string.stat_ended) else days.toString(), style = BigNumber, color = if (days <= 3) Bridge.warning else Bridge.foreground)
                    if (days > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.days_left_label), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
            }
            if (usage.total > 0) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Bridge.muted)) {
                    Box(Modifier.fillMaxWidth((usage.used.toFloat() / usage.total).coerceIn(0f, 1f)).height(5.dp).clip(CircleShape).background(if (low) Bridge.warning else Bridge.brand))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        val answering = servers.count { (it.delay ?: 0) > 0 }
        val parts = buildList {
            if (subscription == null) add(stringResource(R.string.manual_hint))
            if (subscription?.public == true) add(stringResource(R.string.public_hint))
            add(stringResource(R.string.responding, answering, servers.size))
            if (subscription != null && subscription.updatedAt > 0) add(stringResource(R.string.updated_when, DateUtils.getRelativeTimeSpanString(subscription.updatedAt).toString()))
        }
        Text(parts.joinToString(" "), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (subscription != null) {
                FillButton(stringResource(R.string.sub_update), Icons.RefreshCw, { model.updateSubscription(subscription) }, Modifier.weight(1f), enabled = subscription.id !in model.updating)
            }
            FillButton(stringResource(R.string.test), Icons.Zap, { model.measure(servers) }, Modifier.weight(1f), enabled = servers.none { it.id in model.measuring })
            Box(Modifier.clip(ItemShape).background(Bridge.muted)) { IconAction(Icons.Ellipsis, stringResource(R.string.more), onMore) }
        }
    }
}

private val BigNumber @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun ServerRow(model: AppModel, server: Server, source: String?, onPick: () -> Unit, onMore: () -> Unit) {
    val p = model.parse(server).getOrNull()
    val on = server.id == model.selected
    val dead = (server.delay ?: 1) <= 0
    val protocol = p?.let { protocolOf(it.kind) + if (it.usesXray) " + Xray" else "" }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(ItemShape).background(if (on) Bridge.primary.copy(alpha = 0.16f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).heightIn(min = 60.dp).clickable(onClick = onPick).padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { ServerFlag(server) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayName(server),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (on) Bridge.brand else if (dead) Bridge.mutedForeground else Bridge.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val country = server.country?.let { countryName(it) }
                Text(source ?: listOfNotNull(protocol, country).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (on) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Check, null, Modifier.size(18.dp), tint = Bridge.brand)
            }
            Spacer(Modifier.width(8.dp))
            Ping(server, model)
        }
        IconAction(Icons.Ellipsis, stringResource(R.string.more), onMore)
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 1.dp).height(60.dp).clip(ItemShape).background(shimmer(Bridge.card, Bridge.muted)))
}

@Composable
private fun NothingFound(title: String, line: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(line, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EmptyServers(model: AppModel, onAdd: () -> Unit, onPublic: () -> Unit) {
    val context = LocalContext.current
    BridgeCard(modifier = Modifier.padding(top = 4.dp), padding = PaddingValues(20.dp)) {
        Icon(Icons.Server, null, Modifier.size(28.dp), tint = Bridge.brand)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.empty_servers), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FillButton(stringResource(R.string.menu_paste_short), Icons.ClipboardPaste, { pasteInto(context, model) }, Modifier.weight(1f))
            FillButton(stringResource(R.string.add_servers), Icons.Plus, onAdd, Modifier.weight(1f), primary = true)
        }
        Spacer(Modifier.height(8.dp))
        FillButton(stringResource(R.string.menu_public_short), Icons.Sparkles, onPublic, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SheetTitle(title: String, line: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (line != null) Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, tint: Color = Bridge.brand, ink: Color = Bridge.foreground, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(ItemShape).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = ink)
    }
}

/** Remove, which asks for a second tap because it can't be undone. */
@Composable
private fun RemoveRow(title: String, onRemove: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    SheetRow(Icons.Trash2, if (armed) stringResource(R.string.tap_again_remove) else title, Bridge.destructive, Bridge.destructive) {
        if (armed) onRemove() else armed = true
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = GeistMono) else MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ConfigSheet(model: AppModel, server: Server, source: String, onQr: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val p = model.parse(server).getOrNull()
    val delay = server.delay
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SheetTitle(displayName(server))
        Column(Modifier.fillMaxWidth().clip(ItemShape).background(Bridge.muted).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact(stringResource(R.string.protocol), p?.let { protocolOf(it.kind) + if (it.usesXray) " + Xray" else "" } ?: "-", Modifier.weight(1f))
                Fact(stringResource(R.string.country), server.country?.let { countryName(it) } ?: "-", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fact(stringResource(R.string.ping), if (delay == null) "-" else if (delay <= 0) stringResource(R.string.delay_failed) else stringResource(R.string.delay_ms, delay), Modifier.weight(1f))
                Fact(stringResource(R.string.source), source, Modifier.weight(1f))
            }
            if (p != null) Fact(stringResource(R.string.address), "⁦${p.host}:${p.port}⁩", mono = true)
        }
        Spacer(Modifier.height(8.dp))
        SheetRow(Icons.QrCode, stringResource(R.string.server_qr), onClick = onQr)
        SheetRow(Icons.Copy, stringResource(R.string.server_copy)) {
            copy(context, server.link)
            onDone()
        }
        RemoveRow(stringResource(R.string.remove)) {
            model.removeServer(server)
            onDone()
        }
    }
}

@Composable
private fun SourceSheet(model: AppModel, subscription: Subscription?, servers: List<Server>, onDone: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SheetTitle(
            when {
                subscription == null -> stringResource(R.string.manual_configs)
                subscription.public -> stringResource(R.string.public_sub_name)
                else -> subscription.name
            },
            subscription?.url?.takeIf { !subscription.public && it.isNotEmpty() },
        )
        if (subscription != null && !subscription.public) {
            SheetRow(Icons.Copy, stringResource(R.string.sub_copy)) {
                copy(context, subscription.url)
                onDone()
            }
        }
        RemoveRow(stringResource(if (subscription != null && !subscription.public) R.string.remove_subscription else R.string.remove_all)) {
            if (subscription != null) model.removeSubscription(subscription) else servers.forEach { model.removeServer(it) }
            onDone()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WarpSheet(model: AppModel) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SheetTitle(stringResource(R.string.warp_name))
        Text(stringResource(R.string.warp_method), style = MaterialTheme.typography.bodySmall)
        for (choice in Warp.Choice.entries) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(ItemShape).clickable { model.changeWarpChoice(choice) }, verticalAlignment = Alignment.CenterVertically) {
                RadioMark(model.warpChoice == choice)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(warpLabel(choice)), style = MaterialTheme.typography.titleSmall)
            }
        }
        if (model.warpChoice == Warp.Choice.Psiphon) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RegionChip(null, model.psiphonRegion == null) { model.changePsiphonRegion(null) }
                for (code in Warp.PSIPHON_REGIONS) RegionChip(code, model.psiphonRegion == code) { model.changePsiphonRegion(code) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                when (model.warpChoice) {
                    Warp.Choice.Auto -> R.string.warp_auto_hint
                    Warp.Choice.Foreign -> R.string.warp_foreign_hint
                    Warp.Choice.Psiphon -> R.string.warp_psiphon_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RegionChip(code: String?, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(38.dp).clip(CircleShape).background(Bridge.muted).border(1.dp, if (on) Bridge.brand.copy(alpha = 0.6f) else Color.Transparent, CircleShape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (code != null) {
                FlagIcon(code, width = 18.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(code?.let { countryName(it) } ?: stringResource(R.string.warp_region_any), style = MaterialTheme.typography.labelLarge, color = if (on) Bridge.brand else Bridge.foreground)
        }
    }
}

/** What "Add servers" offers. */
@Composable
fun AddServersSheetContent(model: AppModel, onDone: () -> Unit, onEnter: () -> Unit, onPublic: () -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.add_servers), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        SettingRow(Icons.ClipboardPaste, stringResource(R.string.menu_paste), stringResource(R.string.menu_paste_hint), onClick = {
            onDone()
            pasteInto(context, model)
        })
        HorizontalDivider(color = Bridge.border)
        SettingRow(Icons.ScanLine, stringResource(R.string.menu_scan), stringResource(R.string.menu_scan_hint), onClick = {
            onDone()
            onScan()
        })
        HorizontalDivider(color = Bridge.border)
        SettingRow(Icons.Link2, stringResource(R.string.menu_enter), stringResource(R.string.menu_enter_hint), onClick = {
            onDone()
            onEnter()
        })
        HorizontalDivider(color = Bridge.border)
        SettingRow(Icons.Sparkles, stringResource(R.string.menu_public), stringResource(R.string.menu_public_hint), onClick = {
            onDone()
            onPublic()
        })
    }
}

/** The text field for a link or a subscription address. */
@Composable
fun EnterLinkDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bridge.popover,
        title = { Text(stringResource(R.string.enter_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.enter_hint), color = Bridge.mutedForeground) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = GeistMono),
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Bridge.brand, unfocusedBorderColor = Bridge.input, cursorColor = Bridge.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { PrimaryButton(stringResource(R.string.add), { onAdd(text) }, enabled = text.isNotBlank()) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Bridge.mutedForeground) } },
    )
}

/** The warning before using public configs. */
@Composable
fun PublicConfigsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bridge.popover,
        icon = { Icon(Icons.TriangleAlert, null, tint = Bridge.warning) },
        title = { Text(stringResource(R.string.public_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(stringResource(R.string.public_warning), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.public_how), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { PrimaryButton(stringResource(R.string.public_confirm), onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Bridge.mutedForeground) } },
    )
}

fun pasteInto(context: Context, model: AppModel) {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    if (text.isNullOrBlank()) model.say(R.string.clipboard_empty) else model.addText(text)
}

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bridge", text))
    // Android 13 and up show their own confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
