package io.github.m4hdisa.bridge.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m4hdisa.bridge.AUTO
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.Health
import io.github.m4hdisa.bridge.R
import io.github.m4hdisa.bridge.Subscription
import io.github.m4hdisa.bridge.VpnState
import io.github.m4hdisa.bridge.Warp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The dashboard, top to bottom: the connect key and how the connection is (where it leaves, on a
 * map, and its speed), the configs to pick from in a box, then the subscriptions. Mode lives in
 * Settings: on a phone Full VPN is what most people want.
 */
@Composable
fun HomeScreen(model: AppModel, onConnect: () -> Unit, onOpenServers: () -> Unit, onOpenApps: () -> Unit, onAdd: () -> Unit) {
    val status = model.status
    val keyState = when (status) {
        VpnState.Status.Running -> KeyState.On
        VpnState.Status.Stopped -> KeyState.Off
        else -> KeyState.Busy
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        ConnectKey(
            state = keyState,
            label = stringResource(if (status == VpnState.Status.Stopped) R.string.connect else R.string.disconnect),
            contentDescription = stringResource(if (status == VpnState.Status.Stopped) R.string.connect else R.string.disconnect),
            enabled = status != VpnState.Status.Stopping,
            onClick = onConnect,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when {
                    keyState == KeyState.On && model.health.state == Health.State.Failed -> Bridge.warning
                    keyState == KeyState.On -> Bridge.success
                    keyState == KeyState.Busy -> Bridge.brand
                    else -> Bridge.mutedForeground
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
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            // As wide as the dot and its gap, so the words sit in the middle under the button.
            Spacer(Modifier.width(18.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            when (status) {
                VpnState.Status.Running -> stringResource(R.string.connected_for, elapsed(model.connectedAt))
                VpnState.Status.Starting -> stringResource(model.progress ?: R.string.status_starting_hint)
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

    if (model.running) {
        Spacer(Modifier.height(20.dp))
        ConnectionPanel(model)
    }

    SectionLabel(stringResource(R.string.home_server)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefreshAction(stringResource(R.string.refresh_all), model.updating.isNotEmpty()) { model.refreshAll() }
            IconAction(Icons.Zap, stringResource(R.string.test_all), { model.measure() }, enabled = model.servers.isNotEmpty() && model.busy == null)
            IconAction(Icons.Plus, stringResource(R.string.add_servers), onAdd)
            Row(Modifier.clip(ControlShape).clickable(onClick = onOpenServers).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.manage_servers), style = MaterialTheme.typography.labelMedium, color = Bridge.mutedForeground)
                Spacer(Modifier.width(4.dp))
                DirectionalIcon(Icons.ArrowRight, null, Modifier.size(14.dp))
            }
        }
    }
    ConfigCards(model, onAdd)

    if (model.perApp) {
        SectionLabel(stringResource(R.string.apps_on_vpn))
        AppsCard(model, onOpenApps)
    }


    if (model.bypassIran) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.MapPin, null, Modifier.size(14.dp), tint = Bridge.mutedForeground)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.iran_skips), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "1:05" or "1:02:05", kept left to right in Persian text; ticks every second. */
@Composable
private fun elapsed(since: Long?): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - (since ?: now)) / 1000).coerceAtLeast(0)
    val text = if (seconds >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
    return "⁦$text⁩"
}

/** A country's name in the app's language. */
@Composable
fun countryName(code: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return Locale("", code).getDisplayCountry(locale).ifEmpty { code }
}

