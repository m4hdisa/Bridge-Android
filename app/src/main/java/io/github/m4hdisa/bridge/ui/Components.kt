package io.github.m4hdisa.bridge.ui

import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m4hdisa.bridge.R

// Shapes, as in the Windows app: surfaces (cards) 16, things inside them 12, controls 10, badges and chips round.
val CardShape = RoundedCornerShape(16.dp)
val ItemShape = RoundedCornerShape(12.dp)
val ControlShape = RoundedCornerShape(10.dp)

/** A surface: the card color with a thin light border. */
@Composable
fun BridgeCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = Bridge.card,
        border = BorderStroke(1.dp, Bridge.border),
    ) {
        Column(Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding), content = content)
    }
}

/** Every page's header, in the same place: a blue tile with the page's icon, its title, and its actions. */
@Composable
fun AppHeader(title: String, tile: @Composable () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        tile()
        Spacer(Modifier.width(10.dp))
        Text(title, style = HeaderTitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The last icon's own padding lines its glyph up with the edge of the cards.
        Row(Modifier.offset(x = headerEdge()), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

private val HeaderTitle @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 26.sp)

@Composable
private fun headerEdge(): Dp = if (LocalLayoutDirection.current == LayoutDirection.Rtl) (-10).dp else 10.dp

/** The tile at the start of a header. */
@Composable
fun HeaderTile(icon: ImageVector) {
    Box(Modifier.size(36.dp).clip(ControlShape).background(Bridge.primary), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = Bridge.primaryForeground)
    }
}

/** An icon in a header: always the same size and color. */
@Composable
fun HeaderAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, enabled: Boolean = true) =
    IconAction(icon, contentDescription, onClick, tint = Bridge.foreground, enabled = enabled)

/** A filter or source above a list: a pill in a row tall enough for a finger. */
@Composable
fun ChipTab(label: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(52.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(38.dp)
                .clip(CircleShape)
                .background(if (on) Bridge.primary.copy(alpha = 0.2f) else Bridge.card)
                .border(1.dp, if (on) Bridge.brand.copy(alpha = 0.45f) else Bridge.border, CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (on) Bridge.brand else Bridge.mutedForeground
            Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.75f))
            }
        }
    }
}

/** The moving light of a skeleton, while a list or a ping is being loaded. */
@Composable
fun shimmer(base: Color = Bridge.muted, light: Color = Color(0xFF343434)): Brush {
    val x = rememberInfiniteTransition(label = "shimmer").animateFloat(-400f, 800f, infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.LinearEasing)), label = "shimmer").value
    return Brush.linearGradient(listOf(base, light, base), start = Offset(x, 0f), end = Offset(x + 400f, 0f))
}

/** A small button filled with the muted color, or the logo blue for the page's main action. */
@Composable
fun FillButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) =
    ButtonBase(text, icon, onClick, modifier, enabled, if (primary) Bridge.primary else Bridge.muted, if (primary) Bridge.primaryForeground else Bridge.foreground, null, 48.dp, ItemShape)

/** Mirrors a drawing left to right. */
fun Modifier.mirrored(): Modifier = this.scale(scaleX = -1f, scaleY = 1f)

/** An icon that points somewhere (arrows, chevrons): mirrored when the screen reads right to left. */
@Composable
fun DirectionalIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Bridge.mutedForeground) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(icon, contentDescription, modifier.scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f), tint = tint)
}

enum class Tone(val text: Color, val fill: Color, val line: Color) {
    Neutral(Bridge.mutedForeground, Color(0x0DFFFFFF), Bridge.input),
    Success(Bridge.success, Bridge.success.copy(alpha = 0.1f), Bridge.success.copy(alpha = 0.3f)),
    Warning(Bridge.warning, Bridge.warning.copy(alpha = 0.1f), Bridge.warning.copy(alpha = 0.3f)),
    Danger(Bridge.destructive, Bridge.destructive.copy(alpha = 0.1f), Bridge.destructive.copy(alpha = 0.3f)),
    Brand(Bridge.brand, Bridge.brand.copy(alpha = 0.1f), Bridge.brand.copy(alpha = 0.3f)),
}

