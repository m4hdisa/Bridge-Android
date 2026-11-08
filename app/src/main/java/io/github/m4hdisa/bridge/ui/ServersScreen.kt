package io.github.m4hdisa.bridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.R
import io.github.m4hdisa.bridge.Server
import io.github.m4hdisa.bridge.Subscription

/** The Servers page, as items of the page's list: subscriptions and configs, the chosen one highlighted. */
fun LazyListScope.serversPage(model: AppModel, onAdd: () -> Unit, onPublic: () -> Unit) {
    item {
        PageHeader(stringResource(R.string.servers_title), stringResource(R.string.servers_subtitle))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton(stringResource(R.string.test_all), { model.measure() }, icon = Icons.Zap, enabled = model.servers.isNotEmpty())
            PrimaryButton(stringResource(R.string.add_servers), onAdd, icon = Icons.Plus)
        }
        if (model.subscriptions.any { !it.public }) {
            Spacer(Modifier.height(10.dp))
            OutlineButton(stringResource(R.string.update_subs), { model.updateSubscriptions(onlyStale = false) }, icon = Icons.RefreshCw, height = 36.dp)
        }
        Spacer(Modifier.height(16.dp))
    }
    item(key = "warp") {
        WarpCard(model)
        Spacer(Modifier.height(12.dp))
    }
    val servers = model.servers
    val subscriptions = model.subscriptions
    if (servers.isEmpty() && subscriptions.isEmpty()) {
        item { EmptyServers(model, onPublic) }
        return
    }
    if (servers.size > 1) {
        item {
            AutoCard(model)
            Spacer(Modifier.height(12.dp))
        }
    }
    for (subscription in subscriptions) {
        item(key = "sub-${subscription.id}") {
            GroupCard(model, subscription, servers.filter { it.subscription == subscription.id })
            Spacer(Modifier.height(12.dp))
        }
    }
    val manual = servers.filter { s -> s.subscription == null || subscriptions.none { it.id == s.subscription } }
    if (manual.isNotEmpty()) {
        item(key = "manual") {
            GroupCard(model, null, manual)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EmptyServers(model: AppModel, onPublic: () -> Unit) {
    val context = LocalContext.current
    BridgeCard(padding = PaddingValues(20.dp)) {
        Icon(Icons.Server, null, Modifier.size(28.dp), tint = Bridge.brand)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.empty_servers), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.menu_paste_short), { pasteInto(context, model) }, icon = Icons.ClipboardPaste)
            OutlineButton(stringResource(R.string.menu_public_short), onPublic, icon = Icons.Sparkles)
        }
    }
}

@Composable
private fun AutoCard(model: AppModel) {
    val on = model.autoSelected
    val now = if (on) model.activeServer() else null
    BridgeCard(padding = PaddingValues(14.dp), onClick = { model.select(io.github.m4hdisa.bridge.AUTO) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(ItemShape).background(if (on) Bridge.primary else Bridge.muted), contentAlignment = Alignment.Center) {
                Icon(Icons.Zap, null, Modifier.size(18.dp), tint = if (on) Bridge.primaryForeground else Bridge.mutedForeground)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_name), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (now != null) stringResource(R.string.auto_now, now.name) else stringResource(R.string.auto_hint, model.servers.size),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RadioMark(on)
        }
    }
}

