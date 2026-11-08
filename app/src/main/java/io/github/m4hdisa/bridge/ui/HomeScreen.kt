package io.github.m4hdisa.bridge.ui

import android.text.format.Formatter
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.Health
import io.github.m4hdisa.bridge.R
import io.github.m4hdisa.bridge.Server
import io.github.m4hdisa.bridge.Traffic
import io.github.m4hdisa.bridge.VpnState
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The dashboard, one screen that never scrolls: the header, the connect key with equal room above
 * and below it, how the connection is, and the chosen server. The list of servers has a page of
 * its own, opened from that last box.
 */
@Composable
fun HomeScreen(model: AppModel, onConnect: () -> Unit, onOpenServers: () -> Unit, onAdd: () -> Unit) {
    val status = model.status
    val canConnect = model.warpSelected || model.servers.isNotEmpty()
    val keyState = when (status) {
        VpnState.Status.Running -> KeyState.On
        VpnState.Status.Stopped -> KeyState.Off
        else -> KeyState.Busy
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // The map gives way first on a short phone, so the key and the numbers keep their room.
        val mapHeight = (maxHeight - 48.dp - 170.dp - 118.dp - 72.dp - 34.dp).coerceIn(0.dp, 128.dp).let { if (it < 48.dp) 0.dp else it }
        Column(Modifier.fillMaxSize()) {
            AppHeader("Bridge", { LogoTile() }) {
                HeaderAction(Icons.Plus, stringResource(R.string.add_servers), onAdd)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val adding = status == VpnState.Status.Stopped && !canConnect
                val label = stringResource(
                    when {
                        adding -> R.string.add_servers
                        status == VpnState.Status.Stopped -> R.string.connect
                        status == VpnState.Status.Starting -> R.string.cancel
                        else -> R.string.disconnect
                    },
                )
                ConnectKey(
                    state = keyState,
                    label = label,
                    contentDescription = label,
                    enabled = status != VpnState.Status.Stopping,
                    onClick = if (adding) onAdd else onConnect,
                    side = min(maxHeight, 224.dp).coerceAtLeast(96.dp),
                    icon = if (adding) Icons.Plus else Icons.Power,
                    note = if (status == VpnState.Status.Running) elapsed(model.connectedAt) else null,
                )
            }
            ConnectionCard(model, canConnect, mapHeight)
            Spacer(Modifier.height(10.dp))
            ServerBox(model, onOpenServers)
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** When connected, a blue light comes down from the top of the screen, moves slowly and fades out towards the middle. */
@Composable
fun ConnectedLight(on: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (on) 1f else 0f, tween(700), label = "light")
    val rise by animateFloatAsState(if (on) 0f else -0.45f, tween(1100), label = "rise")
    if (alpha == 0f) return
    val moving = rememberInfiniteTransition(label = "light")
    val a by moving.animateFloat(-1f, 1f, infiniteRepeatable(tween(8_000), RepeatMode.Reverse), label = "a")
    val b by moving.animateFloat(1f, -1f, infiniteRepeatable(tween(11_000), RepeatMode.Reverse), label = "b")
    Canvas(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(0.62f)
            .graphicsLayer {
                this.alpha = alpha
                translationY = rise * size.height
                compositingStrategy = CompositingStrategy.Offscreen
            },
    ) {
        val w = size.width
        val h = size.height
        val first = Offset(w * (0.32f + 0.16f * a), h * 0.04f)
        val second = Offset(w * (0.70f + 0.16f * b), -h * 0.02f)
        drawRect(Brush.radialGradient(listOf(Bridge.primary.copy(alpha = 0.95f), Color.Transparent), first, w * 1.05f))
        drawRect(Brush.radialGradient(listOf(Bridge.brand.copy(alpha = 0.6f), Color.Transparent), second, w * 0.8f))
        drawRect(Brush.verticalGradient(0f to Color.Black, 0.25f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
}

@Composable
private fun LogoTile() {
    Box(Modifier.size(36.dp).clip(ControlShape).background(Bridge.primary), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_notification), null, Modifier.size(26.dp), tint = Bridge.primaryForeground)
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

/** Where traffic leaves, on a map with where the phone is; then the speed. A failed connection is told here too. */
@Composable
private fun ConnectionCard(model: AppModel, canConnect: Boolean, mapHeight: Dp) {
    val health = model.health
    val running = model.running
    val status = model.status
    val error = if (status == VpnState.Status.Stopped) model.vpnError ?: model.localError?.let { stringResource(it) } else null
    BridgeCard(
        modifier = if (error != null) Modifier.border(1.dp, Bridge.destructive.copy(alpha = 0.55f), CardShape) else Modifier,
        padding = PaddingValues(0.dp),
    ) {
        if (mapHeight > 0.dp) {
            WorldMap(
                home = model.homeCountry,
                exitCountry = if (running) health.country else null,
                exitAt = if (running) health.at else null,
                height = mapHeight,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
            )
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                error != null -> {
                    Lead { Icon(Icons.CircleAlert, null, Modifier.size(20.dp), tint = Bridge.destructive) }
                    TitleAndLine(stringResource(R.string.could_not_connect), error, Modifier.weight(1f), lines = 3)
                    Box(Modifier.padding(start = 4.dp)) { IconAction(Icons.X, stringResource(R.string.close), { model.clearError() }) }
                }
                status == VpnState.Status.Stopped -> {
                    Lead { Icon(Icons.Shield, null, Modifier.size(20.dp), tint = Bridge.mutedForeground) }
                    TitleAndLine(stringResource(R.string.status_off), stringResource(if (canConnect) R.string.status_off_hint else R.string.status_off_empty), Modifier.weight(1f))
                }
                status != VpnState.Status.Running -> {
                    Lead { Icon(Icons.LoaderCircle, null, Modifier.size(20.dp), tint = Bridge.brand) }
                    TitleAndLine(
                        stringResource(if (status == VpnState.Status.Starting) R.string.status_starting else R.string.status_stopping),
                        stringResource(model.progress ?: R.string.status_starting_hint),
                        Modifier.weight(1f),
                    )
                }
                health.state == Health.State.Failed -> {
                    Lead { Icon(Icons.CircleAlert, null, Modifier.size(20.dp), tint = Bridge.warning) }
                    Text(stringResource(R.string.not_answering), style = MaterialTheme.typography.bodyMedium, color = Bridge.warning, modifier = Modifier.weight(1f))
                    IconAction(Icons.RefreshCw, stringResource(R.string.health_check_now), { model.checkNow() })
                }
                health.country != null -> ExitDetails(model.warpSelected, health)
                else -> {
                    Lead { Icon(Icons.Globe, null, Modifier.size(20.dp), tint = Bridge.mutedForeground) }
                    Text(stringResource(R.string.exit_finding), style = MaterialTheme.typography.bodyMedium, color = Bridge.mutedForeground, modifier = Modifier.weight(1f))
                }
            }
        }
        HorizontalDivider(color = Bridge.border)
        Meters(model.traffic, running)
    }
}

@Composable
private fun Lead(content: @Composable () -> Unit) {
    Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { content() }
    Spacer(Modifier.width(10.dp))
}

@Composable
private fun TitleAndLine(title: String, line: String, modifier: Modifier = Modifier, lines: Int = 1) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(line, style = MaterialTheme.typography.bodySmall, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ExitDetails(warp: Boolean, health: Health) {
    val country = health.country ?: return
    Lead {
        if (warp) {
            Image(painterResource(R.drawable.ic_cloudflare), null, Modifier.size(width = 26.dp, height = 16.dp))
        } else {
            FlagIcon(country, width = 24.dp)
        }
    }
    TitleAndLine(
        if (warp) "Cloudflare WARP" else countryName(country),
        if (warp) stringResource(R.string.exit_in, countryName(country)) else health.city ?: stringResource(R.string.exit_location),
        Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    Column(horizontalAlignment = Alignment.End) {
        health.ip?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono), maxLines = 1) }
        if (health.delay > 0) {
            Text(stringResource(R.string.delay_ms, health.delay), style = MaterialTheme.typography.labelMedium, color = delayColor(health.delay), maxLines = 1)
        }
    }
}

@Composable
private fun Meters(t: Traffic, running: Boolean) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Meter(Icons.ArrowDown, stringResource(R.string.speed_down), if (running) rate(t.down) else "-", running, Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(40.dp).background(Bridge.border))
        Meter(Icons.ArrowUp, stringResource(R.string.speed_up), if (running) rate(t.up) else "-", running, Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(40.dp).background(Bridge.border))
        Meter(Icons.Activity, stringResource(R.string.used), if (running) "⁦${Formatter.formatShortFileSize(context, t.totalDown + t.totalUp)}⁩" else "-", running, Modifier.weight(1f))
    }
}

@Composable
private fun Meter(icon: ImageVector, label: String, value: String, live: Boolean, modifier: Modifier) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(13.dp), tint = if (live) Bridge.brand else Bridge.mutedForeground)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Bridge.mutedForeground, maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (live) FontWeight.SemiBold else FontWeight.Medium),
            color = if (live) Bridge.foreground else Bridge.mutedForeground,
            maxLines = 1,
        )
    }
}

