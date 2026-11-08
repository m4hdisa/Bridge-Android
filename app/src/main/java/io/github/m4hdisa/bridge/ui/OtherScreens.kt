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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.LocaleListCompat
import io.github.m4hdisa.bridge.AppModel
import io.github.m4hdisa.bridge.BuildConfig
import io.github.m4hdisa.bridge.InstalledApp
import io.github.m4hdisa.bridge.R
import kotlinx.coroutines.launch

// ---------- Apps ----------

/** Which apps use the VPN; opened from Settings. */
@Composable
fun AppsPage(model: AppModel, filter: String, onFilter: (String) -> Unit, onBack: () -> Unit) {
    val apps = model.installedApps
    val chosen = model.chosenApps
    val needle = filter.trim().lowercase()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            // The arrow's own padding hangs outside, so its drawing lines up with the cards.
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Box(Modifier.offset(x = if (rtl) 12.dp else (-12).dp)) {
                BackAction(onBack)
            }
            Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 26.sp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                BridgeCard(padding = PaddingValues(0.dp)) {
                    ToggleRow(Icons.Split, stringResource(R.string.per_app_title), stringResource(if (model.perApp) R.string.per_app_on_hint else R.string.per_app_off_hint), model.perApp, { model.changePerApp(it) })
                }
            }
            if (!model.perApp) return@LazyColumn
            item {
                Spacer(Modifier.height(10.dp))
                SearchField(filter, onFilter, stringResource(R.string.search_apps))
                Text(stringResource(R.string.apps_chosen, chosen.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 4.dp))
            }
            if (apps == null) {
                item { Text(stringResource(R.string.loading_apps), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
            } else {
                val shown = apps
                    .filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.packageName.contains(needle) }
                    .sortedBy { if (it.packageName in chosen) 0 else 1 }
                items(shown, key = { it.packageName }) { app -> AppRow(app, app.packageName in chosen) { model.toggleApp(app.packageName) } }
            }
        }
    }
}

@Composable
private fun BackAction(onBack: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The chevron points forward, so backward is its mirror in left-to-right text.
    Box(Modifier.size(48.dp).clip(ItemShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
        Icon(Icons.ChevronRight, stringResource(R.string.back), Modifier.size(22.dp).then(if (rtl) Modifier else Modifier.mirrored()), tint = Bridge.foreground)
    }
}

@Composable
private fun AppRow(app: InstalledApp, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(ItemShape)
            .toggleable(on, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = remember(app.packageName) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
        if (icon != null) Image(icon, null, Modifier.size(38.dp).clip(ControlShape)) else Box(Modifier.size(38.dp).clip(ControlShape).background(Bridge.muted))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono, fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(
            checked = on,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Bridge.primary, uncheckedColor = Bridge.mutedForeground, checkmarkColor = Bridge.primaryForeground),
        )
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, focus: Boolean = false) {
    val requester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(ItemShape)
            .background(Bridge.card)
            .border(1.dp, Bridge.input, ItemShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, null, Modifier.size(18.dp), tint = Bridge.mutedForeground)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Bridge.mutedForeground, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                cursorBrush = SolidColor(Bridge.brand),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
        }
    }
}

// ---------- Logs ----------

private val ANSI = Regex("\u001B\\[[0-9;]*m")

private enum class Level { Info, Warning, Error }

private data class LogLine(val text: String, val level: Level)

private fun logLines(log: String): List<LogLine> = log.lines().takeLast(800).map { it.replace(ANSI, "") }.filter { it.isNotBlank() }.map { line ->
    LogLine(
        line,
        when {
            line.contains("ERROR") || line.contains("FATAL") || line.contains("[Error]") -> Level.Error
            line.contains("WARN") || line.contains("[Warning]") -> Level.Warning
            else -> Level.Info
        },
    )
}

/** What sing-box, Xray and Free WARP wrote during the last connection, following the end unless you scroll up. */
@Composable
fun LogsPage(log: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember(log) { logLines(log) }
    var filter by remember { mutableStateOf<Level?>(null) }
    val shown = remember(lines, filter) { if (filter == null) lines else lines.filter { it.level == filter } }
    val list = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) return@LaunchedEffect
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        following = last == null || last.index >= info.totalItemsCount - 1
    }
    LaunchedEffect(shown.size, filter) { if (following && shown.isNotEmpty()) list.scrollToItem(shown.lastIndex) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        AppHeader(stringResource(R.string.logs_title), { HeaderTile(Icons.ScrollFill) }) {
            HeaderAction(Icons.Copy, stringResource(R.string.copy), { copy(context, log) }, enabled = log.isNotEmpty())
            HeaderAction(Icons.Share2, stringResource(R.string.share), {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log)
                context.startActivity(Intent.createChooser(send, null))
            }, enabled = log.isNotEmpty())
        }
        Spacer(Modifier.height(10.dp))
        if (lines.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipTab(stringResource(R.string.filter_all), lines.size, filter == null, { filter = null })
                ChipTab(stringResource(R.string.filter_errors), lines.count { it.level == Level.Error }, filter == Level.Error, { filter = Level.Error })
                ChipTab(stringResource(R.string.filter_warnings), lines.count { it.level == Level.Warning }, filter == Level.Warning, { filter = Level.Warning })
            }
        }
        BridgeCard(Modifier.weight(1f), padding = PaddingValues(0.dp)) {
            if (lines.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.ScrollText, null, Modifier.size(28.dp), tint = Bridge.brand)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    // Logs read left to right in every language.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(shown) { _, line ->
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono, fontSize = 11.5.sp, lineHeight = 18.sp),
                                    color = when (line.level) {
                                        Level.Error -> Bridge.destructive
                                        Level.Warning -> Bridge.warning
                                        Level.Info -> Bridge.foreground.copy(alpha = 0.85f)
                                    },
                                )
                            }
                        }
                    }
                    if (!following) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).height(48.dp).clickable {
                                following = true
                                scope.launch { list.scrollToItem(shown.lastIndex.coerceAtLeast(0)) }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(Modifier.height(38.dp).clip(CircleShape).background(Bridge.sidebarAccent).border(1.dp, Bridge.input, CircleShape).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.ArrowDown, null, Modifier.size(15.dp), tint = Bridge.foreground)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.latest), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ---------- Settings ----------