/** Where traffic leaves, on a map with where the phone is; then the speed and a speed test. */
@Composable
private fun ConnectionPanel(model: AppModel) {
    val health = model.health
    val t = model.traffic
    val speed = model.speed
    val warp = model.warpSelected
    val context = LocalContext.current
    BridgeCard(padding = PaddingValues(0.dp)) {
        WorldMap(
            home = model.homeCountry,
            exitCountry = health.country,
            exitAt = health.at,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        )
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val country = health.country
            when {
                health.state == Health.State.Failed -> {
                    Icon(Icons.CircleAlert, null, Modifier.size(18.dp), tint = Bridge.warning)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.not_answering), style = MaterialTheme.typography.bodyMedium, color = Bridge.warning, modifier = Modifier.weight(1f))
                    IconAction(Icons.RefreshCw, stringResource(R.string.health_check_now), { model.checkNow() })
                }
                country != null -> {
                    if (warp) {
                        Image(painterResource(R.drawable.ic_cloudflare), null, Modifier.size(width = 26.dp, height = 16.dp))
                    } else {
                        FlagIcon(country, width = 26.dp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (warp) "Cloudflare WARP" else countryName(country),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (warp) stringResource(R.string.exit_in, countryName(country)) else health.city ?: stringResource(R.string.exit_location),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        health.ip?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono), maxLines = 1) }
                        if (health.delay > 0) {
                            Spacer(Modifier.height(3.dp))
                            DelayBadge(health.delay)
                        }
                    }
                }
                else -> {
                    Icon(Icons.Globe, null, Modifier.size(16.dp), tint = Bridge.mutedForeground)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.exit_finding), style = MaterialTheme.typography.bodyMedium, color = Bridge.mutedForeground)
                }
            }
        }
        HorizontalDivider(color = Bridge.border)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeedCell(Icons.ArrowDown, Bridge.brand, rate(t.down), stringResource(R.string.speed_down), Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(40.dp).background(Bridge.border))
            SpeedCell(Icons.ArrowUp, Bridge.mutedForeground, rate(t.up), stringResource(R.string.speed_up), Modifier.weight(1f))
        }
        HorizontalDivider(color = Bridge.border)
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    speed.failed -> Text(stringResource(R.string.speed_failed), style = MaterialTheme.typography.bodySmall, color = Bridge.destructive)
                    speed.down != null || speed.up != null -> Text(
                        listOfNotNull(
                            speed.down?.let { "↓ " + stringResource(R.string.speed_mbps, mbpsText(it)) },
                            speed.up?.let { "↑ " + stringResource(R.string.speed_mbps, mbpsText(it)) },
                        ).joinToString("   "),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    else -> Text(
                        stringResource(R.string.speed_total, Formatter.formatShortFileSize(context, t.totalDown), Formatter.formatShortFileSize(context, t.totalUp)),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlineButton(
                stringResource(if (speed.running) R.string.speed_testing else R.string.speed_test),
                { model.testSpeed() },
                icon = Icons.Gauge,
                enabled = !speed.running,
                height = 32.dp,
            )
        }
    }
}

