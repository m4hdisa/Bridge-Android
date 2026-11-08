package io.github.m4hdisa.bridge.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.R
import io.github.m4hdisa.bridge.Subscription
import io.github.m4hdisa.bridge.Usage
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import io.github.m4hdisa.bridge.Warp
import java.util.Locale

/** The update button: it spins while that update runs, and can't be pressed again meanwhile. */
@Composable
fun RefreshAction(description: String, spinning: Boolean, onClick: () -> Unit) {
    val turn = if (spinning) {
        rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin").value
    } else {
        0f
    }
    Box(
        Modifier.size(36.dp).clip(ControlShape).clickable(enabled = !spinning, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.RefreshCw, description, Modifier.size(18.dp).rotate(turn), tint = if (spinning) Bridge.brand else Bridge.mutedForeground)
    }
}

/** Traffic left, as the panel told it: from the total, or as a number on its own. */
fun Usage.left(): Long? = when {
    total > 0 -> (total - used).coerceAtLeast(0)
    remaining > 0 -> remaining
    else -> null
}

/** Free WARP on the Servers page: no config needed, a way through is found when connecting. */
@Composable
fun WarpCard(model: AppModel) {
    val selected = model.warpSelected
    val available = model.warpAvailable
    BridgeCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(ItemShape).background(Bridge.muted), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_cloudflare), null, Modifier.size(width = 24.dp, height = 14.dp))
            }
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.warp_name), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Badge(stringResource(R.string.warp_badge), Tone.Success)
            }
            if (selected) {
                Badge(stringResource(R.string.warp_in_use), Tone.Brand)
            } else {
                PrimaryButton(stringResource(R.string.warp_use), { model.select(Warp.ID) }, enabled = available, height = 34.dp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(if (available) R.string.warp_hint else R.string.warp_unavailable), style = MaterialTheme.typography.bodySmall)
        if (!available) return@BridgeCard
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Bridge.border)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.warp_method), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        val choices = listOf(
            Warp.Choice.Auto to R.string.warp_auto,
            Warp.Choice.Foreign to R.string.warp_foreign,
            Warp.Choice.Psiphon to R.string.warp_psiphon,
        )
        for ((choice, label) in choices) {
            Row(
                Modifier.fillMaxWidth().clip(ItemShape).clickable { model.changeWarpChoice(choice) }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioMark(model.warpChoice == choice)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (choice == Warp.Choice.Psiphon && model.warpChoice == Warp.Choice.Psiphon) RegionPicker(model)
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

        // Saved configs, as on Windows: found once, then a tap connects in seconds.
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Bridge.border)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.warp_saved_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (model.running) R.string.warp_find_disconnect else R.string.warp_find_hint), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            OutlineButton(
                stringResource(if (model.findingWarp) R.string.warp_finding else R.string.warp_find),
                { model.findWarpConfigs() },
                icon = Icons.Search,
                enabled = !model.findingWarp && model.status == io.github.m4hdisa.bridge.VpnState.Status.Stopped,
                height = 34.dp,
            )
        }
        if (model.warpConfigs.isNotEmpty()) Spacer(Modifier.height(6.dp))
        for (config in model.warpConfigs) WarpConfigRow(model, config, removable = true)
    }
}

/** A saved WARP config: its method, gateway, exit and speed; a tap picks it. */
@Composable
fun WarpConfigRow(model: AppModel, config: io.github.m4hdisa.bridge.WarpConfig, removable: Boolean = false) {
    val id = Warp.selectionOf(config)
    val on = model.selected == id
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ItemShape)
            .background(if (on) Bridge.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { model.select(id) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(on)
        Spacer(Modifier.width(10.dp))
        FlagIcon(config.exit, width = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.warp_config_name, config.method.label), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(config.exit?.let { countryName(it) }, config.gateway).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        config.down?.let {
            Spacer(Modifier.width(6.dp))
            Text("↓ " + stringResource(R.string.speed_mbps, mbpsText(it)), style = MaterialTheme.typography.labelSmall, color = Bridge.mutedForeground)
        }
        if (removable) IconAction(Icons.Trash2, stringResource(R.string.remove), { model.removeWarpConfig(config) })
    }
}

