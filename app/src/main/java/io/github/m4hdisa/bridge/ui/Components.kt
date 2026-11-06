package io.github.m4hdisa.bridge.ui

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

/** A page's title and what it is for. */
@Composable
fun PageHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Bridge.mutedForeground)
        }
    }
}

/** A small label above a group, with an optional link on the other side. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Bridge.mutedForeground, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

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
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .height(height)
            .scale(if (pressed) 0.98f else 1f)
            .clip(ControlShape)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, ControlShape) else Modifier)
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

/** An icon on its own, for small actions on a card. */
@Composable
fun IconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Bridge.mutedForeground, enabled: Boolean = true) {
    Box(
        modifier
            .size(36.dp)
            .clip(ControlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(18.dp), tint = tint)
    }
}

@Composable
fun BridgeSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
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

/** Choices side by side in a frame; the chosen one filled with the logo blue (the dashboard's Mode). */
@Composable
fun <T> Segmented(options: List<Triple<T, String, ImageVector>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Bridge.card)
            .border(1.dp, Bridge.border, CardShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label, icon) in options) {
            val on = value == selected
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(ItemShape)
                    .background(if (on) Bridge.primary else Color.Transparent)
                    .clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (on) Bridge.primaryForeground else Bridge.mutedForeground)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Bridge.primaryForeground else Bridge.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * The connect button, built like a key on a car's dashboard (the Windows app's default): a round
 * key set in a dark frame, with a small light above the icon. Off: the light is dark.
 * Connecting: it blinks blue. On: it glows green, and the key stays a little pressed in.
 */
@Composable
fun ConnectKey(state: KeyState, label: String, contentDescription: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = state == KeyState.On
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val blink = rememberInfiniteTransition(label = "key").animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "key").value
    Box(
        modifier
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF161616), Color(0xFF0B0B0B))))
            .border(1.dp, Color(0x0AFFFFFF), CircleShape)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .offset(y = if (pressed) 2.dp else if (on) 1.dp else 0.dp)
                .size(176.dp)
                .shadow(if (on || pressed) 4.dp else 14.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(Brush.verticalGradient(0f to Color(0xFF2E2E2E), 0.55f to Color(0xFF1D1D1D), 1f to Color(0xFF161616)))
                .border(1.dp, Color(if (on) 0x14FFFFFF else 0x1FFFFFFF), CircleShape)
                .drawBehind {
                    // A soft sheen across the top, like light on a plastic key; it fades out before any edge.
                    drawRect(Brush.radialGradient(listOf(Color(0x17FFFFFF), Color.Transparent), center = Offset(size.width / 2, 0f), radius = size.width * 0.38f))
                }
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                .alpha(if (enabled) 1f else 0.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The indicator light.
            val light = when (state) {
                KeyState.On -> Bridge.lightOn
                KeyState.Busy -> Bridge.brand.copy(alpha = blink)
                KeyState.Off -> Color(0x1FFFFFFF)
            }
            Box(
                Modifier
                    .size(width = 28.dp, height = 6.dp)
                    .drawBehind {
                        if (state != KeyState.Off) {
                            val glow = if (on) Bridge.lightOn else Bridge.brand.copy(alpha = blink)
                            // A round glow that fades to nothing at its edge, so it has no outline.
                            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * 0.45f), Color.Transparent), radius = size.width * 0.9f), radius = size.width * 0.9f)
                        }
                    }
                    .clip(CircleShape)
                    .background(light),
            )
            Spacer(Modifier.height(12.dp))
            val ink = if (on) Bridge.foreground else Bridge.mutedForeground
            Icon(Icons.Power, contentDescription, Modifier.size(40.dp), tint = ink)
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = ink)
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