/** The chosen server, and the way to change it. */
@Composable
private fun ServerBox(model: AppModel, onOpen: () -> Unit) {
    val server = model.servers.firstOrNull { it.id == model.selected }
    val auto = model.autoSelected
    val warp = model.warpSelected
    val shown = server ?: if (!warp && !auto) model.servers.firstOrNull() else null
    BridgeCard(padding = PaddingValues(0.dp), onClick = onOpen) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Lead {
                when {
                    warp -> Image(painterResource(R.drawable.ic_cloudflare), null, Modifier.size(width = 26.dp, height = 16.dp))
                    auto -> Icon(Icons.Zap, null, Modifier.size(20.dp), tint = Bridge.brand)
                    shown != null -> ServerFlag(shown)
                    else -> Icon(Icons.Globe, null, Modifier.size(20.dp), tint = Bridge.mutedForeground)
                }
            }
            when {
                warp -> TitleAndLine(stringResource(R.string.warp_name), "Cloudflare WARP", Modifier.weight(1f))
                auto -> TitleAndLine(
                    stringResource(R.string.auto_name),
                    model.activeServer()?.takeIf { model.running }?.let { stringResource(R.string.auto_now, displayName(it)) } ?: stringResource(R.string.auto_hint_short, model.servers.size),
                    Modifier.weight(1f),
                )
                shown != null -> {
                    TitleAndLine(displayName(shown), sourceLine(model, shown), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Ping(shown, model)
                }
                else -> TitleAndLine(stringResource(R.string.empty_title), stringResource(R.string.tap_to_add), Modifier.weight(1f))
            }
            Spacer(Modifier.width(6.dp))
            DirectionalIcon(Icons.ChevronRight, null, Modifier.size(22.dp))
        }
    }
}

