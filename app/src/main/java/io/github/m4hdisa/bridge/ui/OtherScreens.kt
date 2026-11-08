package io.github.m4hdisa.bridge.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.LocaleListCompat
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.BuildConfig
import io.github.m4hdisa.bridge.InstalledApp
import io.github.m4hdisa.bridge.R

// ---------- Apps ----------

/** Which apps use the VPN. */
fun LazyListScope.appsPage(model: AppModel, filter: String, onFilter: (String) -> Unit) {
    item {
        PageHeader(stringResource(R.string.apps_title), stringResource(R.string.apps_subtitle))
        Spacer(Modifier.height(16.dp))
        BridgeCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Split, null, Modifier.size(18.dp).padding(top = 2.dp), tint = Bridge.mutedForeground)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.per_app_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(if (model.perApp) R.string.per_app_on_hint else R.string.per_app_off_hint), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(12.dp))
                BridgeSwitch(model.perApp, { model.changePerApp(it) })
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (!model.perApp) return
    val apps = model.installedApps
    item {
        SearchField(filter, onFilter, stringResource(R.string.search_apps))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.apps_chosen, model.chosenApps.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
    }
    if (apps == null) {
        item { Text(stringResource(R.string.loading_apps), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
        return
    }
    val chosen = model.chosenApps
    val needle = filter.trim().lowercase()
    val shown = apps
        .filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.packageName.contains(needle) }
        .sortedBy { if (it.packageName in chosen) 0 else 1 }
    items(shown, key = { it.packageName }) { app -> AppRow(app, app.packageName in chosen) { model.toggleApp(app.packageName) } }
}

@Composable
private fun AppRow(app: InstalledApp, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ItemShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = remember(app.packageName) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
        if (icon != null) Image(icon, null, Modifier.size(36.dp).clip(ControlShape)) else Box(Modifier.size(36.dp).clip(ControlShape).background(Bridge.muted))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(
            checked = on,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Bridge.primary, uncheckedColor = Bridge.input, checkmarkColor = Bridge.primaryForeground),
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(ControlShape)
            .background(Bridge.card)
            .border(1.dp, Bridge.input, ControlShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, null, Modifier.size(16.dp), tint = Bridge.mutedForeground)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Bridge.mutedForeground)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                cursorBrush = SolidColor(Bridge.brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------- Logs ----------

/** What sing-box and Xray wrote during the last connection; errors in red, warnings in yellow. */
fun LazyListScope.logsPage(log: String) {
    item {
        val context = LocalContext.current
        PageHeader(stringResource(R.string.logs_title), stringResource(R.string.logs_subtitle))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton(stringResource(R.string.copy), { copy(context, log) }, icon = Icons.Copy, enabled = log.isNotEmpty())
            OutlineButton(stringResource(R.string.share), {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log)
                context.startActivity(Intent.createChooser(send, null))
            }, icon = Icons.Share2, enabled = log.isNotEmpty())
        }
        Spacer(Modifier.height(14.dp))
        BridgeCard(padding = PaddingValues(12.dp)) {
            if (log.isEmpty()) {
                Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(colored(log), style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono, fontSize = 11.sp, lineHeight = 16.sp, color = Bridge.foreground.copy(alpha = 0.85f)), softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private val ANSI = Regex("\u001B\\[[0-9;]*m")

private fun colored(log: String): AnnotatedString = buildAnnotatedString {
    for (raw in log.lines().takeLast(800)) {
        val line = raw.replace(ANSI, "")
        val color = when {
            line.contains("ERROR") || line.contains("FATAL") || line.contains("[Error]") -> Bridge.destructive
            line.contains("WARN") || line.contains("[Warning]") -> Bridge.warning
            else -> null
        }
        if (color != null) withStyle(SpanStyle(color = color)) { append(line) } else append(line)
        append('\n')
    }
}

// ---------- Settings ----------

fun LazyListScope.settingsPage(model: AppModel) {
    item {
        PageHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle))
        Spacer(Modifier.height(16.dp))
        BridgeCard {
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LanguageRow()
        }
        Spacer(Modifier.height(12.dp))
        BridgeCard {
            Text(stringResource(R.string.mode), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Segmented(
                options = listOf(
                    Triple(false, stringResource(R.string.mode_full), Icons.Globe),
                    Triple(true, stringResource(R.string.mode_per_app), Icons.Split),
                ),
                selected = model.perApp,
                onSelect = { model.changePerApp(it) },
            )
            Text(
                stringResource(if (model.perApp) R.string.mode_per_app_hint else R.string.mode_full_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        BridgeCard {
            Text(stringResource(R.string.connection), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SettingRow(Icons.MapPin, stringResource(R.string.bypass_iran), stringResource(R.string.bypass_iran_hint)) {
                BridgeSwitch(model.bypassIran, { model.changeBypassIran(it) })
            }
            HorizontalDivider(color = Bridge.border, modifier = Modifier.padding(vertical = 4.dp))
            SettingRow(Icons.Zap, stringResource(R.string.fake_ip), stringResource(R.string.fake_ip_hint)) {
                BridgeSwitch(model.fakeIp, { model.changeFakeIp(it) })
            }
            HorizontalDivider(color = Bridge.border, modifier = Modifier.padding(vertical = 4.dp))
            SettingRow(Icons.ScrollText, stringResource(R.string.detailed_log), stringResource(R.string.detailed_log_hint)) {
                BridgeSwitch(model.detailedLog, { model.changeDetailedLog(it) })
            }
        }
        Spacer(Modifier.height(12.dp))
        BridgeCard {
            Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SettingRow(Icons.Info, stringResource(R.string.version, BuildConfig.VERSION_NAME), stringResource(R.string.credits))
            HorizontalDivider(color = Bridge.border, modifier = Modifier.padding(vertical = 4.dp))
            SettingRow(Icons.ShieldCheck, stringResource(R.string.privacy_title), stringResource(R.string.privacy_hint))
        }
    }
}

@Composable
private fun LanguageRow() {
    var open by remember { mutableStateOf(false) }
    val tags = listOf("", "fa", "en")
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val chosen = tags.firstOrNull { it.isNotEmpty() && current.startsWith(it) } ?: ""
    val labels = mapOf("" to stringResource(R.string.language_system), "fa" to stringResource(R.string.language_fa), "en" to stringResource(R.string.language_en))
    SettingRow(Icons.Languages, stringResource(R.string.language), stringResource(R.string.language_hint)) {
        Box {
            OutlineButton(labels.getValue(chosen), { open = true }, icon = Icons.ChevronDown, height = 36.dp)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Bridge.popover) {
                for (tag in tags) {
                    DropdownMenuItem(text = { Text(labels.getValue(tag)) }, trailingIcon = { if (tag == chosen) Icon(Icons.Check, null, Modifier.size(16.dp), tint = Bridge.brand) }, onClick = {
                        open = false
                        AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                    })
                }
            }
        }
    }
}