@Composable
private fun RegionPicker(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val region = model.psiphonRegion
    Box {
        Row(
            Modifier.clip(ControlShape).background(Bridge.muted).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (region != null) {
                FlagIcon(region, width = 16.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(region ?: stringResource(R.string.warp_region_any), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.ChevronDown, stringResource(R.string.warp_region), Modifier.size(14.dp), tint = Bridge.mutedForeground)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Bridge.popover) {
            DropdownMenuItem(text = { Text(stringResource(R.string.warp_region_any)) }, onClick = {
                open = false
                model.changePsiphonRegion(null)
            })
            for (code in Warp.PSIPHON_REGIONS) {
                DropdownMenuItem(text = { Text(code) }, leadingIcon = { FlagIcon(code, width = 18.dp) }, onClick = {
                    open = false
                    model.changePsiphonRegion(code)
                })
            }
        }
    }
}

/** The dashboard's speed: now, in all since connecting, and a speed test. */
@Composable
fun SpeedCard(model: AppModel) {
    val context = LocalContext.current
    val t = model.traffic
    val speed = model.speed
    BridgeCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Rate(Icons.ArrowDown, stringResource(R.string.speed_down), rate(t.down), Modifier.weight(1f))
            Rate(Icons.ArrowUp, stringResource(R.string.speed_up), rate(t.up), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.speed_total, Formatter.formatShortFileSize(context, t.totalDown), Formatter.formatShortFileSize(context, t.totalUp)),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlineButton(
                stringResource(if (speed.running) R.string.speed_testing else R.string.speed_test),
                { model.testSpeed() },
                icon = Icons.Gauge,
                enabled = !speed.running,
                height = 34.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    speed.failed -> Text(stringResource(R.string.speed_failed), style = MaterialTheme.typography.bodySmall, color = Bridge.destructive)
                    speed.down != null || speed.up != null -> {
                        speed.down?.let { Text("↓ " + stringResource(R.string.speed_mbps, mbps(it)), style = MaterialTheme.typography.titleSmall) }
                        speed.up?.let { Text("↑ " + stringResource(R.string.speed_mbps, mbps(it)), style = MaterialTheme.typography.titleSmall) }
                    }
                    else -> Text(stringResource(R.string.speed_test_hint), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Rate(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier) {
    Row(modifier.clip(ItemShape).background(Bridge.muted).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = Bridge.brand)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** Bytes a second as people read speeds: bits. Kept left to right inside Persian text. */
fun rate(bytesPerSecond: Long): String {
    val bits = bytesPerSecond * 8.0
    val text = when {
        bits >= 1e6 -> String.format(Locale.US, "%.1f Mbps", bits / 1e6)
        bits >= 1e3 -> String.format(Locale.US, "%.0f Kbps", bits / 1e3)
        else -> String.format(Locale.US, "%.0f bps", bits)
    }
    return "⁦$text⁩"
}

private fun mbps(value: Double): String = String.format(Locale.US, if (value >= 10) "%.0f" else "%.1f", value)

/** A subscription's traffic and end date, when its panel reports them. */
@Composable
fun UsageStats(subscription: Subscription, modifier: Modifier = Modifier) {
    val usage = subscription.usage ?: return
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val used = Formatter.formatShortFileSize(context, usage.used)
        val traffic = when {
            usage.total > 0 -> stringResource(R.string.stat_traffic_of, used, Formatter.formatShortFileSize(context, usage.total))
            usage.remaining > 0 -> stringResource(R.string.stat_traffic_left, Formatter.formatShortFileSize(context, usage.remaining))
            else -> used
        }
        val note = usage.left()?.takeIf { usage.total > 0 }?.let { stringResource(R.string.stat_traffic_left, Formatter.formatShortFileSize(context, it)) }
            ?: if (usage.remaining > 0) "" else stringResource(R.string.stat_unlimited)
        UsageStat(stringResource(R.string.stat_traffic), traffic, note, if (usage.total > 0 && usage.used >= usage.total) Bridge.destructive else null, Modifier.weight(1f))
        val ends = usage.expire * 1000
        val now = System.currentTimeMillis()
        val (value, endNote) = when {
            usage.expire <= 0 -> stringResource(R.string.stat_no_end) to ""
            ends <= now -> stringResource(R.string.stat_ended) to ""
            else -> DateUtils.formatDateTime(context, ends, DateUtils.FORMAT_SHOW_DATE) to
                ((ends - now) / DateUtils.DAY_IN_MILLIS).toInt().let { days -> pluralStringResource(R.plurals.stat_days_left, days, days) }
        }
        UsageStat(stringResource(R.string.stat_ends), value, endNote, if (usage.expire > 0 && ends - now < 3 * DateUtils.DAY_IN_MILLIS) Bridge.warning else null, Modifier.weight(1f))
    }
}

@Composable
private fun UsageStat(label: String, value: String, note: String, color: Color?, modifier: Modifier) {
    Column(modifier.clip(ItemShape).background(Bridge.muted).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = color ?: Bridge.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A share link as a QR code, black on white so every scanner reads it. */
@Composable
fun QrDialog(text: String, title: String, onDismiss: () -> Unit) {
    val bitmap = remember(text) { qrBitmap(text, 720) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bridge.popover,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bitmap != null) {
                    Box(Modifier.clip(ItemShape).background(Color.White).padding(12.dp)) {
                        Image(bitmap.asImageBitmap(), null, Modifier.size(240.dp), filterQuality = FilterQuality.None)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.qr_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = Bridge.brand) } },
    )
}

private fun qrBitmap(text: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()

/**
 * A country's flag, the same pictures the Windows app uses (country-flag-icons), drawn from
 * assets/flags; a globe when there is none. Iran's is never shown.
 */
@Composable
fun FlagIcon(code: String?, modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 20.dp) {
    val context = LocalContext.current
    val bitmap = remember(code) { code?.let { Flags.get(context, it) } }
    val size = Modifier.size(width = width, height = width * 2 / 3)
    if (bitmap == null) {
        Icon(Icons.Globe, null, modifier.then(size), tint = Bridge.mutedForeground)
        return
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
    Image(
        bitmap,
        code,
        modifier.then(size).clip(shape).border(1.dp, Color.White.copy(alpha = 0.1f), shape),
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
    )
}

private object Flags {
    private val cache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()

    fun get(context: android.content.Context, code: String): androidx.compose.ui.graphics.ImageBitmap? = synchronized(cache) {
        cache.getOrPut(code) {
            if (code == "IR" || !Regex("^[A-Z]{2}$").matches(code)) return@getOrPut null
            runCatching {
                val svg = context.assets.open("flags/$code.svg").use { com.caverock.androidsvg.SVG.getFromInputStream(it) }
                val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(android.graphics.Canvas(bitmap), android.graphics.RectF(0f, 0f, 96f, 64f))
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }
}

/** A country's flag emoji, for plain text only (the screens use FlagIcon). Iran's is never shown. */
fun flag(country: String): String {
    if (country.length != 2 || !country.all { it in 'A'..'Z' } || country == "IR") return ""
    return country.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}