@Composable
private fun sourceLine(model: AppModel, server: Server): String {
    val subscription = model.subscriptions.firstOrNull { it.id == server.subscription } ?: return stringResource(R.string.manual_configs)
    val name = if (subscription.public) stringResource(R.string.public_sub_name) else subscription.name
    val summary = subscription.usage?.let { usageSummary(it) } ?: return name
    return "$name: $summary"
}

/**
 * The world as dots, with where the phone is and where traffic leaves, and an arc between them.
 * Drawn from data in the app: no map service is asked, so no one learns where the phone is.
 */
@Composable
fun WorldMap(home: String?, exitCountry: String?, exitAt: Pair<Double, Double>?, modifier: Modifier = Modifier, height: Dp? = null) {
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
        val b = to?.let { unit(it) } ?: a ?: return@remember null
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
        Canvas(Modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier.aspectRatio(cols / rows))) {
            val t = progress.value
            val zoom = 1f + ((target?.first ?: 1f) - 1f) * t
            val cx = cols / 2 + ((target?.second ?: (cols / 2)) - cols / 2) * t
            val cy = rows / 2 + ((target?.third ?: (rows / 2)) - rows / 2) * t
            val cell = maxOf(size.width / cols, size.height / rows)
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


/** "vless / ws / tls" reads as "VLESS". */
fun protocolOf(kind: String): String = kind.substringBefore(" / ").uppercase()

private const val IRAN_FLAG = "🇮🇷"

/** A server's name without the flag that is already shown beside it; Iran's is never shown. */
fun displayName(server: Server): String {
    val country = server.country
    return server.name.replace(IRAN_FLAG, "").let { name -> country?.let { name.replace(flag(it).ifEmpty { IRAN_FLAG }, "") } ?: name }.trim().ifEmpty { server.name }
}

@Composable
fun ServerFlag(server: Server) {
    val country = server.country
    if (country != null) FlagIcon(country, width = 24.dp) else Icon(Icons.Globe, null, Modifier.size(20.dp), tint = Bridge.mutedForeground)
}

/** How a delay reads: green when quick, yellow when slow, red when very slow. */
fun delayColor(delay: Int): Color = when {
    delay >= 800 -> Bridge.destructive
    delay >= 300 -> Bridge.warning
    else -> Bridge.success
}

/** A server's delay as a plain colored number; a moving placeholder while it is being measured. */
@Composable
fun Ping(server: Server, model: AppModel) {
    val delay = server.delay
    when {
        server.id in model.measuring -> Box(Modifier.size(width = 44.dp, height = 14.dp).clip(CircleShape).background(shimmer()))
        delay == null -> Unit
        delay <= 0 -> Text(stringResource(R.string.delay_failed), style = MaterialTheme.typography.labelMedium, color = Bridge.destructive, maxLines = 1)
        else -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(String.format(LocalConfiguration.current.locales[0], "%d", delay), style = MaterialTheme.typography.titleSmall, color = delayColor(delay), maxLines = 1)
            Text("ms", style = MaterialTheme.typography.labelSmall, color = Bridge.mutedForeground, maxLines = 1)
        }
    }
}

/** The small round mark: the logo on its blue. */
@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    Box(modifier.size(32.dp).clip(ItemShape).background(Bridge.primary), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_notification), null, Modifier.size(20.dp), tint = Bridge.primaryForeground)
    }
}
