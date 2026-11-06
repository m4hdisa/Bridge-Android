package io.github.m4hdisa.bridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.Health
import io.github.m4hdisa.bridge.R
import io.github.m4hdisa.bridge.VpnState

/** The dashboard: the connect key, the mode, the server, the apps on the VPN and the connection check. */
@Composable
fun HomeScreen(model: AppModel, onConnect: () -> Unit, onOpenServers: () -> Unit, onPickServer: () -> Unit, onOpenApps: () -> Unit) {
    val status = model.status
    val keyState = when (status) {
        VpnState.Status.Running -> KeyState.On
        VpnState.Status.Stopped -> KeyState.Off
        else -> KeyState.Busy
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        ConnectKey(
            state = keyState,
            label = stringResource(if (status == VpnState.Status.Stopped) R.string.connect else R.string.disconnect),
            contentDescription = stringResource(if (status == VpnState.Status.Stopped) R.string.connect else R.string.disconnect),
            enabled = status != VpnState.Status.Stopping,
            onClick = onConnect,
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when (keyState) {
                    KeyState.On -> Bridge.success
                    KeyState.Busy -> Bridge.brand
                    KeyState.Off -> Bridge.mutedForeground
                },
                pulse = keyState == KeyState.Busy,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(
                    when (status) {
                        VpnState.Status.Stopped -> R.string.status_off
                        VpnState.Status.Starting -> R.string.status_starting
                        VpnState.Status.Running -> R.string.status_on
                        VpnState.Status.Stopping -> R.string.status_stopping
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Spacer(Modifier.height(6.dp))
        val active = model.activeServer()
        Text(
            when {
                status == VpnState.Status.Running && active != null -> stringResource(R.string.status_on_hint, active.name)
                status == VpnState.Status.Running -> stringResource(R.string.status_on_auto_hint)
                status == VpnState.Status.Starting -> stringResource(R.string.status_starting_hint)
                else -> stringResource(R.string.status_off_hint)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Bridge.mutedForeground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    val error = model.vpnError?.let { stringResource(R.string.error_prefix, it) } ?: model.localError?.let { stringResource(it) }
    if (error != null) {
        Spacer(Modifier.height(16.dp))
        Notice(Icons.CircleAlert, error, Tone.Danger)
    }

    SectionLabel(stringResource(R.string.mode))
    Segmented(
        options = listOf(
            Triple(true, stringResource(R.string.mode_per_app), Icons.Split),
            Triple(false, stringResource(R.string.mode_full), Icons.Globe),
        ),
        selected = model.perApp,
        onSelect = { model.changePerApp(it) },
    )
    Text(
        stringResource(if (model.perApp) R.string.mode_per_app_hint else R.string.mode_full_hint),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )

    SectionLabel(stringResource(R.string.home_server)) {
        Row(Modifier.clip(ControlShape).clickable(onClick = onOpenServers).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.manage_servers), style = MaterialTheme.typography.labelMedium, color = Bridge.mutedForeground)
            Spacer(Modifier.width(4.dp))
            DirectionalIcon(Icons.ArrowRight, null, Modifier.size(14.dp))
        }
    }
    ServerCard(model, onPickServer)

    if (model.perApp) {
        SectionLabel(stringResource(R.string.apps_on_vpn))
        AppsCard(model, onOpenApps)
    }

    SectionLabel(stringResource(R.string.health_title))
    HealthCard(model)

    if (model.bypassIran) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.MapPin, null, Modifier.size(14.dp), tint = Bridge.mutedForeground)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.iran_skips), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A line of text with an icon, tinted for errors and warnings. */
@Composable
fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tone: Tone, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(tone.fill)
            .border(1.dp, tone.line, CardShape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = tone.text)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = tone.text)
        }
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(start = 28.dp)) { action() }
        }
    }
}

@Composable
private fun ServerCard(model: AppModel, onPick: () -> Unit) {
    val servers = model.servers
    BridgeCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp), onClick = onPick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (servers.isEmpty()) {
                    Text(stringResource(R.string.no_server), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.add_server_hint), style = MaterialTheme.typography.bodySmall)
                } else if (model.autoSelected) {
                    Text(stringResource(R.string.auto_name), style = MaterialTheme.typography.titleMedium)
                    val now = model.activeServer()
                    Text(
                        if (now != null) stringResource(R.string.auto_now, now.name) else stringResource(R.string.auto_hint_short, servers.size),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val server = servers.firstOrNull { it.id == model.selected } ?: servers.first()
                    val parsed = model.parse(server).getOrNull()
                    Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        parsed?.let { Badge(protocolOf(it.kind)) }
                        if (parsed?.usesXray == true) Badge("Xray", Tone.Warning)
                        DelayBadge(server.delay)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.ChevronDown, null, Modifier.size(18.dp), tint = Bridge.mutedForeground)
        }
    }
}

/** "vless / ws / tls" reads as "VLESS" on a badge. */
fun protocolOf(kind: String): String = kind.substringBefore(" / ").uppercase()

@Composable
private fun AppsCard(model: AppModel, onOpenApps: () -> Unit) {
    val chosen = model.chosenApps
    val names = model.installedApps?.filter { it.packageName in chosen }?.map { it.label } ?: emptyList()
    BridgeCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(ItemShape).background(Bridge.muted), contentAlignment = Alignment.Center) {
                Icon(Icons.Smartphone, null, Modifier.size(18.dp), tint = Bridge.mutedForeground)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.apps_count, chosen.size), style = MaterialTheme.typography.titleSmall)
                if (names.isNotEmpty()) {
                    Text(names.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlineButton(stringResource(R.string.add_app), onOpenApps, icon = Icons.Plus, height = 34.dp)
        }
    }
}

@Composable
private fun HealthCard(model: AppModel) {
    val health = model.health
    BridgeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.ShieldCheck, null, Modifier.size(18.dp), tint = Bridge.mutedForeground)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.health_vpn), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.health_hint), style = MaterialTheme.typography.bodySmall)
            }
            val (label, tone) = when (health.state) {
                Health.State.Idle -> stringResource(R.string.health_paused) to Tone.Neutral
                Health.State.Checking -> stringResource(R.string.health_checking) to Tone.Brand
                Health.State.Ok -> stringResource(R.string.delay_ms, health.delay) to if (health.delay >= 800) Tone.Warning else Tone.Success
                Health.State.Failed -> stringResource(R.string.health_failed) to Tone.Danger
            }
            Badge(label, tone)
        }
        if (health.state == Health.State.Ok && health.ip != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(ItemShape).background(Bridge.muted).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.health_exit), style = MaterialTheme.typography.bodySmall)
                    Text(health.ip, style = MaterialTheme.typography.titleSmall.copy(fontFamily = GeistMono, fontWeight = FontWeight.Normal))
                }
                health.country?.let { c ->
                    Text("${flag(c)} $c".trim(), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        if (health.state == Health.State.Failed) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.health_failed_hint), style = MaterialTheme.typography.bodySmall, color = Bridge.destructive)
        }
        if (model.running) {
            Spacer(Modifier.height(12.dp))
            OutlineButton(stringResource(R.string.health_check_now), { model.checkNow() }, icon = Icons.RefreshCw, height = 34.dp)
        }
    }
}

private fun flag(country: String): String {
    if (country.length != 2 || !country.all { it in 'A'..'Z' }) return ""
    return country.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** The small round mark used in the top bar: the logo on its blue. */
@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    Box(modifier.size(32.dp).clip(ItemShape).background(Bridge.primary), contentAlignment = Alignment.Center) {
        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_notification), null, Modifier.size(20.dp), tint = Bridge.primaryForeground)
    }
}