@Composable
private fun SpeedCell(icon: ImageVector, tint: Color, value: String, label: String, modifier: Modifier) {
    Row(modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val IRAN_FLAG = "🇮🇷"

fun mbpsText(value: Double): String = String.format(Locale.US, if (value >= 10) "%.0f" else "%.1f", value)

/**
 * The world as dots, with where the phone is and where traffic leaves, and an arc between them.
 * Drawn from data in the app: no map service is asked, so no one learns where the phone is.
 */
@Composable
fun WorldMap(home: String?, exitCountry: String?, exitAt: Pair<Double, Double>?, modifier: Modifier = Modifier) {
    val from = home?.let { WorldData.PLACES[it] }
    val to = exitAt ?: exitCountry?.let { WorldData.PLACES[it] }
    val pulse by rememberInfiniteTransition(label = "map").animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "pulse")
    val cols = WorldData.COLS.toFloat()
    val rows = WorldData.LAND.size.toFloat()
    val land = Bridge.mutedForeground.copy(alpha = 0.28f)
    val homeColor = Bridge.foreground
    val exitColor = Bridge.success
    val line = Bridge.brand
    val hole = Bridge.card
    // Every land dot's middle, in map units (one unit a dot), worked out once.
    val dots = remember {
        val list = ArrayList<Offset>()
        for ((r, hex) in WorldData.LAND.withIndex()) {
            for ((i, ch) in hex.withIndex()) {
                val bits = ch.digitToInt(16)
                for (b in 0 until 4) if (bits and (8 shr b) != 0) list += Offset(i * 4 + b + 0.5f, r + 0.5f)
            }
        }
        list
    }
    fun unit(at: Pair<Double, Double>) = Offset(
        ((at.second + 180) / WorldData.STEP).toFloat(),
        ((WorldData.TOP - at.first) / WorldData.STEP).toFloat().coerceIn(0f, rows),
    )
    // Once both places are known, the map zooms in on them and brings them to the middle.
    val target = remember(from, to) {
        val a = from?.let { unit(it) }
        val b = to?.let { unit(it) } ?: return@remember null
        val left = minOf(a?.x ?: b.x, b.x)
        val right = maxOf(a?.x ?: b.x, b.x)
        val top = minOf(a?.y ?: b.y, b.y)
        val bottom = maxOf(a?.y ?: b.y, b.y)
        // A gentle zoom: both places with plenty of map around them, never more than 2.2 times.
        val zoom = minOf(cols / maxOf((right - left) * 3.2f, 60f), rows / maxOf((bottom - top) * 4f, 24f)).coerceIn(1f, 2.2f)
        val halfW = cols / (2 * zoom)
        val halfH = rows / (2 * zoom)
        Triple(zoom, ((left + right) / 2).coerceIn(halfW, cols - halfW), ((top + bottom) / 2).coerceIn(halfH, rows - halfH))
    }
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(target) {
        if (target == null) progress.snapTo(0f) else progress.animateTo(1f, tween(1200, delayMillis = 300))
    }
    Box(modifier.clip(ItemShape)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(cols / rows)) {
            val t = progress.value
            val zoom = 1f + ((target?.first ?: 1f) - 1f) * t
            val cx = cols / 2 + ((target?.second ?: (cols / 2)) - cols / 2) * t
            val cy = rows / 2 + ((target?.third ?: (rows / 2)) - rows / 2) * t
            val cell = size.width / cols
            val scale = cell * zoom
            fun screen(u: Offset) = Offset((u.x - cx) * scale + size.width / 2, (u.y - cy) * scale + size.height / 2)
            fun point(at: Pair<Double, Double>) = screen(unit(at))
            // The dots stay small as the map zooms, so it reads as a closer look, not bigger dots.
            drawPoints(dots.map { screen(it) }, androidx.compose.ui.graphics.PointMode.Points, land, strokeWidth = cell * 0.64f * (0.6f + 0.4f * zoom), cap = StrokeCap.Round)
            // The two countries, filled lightly and outlined.
            fun country(code: String?, color: Color) {
                val shape = code?.let { WorldData.SHAPES[it] } ?: return
                val path = Path()
                for (ring in shape.split(';')) {
                    ring.split(' ').forEachIndexed { i, pt ->
                        val lon = pt.substringBefore(',').toDouble()
                        val lat = pt.substringAfter(',').toDouble()
                        val p = point(lat to lon)
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                }
                drawPath(path, color.copy(alpha = 0.22f))
                drawPath(path, color, style = Stroke(width = 1.2.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            if (home != exitCountry) country(home, homeColor)
            country(exitCountry, exitColor)
            val a = from?.let { point(it) }
            val b = to?.let { point(it) }
            if (a != null && b != null && (a - b).getDistance() > cell) {
                // An arc bowed upwards, like a flight route.
                val mid = Offset((a.x + b.x) / 2, minOf(a.y, b.y) - (a - b).getDistance() * 0.3f)
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    quadraticTo(mid.x, mid.y, b.x, b.y)
                }
                drawPath(path, line, style = Stroke(width = cell * 0.35f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(cell * 0.9f, cell * 0.6f))))
            }
            a?.let {
                drawCircle(homeColor, cell * 0.75f, it)
                drawCircle(hole, cell * 0.35f, it)
            }
            b?.let {
                drawCircle(exitColor.copy(alpha = (1 - pulse) * 0.5f), cell * (0.8f + pulse * 2.2f), it)
                drawCircle(exitColor, cell * 0.8f, it)
            }
        }
        // What the two marks are.
        Row(Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (from != null) MapKey(homeColor, stringResource(R.string.map_you), home)
            if (to != null) MapKey(exitColor, stringResource(R.string.map_exit), exitCountry)
        }
    }
}

@Composable
private fun MapKey(color: Color, text: String, country: String?) {
    Row(
        Modifier.clip(ControlShape).background(Bridge.card.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Bridge.mutedForeground)
        // Iran's flag is never shown, so no flag at all there.
        if (country != null && country != "IR") {
            Spacer(Modifier.width(5.dp))
            FlagIcon(country, width = 14.dp)
        }
    }
}

/**
 * The configs on the dashboard, a card a section: Free WARP with its saved configs, Auto, then
 * each subscription (its traffic and days left on top, its own update and test) and the configs
 * added by hand. A tap picks a config; long lists show the first few until opened.
 */
@Composable
private fun ConfigCards(model: AppModel, onAdd: () -> Unit) {
    val context = LocalContext.current
    val usable = model.servers.filter { model.parse(it).isSuccess }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (model.warpAvailable) {
            BridgeCard(padding = PaddingValues(6.dp)) {
                ConfigRow(
                    on = model.warpSelected,
                    lead = { Image(painterResource(R.drawable.ic_cloudflare), null, Modifier.size(width = 24.dp, height = 14.dp)) },
                    title = stringResource(R.string.warp_name),
                    subtitle = "Cloudflare WARP",
                    trailing = { Badge(stringResource(R.string.warp_badge), Tone.Success) },
                ) { model.select(Warp.ID) }
                for (config in model.warpConfigs) WarpConfigRow(model, config)
            }
        }
        if (usable.size > 1) {
            BridgeCard(padding = PaddingValues(6.dp)) {
                val now = if (model.autoSelected) model.activeServer() else null
                ConfigRow(
                    on = model.autoSelected,
                    lead = { Icon(Icons.Zap, null, Modifier.size(18.dp), tint = Bridge.brand) },
                    title = stringResource(R.string.auto_name),
                    subtitle = if (now != null) stringResource(R.string.auto_now, now.name) else stringResource(R.string.auto_hint_short, usable.size),
                ) { model.select(AUTO) }
            }
        }
        // A card a subscription (and one for configs added by hand).
        val groups = model.subscriptions.map { it to usable.filter { s -> s.subscription == it.id } }.filter { it.second.isNotEmpty() } +
            listOf(null to usable.filter { s -> model.subscriptions.none { it.id == s.subscription } }).filter { it.second.isNotEmpty() }
        for ((subscription, servers) in groups) {
            BridgeCard(padding = PaddingValues(6.dp)) {
                GroupHeader(model, subscription, servers)
                subscription?.usage?.let { SubUsage(it, Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                var open by remember(subscription?.id) { mutableStateOf(false) }
                val chosenInside = servers.any { it.id == model.selected }
                val shown = if (open || servers.size <= FOLD) servers else servers.take(FOLD).let { first -> if (chosenInside && first.none { it.id == model.selected }) first + servers.first { it.id == model.selected } else first }
                for (server in shown) ServerChoice(model, server)
                if (servers.size > FOLD) {
                    Row(
                        Modifier.fillMaxWidth().clip(ItemShape).clickable { open = !open }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(if (open) R.string.show_less else R.string.show_all, servers.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = Bridge.brand,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.ChevronDown, null, Modifier.size(14.dp).rotate(if (open) 180f else 0f), tint = Bridge.brand)
                    }
                }
            }
        }
        if (usable.isEmpty()) {
            BridgeCard {
                Column(Modifier.padding(10.dp)) {
                    Text(stringResource(R.string.empty_servers), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(stringResource(R.string.menu_paste_short), { pasteInto(context, model) }, icon = Icons.ClipboardPaste, height = 36.dp)
                        OutlineButton(stringResource(R.string.add_servers), onAdd, icon = Icons.Plus, height = 36.dp)
                    }
                }
            }
        }
    }
}

/** How many configs a subscription's card shows before "Show all". */
private const val FOLD = 6

/** A subscription's traffic and days left, as its panel told them: a bar for the traffic, the days beside it. */
@Composable
private fun SubUsage(usage: io.github.m4hdisa.bridge.Usage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val ends = usage.expire * 1000
    val days = if (usage.expire > 0) ((ends - now) / DateUtils.DAY_IN_MILLIS).toInt() else null
    val left = usage.left()
    Column(modifier.fillMaxWidth().clip(ItemShape).background(Bridge.muted).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Gauge, null, Modifier.size(14.dp), tint = Bridge.brand)
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    usage.total > 0 -> stringResource(R.string.stat_traffic_of, Formatter.formatShortFileSize(context, usage.used), Formatter.formatShortFileSize(context, usage.total))
                    left != null -> stringResource(R.string.stat_traffic_left, Formatter.formatShortFileSize(context, left))
                    else -> stringResource(R.string.stat_unlimited)
                },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (days != null) {
                Text(
                    if (ends <= now) stringResource(R.string.stat_ended) else pluralStringResource(R.plurals.stat_days_left, days, days),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ends - now < 3 * DateUtils.DAY_IN_MILLIS) Bridge.warning else Bridge.mutedForeground,
                )
            }
        }
        if (usage.total > 0) {
            Spacer(Modifier.height(8.dp))
            val used = (usage.used.toFloat() / usage.total).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(ControlShape).background(Bridge.background)) {
                Box(Modifier.fillMaxWidth(used).height(4.dp).background(if (used >= 0.9f) Bridge.warning else Bridge.brand))
            }
            left?.let {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.stat_traffic_left, Formatter.formatShortFileSize(context, it)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A section's title in the config box: the subscription, how many configs, and its own update and test. */
@Composable
private fun GroupHeader(model: AppModel, subscription: Subscription?, servers: List<io.github.m4hdisa.bridge.Server>) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when {
                subscription == null -> Icons.Pencil
                subscription.public -> Icons.Sparkles
                else -> Icons.Link2
            },
            null,
            Modifier.size(14.dp),
            tint = Bridge.brand,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                subscription == null -> stringResource(R.string.manual_configs)
                subscription.public -> stringResource(R.string.public_sub_name)
                else -> subscription.name
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.configs_count, servers.size), style = MaterialTheme.typography.labelSmall, color = Bridge.mutedForeground)
        Spacer(Modifier.weight(1f))
        if (subscription != null) RefreshAction(stringResource(R.string.sub_update), subscription.id in model.updating) { model.updateSubscription(subscription) }
        IconAction(Icons.Zap, stringResource(R.string.test), { model.measure(servers) }, enabled = model.busy == null)
    }
}

