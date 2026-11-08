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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