/** A small rounded label: a protocol, "Xray", a delay. */
@Composable
fun Badge(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(CircleShape)
            .background(tone.fill)
            .border(1.dp, tone.line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = tone.text,
        maxLines = 1,
    )
}

/** How a delay reads: green when quick, yellow when slow, red when it didn't answer. */
@Composable
fun DelayBadge(delay: Int?) {
    when {
        delay == null -> Unit
        delay <= 0 -> Badge(stringResource(R.string.delay_failed), Tone.Danger)
        delay >= 800 -> Badge(stringResource(R.string.delay_ms, delay), Tone.Danger)
        delay >= 300 -> Badge(stringResource(R.string.delay_ms, delay), Tone.Warning)
        else -> Badge(stringResource(R.string.delay_ms, delay), Tone.Success)
    }
}

@Composable
private fun ButtonBase(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    fill: Color,
    ink: Color,
    border: Color?,
    height: Dp,
    shape: androidx.compose.ui.graphics.Shape = ControlShape,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .height(height)
            .scale(if (pressed) 0.98f else 1f)
            .clip(shape)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1)
    }
}

/** The main action: the logo blue. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 40.dp) =
    ButtonBase(text, icon, onClick, modifier, enabled, Bridge.primary, Bridge.primaryForeground, null, height)

/** A second action: a dark button with a frame. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, height: Dp = 40.dp) =
    ButtonBase(text, icon, onClick, modifier, enabled, Color(0x08FFFFFF), Bridge.foreground, Bridge.input, height)

/** An icon on its own: 48 dp to touch, whatever it looks like. */
@Composable
fun IconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Bridge.mutedForeground, enabled: Boolean = true) {
    Box(
        modifier
            .size(48.dp)
            .clip(ItemShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = tint)
    }
}

@Composable
fun BridgeSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Bridge.primaryForeground,
            checkedTrackColor = Bridge.primary,
            checkedBorderColor = Bridge.primary,
            uncheckedThumbColor = Bridge.mutedForeground,
            uncheckedTrackColor = Bridge.muted,
            uncheckedBorderColor = Bridge.input,
        ),
    )
}

/** Choices side by side, the chosen one raised. */
@Composable
fun <T> Segmented(options: List<Triple<T, String, ImageVector>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(ItemShape)
            .background(Bridge.muted)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label, icon) in options) {
            val on = value == selected
            val ink = if (on) Bridge.foreground else Bridge.mutedForeground
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(ControlShape)
                    .background(if (on) Bridge.sidebarAccent else Color.Transparent)
                    .clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = ink)
                Spacer(Modifier.width(7.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A round marker for a list where one thing is chosen. */
@Composable
fun RadioMark(on: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (on) Bridge.brand else Bridge.input, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(Bridge.brand))
    }
}

/** A small round light: green connected, blue connecting, gray off. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val alpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "dot")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot").value
    } else {
        1f
    }
    Box(modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(color))
}

/** A row inside a card: an icon, a title with a line under it, and something on the other side. */
@Composable
fun SettingRow(icon: ImageVector?, title: String, subtitle: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(ItemShape).clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = Bridge.mutedForeground)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

enum class KeyState { Off, Busy, On }

/**
 * The connect button in the Windows app's Classic style, sized for a phone: a bezel ring with a
 * small gap, and the button, a disc lit from above. Connecting: a thin arc runs around the
 * bezel. On: the disc turns the logo blue with a soft light slowly turning inside it, and a
 * still blue light behind it all.
 */