/** A subscription (or the hand-added configs, when `subscription` is null) and its servers. */
@Composable
private fun GroupCard(model: AppModel, subscription: Subscription?, servers: List<Server>) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var showUnusable by remember { mutableStateOf(false) }
    val usable = servers.filter { model.parse(it).isSuccess }
    val unusable = servers.filter { model.parse(it).isFailure }
    BridgeCard(padding = PaddingValues(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (subscription == null) Icons.Pencil else if (subscription.public) Icons.Sparkles else Icons.Link2, null, Modifier.size(16.dp), tint = Bridge.brand)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            subscription == null -> stringResource(R.string.manual_configs)
                            subscription.public -> stringResource(R.string.public_sub_name)
                            else -> subscription.name
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subscription?.public == true) {
                        Spacer(Modifier.width(8.dp))
                        Badge(stringResource(R.string.public_badge), Tone.Warning)
                    }
                }
                val line = when {
                    subscription == null -> stringResource(R.string.manual_hint)
                    subscription.public -> stringResource(R.string.public_hint)
                    else -> subscription.url
                }
                Text(
                    line,
                    style = if (subscription != null && !subscription.public) MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono) else MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconAction(Icons.Zap, stringResource(R.string.test), { model.measure(servers) }, enabled = servers.isNotEmpty())
            if (subscription != null) IconAction(Icons.RefreshCw, stringResource(R.string.sub_update), { model.updateSubscription(subscription) })
            Box {
                IconAction(Icons.Ellipsis, stringResource(R.string.more), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Bridge.popover) {
                    if (subscription != null && !subscription.public) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.sub_copy)) }, leadingIcon = { Icon(Icons.Copy, null, Modifier.size(16.dp)) }, onClick = {
                            menu = false
                            copy(context, subscription.url)
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove), color = Bridge.destructive) }, leadingIcon = { Icon(Icons.Trash2, null, Modifier.size(16.dp), tint = Bridge.destructive) }, onClick = {
                        menu = false
                        if (subscription != null) model.removeSubscription(subscription) else servers.forEach { model.removeServer(it) }
                    })
                }
            }
        }
        if (subscription != null) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val responding = servers.count { (it.delay ?: 0) > 0 }
                Stat(stringResource(R.string.stat_configs), stringResource(R.string.stat_usable, usable.size), stringResource(R.string.stat_of, servers.size, responding), Modifier.weight(1f))
                val updated = if (subscription.updatedAt > 0) DateUtils.getRelativeTimeSpanString(subscription.updatedAt).toString() else stringResource(R.string.sub_never)
                Stat(stringResource(R.string.stat_updated), updated, stringResource(R.string.stat_auto_daily), Modifier.weight(1f))
            }
            if (subscription.usage != null) {
                Spacer(Modifier.height(8.dp))
                UsageStats(subscription, Modifier.padding(end = 8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        for (server in usable) ServerRow(model, server)
        if (unusable.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(ItemShape).combinedClickable(onClick = { showUnusable = !showUnusable }).padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (showUnusable) Icons.ChevronDown else Icons.ChevronRight, null, Modifier.size(14.dp), tint = Bridge.mutedForeground)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.unsupported_count, unusable.size), style = MaterialTheme.typography.bodySmall)
            }
            if (showUnusable) for (server in unusable) ServerRow(model, server)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(ItemShape).background(Bridge.muted).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerRow(model: AppModel, server: Server, onPicked: (() -> Unit)? = null) {
    val context = LocalContext.current
    val parsed = model.parse(server)
    val p = parsed.getOrNull()
    val on = server.id == model.selected
    var menu by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    if (qr) QrDialog(server.link, server.name) { qr = false }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ItemShape)
                .background(if (on) Bridge.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(
                    onClick = {
                        if (p != null) {
                            model.select(server.id)
                            onPicked?.invoke()
                        }
                    },
                    onLongClick = { menu = true },
                )
                .alpha(if (p != null) 1f else 0.55f)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioMark(on)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge(protocolOf(p.kind))
                        if (p.usesXray) Badge("Xray", Tone.Warning)
                        DelayBadge(server.delay)
                    }
                } else {
                    Text(stringResource(R.string.unusable, parsed.exceptionOrNull()?.message ?: ""), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Bridge.popover) {
            DropdownMenuItem(text = { Text(stringResource(R.string.server_copy)) }, leadingIcon = { Icon(Icons.Copy, null, Modifier.size(16.dp)) }, onClick = {
                menu = false
                copy(context, server.link)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.server_qr)) }, leadingIcon = { Icon(Icons.QrCode, null, Modifier.size(16.dp)) }, onClick = {
                menu = false
                qr = true
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.remove), color = Bridge.destructive) }, leadingIcon = { Icon(Icons.Trash2, null, Modifier.size(16.dp), tint = Bridge.destructive) }, onClick = {
                menu = false
                model.removeServer(server)
            })
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

/** Picking a server from the dashboard: Auto and every usable server, a tap away. */
@Composable
fun ServerPickerContent(model: AppModel, onDone: () -> Unit, onManage: () -> Unit) {
    val usable = model.servers.filter { model.parse(it).isSuccess }
    LazyColumn(Modifier.padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pick_server), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onDone()
                    onManage()
                }) { Text(stringResource(R.string.manage_servers), color = Bridge.brand) }
            }
        }
        if (usable.isEmpty()) {
            item { Text(stringResource(R.string.empty_servers), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
        }
        if (model.warpAvailable) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(ItemShape)
                        .background(if (model.warpSelected) Bridge.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClickable(onClick = {
                            model.select(io.github.m4hdisa.bridge.Warp.ID)
                            onDone()
                        })
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioMark(model.warpSelected)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.warp_name), style = MaterialTheme.typography.titleSmall)
                        Text("Cloudflare WARP", style = MaterialTheme.typography.bodySmall)
                    }
                    Badge(stringResource(R.string.warp_badge), Tone.Success)
                }
            }
        }
        if (model.servers.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(ItemShape)
                        .background(if (model.autoSelected) Bridge.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClickable(onClick = {
                            model.select(io.github.m4hdisa.bridge.AUTO)
                            onDone()
                        })
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioMark(model.autoSelected)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.auto_name), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.auto_hint_short, model.servers.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(usable, key = { it.id }) { ServerRow(model, it, onPicked = onDone) }
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
