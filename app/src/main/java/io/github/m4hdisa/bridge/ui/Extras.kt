package io.github.m4hdisa.bridge.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.m4hdisa.bridge.R
import io.github.m4hdisa.bridge.Usage
import java.util.Locale

/** Traffic left, as the panel told it: from the total, or as a number on its own. */
fun Usage.left(): Long? = when {
    total > 0 -> (total - used).coerceAtLeast(0)
    remaining > 0 -> remaining
    else -> null
}

/** Whole days until the subscription ends, or null when it doesn't. */
fun Usage.daysLeft(): Int? = if (expire > 0) ((expire * 1000 - System.currentTimeMillis()) / DateUtils.DAY_IN_MILLIS).toInt() else null

/** "38 GB left, 17 days", for one line. */
@Composable
fun usageSummary(usage: Usage): String? {
    val context = LocalContext.current
    val left = usage.left()?.let { Formatter.formatShortFileSize(context, it) }
    val days = usage.daysLeft()?.coerceAtLeast(0)
    return when {
        left != null && days != null -> stringResource(R.string.usage_short, left, days)
        left != null -> stringResource(R.string.stat_traffic_left, left)
        else -> null
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
 * assets/flags; Iran's as plain stripes (IranStripes); a globe when there is none.
 */
@Composable
fun FlagIcon(code: String?, modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 20.dp) {
    val context = LocalContext.current
    val bitmap = remember(code) { code?.let { Flags.get(context, it) } }
    val size = Modifier.size(width = width, height = width * 2 / 3)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
    if (code == IRAN) {
        IranStripes(modifier.then(size).clip(shape).border(1.dp, Color.White.copy(alpha = 0.1f), shape))
        return
    }
    if (bitmap == null) {
        Icon(Icons.Globe, null, modifier.then(size), tint = Bridge.mutedForeground)
        return
    }
    Image(
        bitmap,
        code,
        modifier.then(size).clip(shape).border(1.dp, Color.White.copy(alpha = 0.1f), shape),
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
    )
}

private const val IRAN = "IR"

/** Iran's flag as plain green, white and red stripes, without the emblem and the writing, as on Windows. */
@Composable
private fun IranStripes(modifier: Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF239F40)))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.White))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFDA0000)))
    }
}

/** A Free WARP location's name: Fastest, Any country, or the country. */
@Composable
fun warpLocationName(location: String): String = when (location) {
    io.github.m4hdisa.bridge.Warp.FASTEST -> stringResource(R.string.warp_fastest)
    io.github.m4hdisa.bridge.Warp.ANY -> stringResource(R.string.warp_any)
    else -> countryName(location)
}

/** What Free WARP's mark shows: where it really leaves from while connected through it, else the chosen location. */
fun warpShownCode(model: io.github.m4hdisa.bridge.AppModel): String? =
    model.health.country?.takeIf { model.throughWarp } ?: warpMarkCode(model.warpLocation)

/** RoundFlag's stand-in code for Fastest: a lightning bolt instead of a flag. */
const val FASTEST_MARK = "fastest"

/** What RoundFlag shows for a Free WARP location: the bolt for Fastest, a globe for any country, else the country. */
fun warpMarkCode(location: String): String? = when (location) {
    io.github.m4hdisa.bridge.Warp.FASTEST -> FASTEST_MARK
    io.github.m4hdisa.bridge.Warp.ANY -> null
    else -> location
}

/** A country's flag cut to a circle; a globe without a country, a lightning bolt for FASTEST_MARK. */
@Composable
fun RoundFlag(code: String?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val circle = modifier.size(size).clip(CircleShape)
    when {
        code == FASTEST_MARK -> Box(circle.background(Color(0xFF0F2A7A)), contentAlignment = Alignment.Center) {
            Icon(Icons.Zap, null, Modifier.size(size * 0.65f), tint = Color(0xFF9FC0FF))
        }
        code == IRAN -> IranStripes(circle.border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape))
        else -> {
            val bitmap = remember(code) { code?.let { Flags.get(context, it) } }
            if (bitmap == null) {
                Box(circle.background(Bridge.muted), contentAlignment = Alignment.Center) {
                    Icon(Icons.Globe, null, Modifier.size(size * 0.68f), tint = Bridge.mutedForeground)
                }
            } else {
                Image(bitmap, code, circle.border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
        }
    }
}

/**
 * Free WARP's mark: Cloudflare's logo with a round flag of where it leaves from on its corner.
 * `ring` is the color behind it, so the flag stands apart from the logo. Logos aren't mirrored.
 */
@Composable
fun WarpMark(code: String?, ring: Color, large: Boolean = false) {
    val flag = if (large) 18.dp else 15.dp
    Box(Modifier.size(width = if (large) 38.dp else 30.dp, height = if (large) 30.dp else 24.dp)) {
        Image(
            painterResource(R.drawable.ic_cloudflare),
            null,
            Modifier.align(AbsoluteAlignment.TopLeft).padding(top = 1.dp).size(width = if (large) 30.dp else 24.dp, height = if (large) 17.5.dp else 14.dp),
        )
        Box(Modifier.align(AbsoluteAlignment.BottomRight).size(flag + 4.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
            RoundFlag(code, flag)
        }
    }
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