@Composable
fun ConnectKey(
    state: KeyState,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    side: Dp = 224.dp,
    icon: ImageVector = Icons.Power,
    note: String? = null,
) {
    // Everything scales with the room the page has.
    val k = side.value / 224f
    val on = state == KeyState.On
    val busy = state == KeyState.Busy
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, tween(500), label = "lit")
    val glow by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, tween(700), label = "glow")
    val turning = rememberInfiniteTransition(label = "key")
    val arc = turning.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "arc").value
    val swirl = turning.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = androidx.compose.animation.core.LinearEasing)), label = "swirl").value
    val deep = Color(0xFF0230B0)
    Box(
        modifier
            .size(side)
            .drawBehind {
                // The still blue light behind it, on only; it fades to nothing well inside the box.
                if (glow > 0f) drawCircle(Brush.radialGradient(listOf(Bridge.primary.copy(alpha = 0.3f * glow), Color.Transparent), radius = size.minDimension / 2), radius = size.minDimension / 2)
            },
        contentAlignment = Alignment.Center,
    ) {
        // The bezel: a ring with a small gap around the button.
        Box(
            Modifier
                .size(200.dp * k)
                .clip(CircleShape)
                .background(if (on) Bridge.primary.copy(alpha = 0.10f) else Color(0x99141414))
                .border(1.dp, if (on) Bridge.brand.copy(alpha = 0.3f) else Color(0x12FFFFFF), CircleShape),
        )
        if (busy) {
            androidx.compose.foundation.Canvas(Modifier.size(200.dp * k)) {
                val w = 1.5.dp.toPx()
                drawArc(
                    Bridge.brand,
                    startAngle = arc,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
        Column(
            Modifier
                .size(164.dp * k)
                .scale(if (pressed) 0.97f else 1f)
                .shadow(if (on) 18.dp else 10.dp, CircleShape, ambientColor = if (on) Bridge.primary else Color.Black, spotColor = if (on) Bridge.primary else Color.Black)
                .clip(CircleShape)
                .drawBehind {
                    // The disc, lit from above: dark when off, the logo blue when on.
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF2B2B2B), Color(0xFF1A1A1A))))
                    if (lit > 0f) {
                        drawRect(Brush.verticalGradient(0f to Color(0xFF3D6BF5), 0.45f to Bridge.primary, 1f to deep), alpha = lit)
                        // A soft light slowly turning inside it.
                        rotate(swirl) {
                            drawCircle(
                                Brush.sweepGradient(
                                    0f to Color.Transparent,
                                    0.19f to Color(0x80A9CCFF),
                                    0.42f to Color.Transparent,
                                    0.58f to Color.Transparent,
                                    0.78f to Color(0x8C5B3CE6),
                                    1f to Color.Transparent,
                                ),
                                radius = size.minDimension * 0.75f,
                                alpha = 0.55f * lit,
                            )
                        }
                    }
                    // A highlight across the top, fading out before any edge.
                    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = if (on) 0.22f else 0.08f), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 0.45f))
                }
                .border(1.dp, Color.White.copy(alpha = if (on) 0.18f else 0.08f), CircleShape)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                .alpha(if (enabled) 1f else 0.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val ink = when {
                on -> Color.White
                busy -> Bridge.brand
                else -> Bridge.mutedForeground
            }
            Icon(icon, contentDescription, Modifier.size(46.dp * k), tint = ink)
            Spacer(Modifier.height(6.dp * k))
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = (15 * k).sp, lineHeight = (19 * k).sp), color = ink, maxLines = 1)
            if (note != null) {
                Spacer(Modifier.height(3.dp * k))
                Text(note, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono, fontSize = (13 * k).sp, lineHeight = (16 * k).sp), color = ink.copy(alpha = 0.85f), maxLines = 1)
            }
        }
    }
}

/** A thin line of progress under the top of a page while something runs. */
@Composable
fun BusyBar(text: String, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "busy").animateFloat(0f, 1f, infiniteRepeatable(tween(1100)), label = "busy").value
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(Bridge.muted)
                .drawBehind {
                    val w = size.width * 0.3f
                    drawRect(Bridge.brand, topLeft = Offset((size.width + w) * t - w, 0f), size = Size(w, size.height))
                },
        )
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}