@Composable
private fun SetGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Bridge.mutedForeground, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 8.dp))
        BridgeCard(padding = PaddingValues(0.dp)) { content() }
    }
}

@Composable
private fun SetRow(icon: ImageVector, title: String, line: String?, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(21.dp), tint = Bridge.brand) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (line != null) Text(line, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, line: String, on: Boolean, onChange: (Boolean) -> Unit) {
    SetRow(icon, title, line, Modifier.toggleable(on, role = Role.Switch, onValueChange = onChange)) { BridgeSwitch(on, null) }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, line: String?, value: String? = null, onClick: () -> Unit) {
    SetRow(icon, title, line, Modifier.clickable(onClick = onClick)) {
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
        }
        DirectionalIcon(Icons.ChevronRight, null, Modifier.size(20.dp))
    }
}

private enum class SettingsSheet { Language, Credits, Privacy }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(model: AppModel, onOpenApps: () -> Unit) {
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    val tags = listOf("", "fa", "en")
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val chosenLanguage = tags.firstOrNull { it.isNotEmpty() && current.startsWith(it) } ?: ""
    val languages = mapOf("" to stringResource(R.string.language_system), "fa" to stringResource(R.string.language_fa), "en" to stringResource(R.string.language_en))
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        AppHeader(stringResource(R.string.settings_title), { HeaderTile(Icons.GearSixFill) })
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SetGroup("VPN") {
                Column(Modifier.padding(14.dp)) {
                    Segmented(
                        options = listOf(
                            Triple(false, stringResource(R.string.mode_full), Icons.Globe),
                            Triple(true, stringResource(R.string.mode_per_app), Icons.Split),
                        ),
                        selected = model.perApp,
                        onSelect = { model.changePerApp(it) },
                    )
                    Text(stringResource(if (model.perApp) R.string.mode_per_app_hint else R.string.mode_full_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                HorizontalDivider(color = Bridge.border)
                LinkRow(Icons.Smartphone, stringResource(R.string.apps_on_vpn), if (model.perApp) stringResource(R.string.apps_count, model.chosenApps.size) else stringResource(R.string.apps_every), onClick = onOpenApps)
            }
            SetGroup(stringResource(R.string.connection)) {
                ToggleRow(Icons.MapPin, stringResource(R.string.bypass_iran), stringResource(R.string.bypass_iran_hint), model.bypassIran, { model.changeBypassIran(it) })
                HorizontalDivider(color = Bridge.border)
                ToggleRow(Icons.Zap, stringResource(R.string.fake_ip), stringResource(R.string.fake_ip_hint), model.fakeIp, { model.changeFakeIp(it) })
                HorizontalDivider(color = Bridge.border)
                ToggleRow(Icons.ScrollText, stringResource(R.string.detailed_log), stringResource(R.string.detailed_log_hint), model.detailedLog, { model.changeDetailedLog(it) })
            }
            SetGroup(stringResource(R.string.appearance)) {
                LinkRow(Icons.Languages, stringResource(R.string.language), null, languages.getValue(chosenLanguage)) { sheet = SettingsSheet.Language }
            }
            SetGroup(stringResource(R.string.about)) {
                LinkRow(Icons.Info, stringResource(R.string.version, BuildConfig.VERSION_NAME), stringResource(R.string.licenses)) { sheet = SettingsSheet.Credits }
                HorizontalDivider(color = Bridge.border)
                LinkRow(Icons.ShieldCheck, stringResource(R.string.privacy_title), null) { sheet = SettingsSheet.Privacy }
            }
        }
    }
    val open = sheet ?: return
    ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = Bridge.popover) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            when (open) {
                SettingsSheet.Language -> {
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                    for (tag in tags) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(ItemShape).clickable {
                                sheet = null
                                AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioMark(tag == chosenLanguage)
                            Spacer(Modifier.width(12.dp))
                            Text(languages.getValue(tag), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    Text(stringResource(R.string.language_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                SettingsSheet.Credits -> {
                    Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                    Text(stringResource(R.string.credits), style = MaterialTheme.typography.bodySmall)
                }
                SettingsSheet.Privacy -> {
                    Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                    Text(stringResource(R.string.privacy_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