@Composable
private fun ServerChoice(model: AppModel, server: io.github.m4hdisa.bridge.Server) {
    val p = model.parse(server).getOrNull()
    val country = server.country
    ConfigRow(
        on = server.id == model.selected,
        lead = {
            if (country != null) {
                FlagIcon(country, width = 22.dp)
            } else {
                Icon(Icons.Globe, null, Modifier.size(18.dp), tint = Bridge.mutedForeground)
            }
        },
        // The flag is already beside it, so not again in the name; Iran's is never shown.
        title = server.name.replace(IRAN_FLAG, "").let { name -> country?.let { name.replace(flag(it).ifEmpty { IRAN_FLAG }, "") } ?: name }.trim().ifEmpty { server.name },
        subtitle = listOfNotNull(
            country?.let { countryName(it) },
            p?.let { protocolOf(it.kind) + if (it.usesXray) " · Xray" else "" },
        ).joinToString(" · "),
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                DelayBadge(server.delay)
                server.speed?.let {
                    Spacer(Modifier.height(3.dp))
                    Text("↓ " + stringResource(R.string.speed_mbps, mbpsText(it)), style = MaterialTheme.typography.labelSmall, color = Bridge.mutedForeground)
                }
            }
        },
    ) { model.select(server.id) }
}

@Composable
private fun ConfigRow(
    on: Boolean,
    lead: @Composable () -> Unit,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ItemShape)
            .background(if (on) Bridge.primary.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (on) Modifier.border(1.dp, Bridge.primary.copy(alpha = 0.45f), ItemShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        Spacer(Modifier.width(10.dp))
        RadioMark(on)
    }
}

/** A line of text with an icon, tinted for errors and warnings. */
@Composable
fun Notice(icon: ImageVector, text: String, tone: Tone, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
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

/** "vless / ws / tls" reads as "VLESS" on a badge. */
fun protocolOf(kind: String): String = kind.substringBefore(" / ").uppercase()

@Composable
private fun AppsCard(model: AppModel, onOpenApps: () -> Unit) {
    val chosen = model.chosenApps
    val names = model.installedApps?.filter { it.packageName in chosen }?.map { it.label } ?: emptyList()
    BridgeCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
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

/** The small round mark used in the top bar: the logo on its blue. */
@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    Box(modifier.size(32.dp).clip(ItemShape).background(Bridge.primary), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_notification), null, Modifier.size(20.dp), tint = Bridge.primaryForeground)
    }
}
