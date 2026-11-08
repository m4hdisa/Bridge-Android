package io.github.m4hdisa.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.m4hdisa.bridge.ui.AddServersSheetContent
import io.github.m4hdisa.bridge.ui.Bridge
import io.github.m4hdisa.bridge.ui.BridgeTheme
import io.github.m4hdisa.bridge.ui.BusyBar
import io.github.m4hdisa.bridge.ui.EnterLinkDialog
import io.github.m4hdisa.bridge.ui.HomeScreen
import io.github.m4hdisa.bridge.ui.Icons
import io.github.m4hdisa.bridge.ui.ItemShape
import io.github.m4hdisa.bridge.ui.LogoMark
import io.github.m4hdisa.bridge.ui.PublicConfigsDialog
import io.github.m4hdisa.bridge.ui.ServerPickerContent
import io.github.m4hdisa.bridge.ui.StatusDot
import io.github.m4hdisa.bridge.ui.appsPage
import io.github.m4hdisa.bridge.ui.logsPage
import io.github.m4hdisa.bridge.ui.serversPage
import io.github.m4hdisa.bridge.ui.settingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Home, Servers, Apps, Logs, Settings }

class MainActivity : AppCompatActivity() {
    private val model by lazy { App.model(this) }
    private var page by mutableStateOf(Screen.Home)

    /** The page on screen; the emulator test opens each one. */
    val shown get() = page

    fun show(screen: Screen) {
        page = screen
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.permissionResult(result.resultCode == RESULT_OK)
    }

    /** The camera, for a config's or subscription's QR code (ZXing's own screen asks for the camera). */
    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let {
            page = Screen.Servers
            model.addText(it)
        }
    }

    private fun startScan() {
        scan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false),
        )
    }

    // The app works without it; only the "connected" notification stays hidden.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(PAGE)?.let { saved -> Screen.entries.firstOrNull { it.name == saved }?.let { page = it } }
        setContent {
            BridgeTheme {
                BridgeApp(model, page, { page = it }, onConnect = { model.toggle { vpnPermission.launch(it) } }, onScan = { startScan() })
            }
        }
        model.loadApps()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) {
            handleIncoming(intent)
            model.updateSubscriptions(onlyStale = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PAGE, page.name)
    }

    /** Links shared from another app, or opened from a browser. */
    private fun handleIncoming(intent: Intent?) {
        // The quick settings tile, when Android first has to allow the VPN.
        if (intent?.action == ACTION_CONNECT) {
            if (model.status == VpnState.Status.Stopped) model.toggle { vpnPermission.launch(it) }
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        page = Screen.Servers
        model.addText(text)
    }

    companion object {
        private const val PAGE = "page"
        const val ACTION_CONNECT = "io.github.m4hdisa.bridge.CONNECT"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeApp(model: AppModel, page: Screen, onPage: (Screen) -> Unit, onConnect: () -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var addSheet by remember { mutableStateOf(false) }
    var pickSheet by remember { mutableStateOf(false) }
    var enterDialog by remember { mutableStateOf(false) }
    var publicDialog by remember { mutableStateOf(false) }
    var appFilter by rememberSaveable { mutableStateOf("") }
    var log by remember { mutableStateOf("") }

    BackHandler(enabled = page != Screen.Home) { onPage(Screen.Home) }

    val message = model.message
    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        val text = context.getString(m.res, *m.args.toTypedArray())
        val action = when (m.action) {
            Message.Action.Reconnect -> context.getString(R.string.reconnect)
            null -> null
        }
        model.messageShown(m)
        val result = snackbar.showSnackbar(text, actionLabel = action, withDismissAction = action == null)
        if (result == SnackbarResult.ActionPerformed && m.action == Message.Action.Reconnect) model.reconnect()
    }

    // The log follows the connection while it is on screen.
    LaunchedEffect(page) {
        while (page == Screen.Logs) {
            log = withContext(Dispatchers.IO) { model.readLog() }
            delay(2_000)
        }
    }

    Scaffold(
        containerColor = Bridge.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, containerColor = Bridge.popover, contentColor = Bridge.foreground, actionColor = Bridge.brand, dismissActionContentColor = Bridge.mutedForeground, shape = ItemShape)
            }
        },
        bottomBar = { NavBar(page, onPage) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // No top bar: the dashboard says how the connection is under its button, and Settings has the version.
            Spacer(Modifier.statusBarsPadding().height(8.dp))
            model.busy?.let { BusyBar(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            val list = rememberLazyListState()
            LaunchedEffect(page) { list.scrollToItem(0) }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
                when (page) {
                    Screen.Home -> item {
                        HomeScreen(
                            model,
                            onConnect = onConnect,
                            onOpenServers = { onPage(Screen.Servers) },
                            onOpenApps = { onPage(Screen.Apps) },
                            onAdd = { addSheet = true },
                        )
                    }
                    Screen.Servers -> serversPage(model, onAdd = { addSheet = true }, onPublic = { publicDialog = true })
                    Screen.Apps -> appsPage(model, appFilter) { appFilter = it }
                    Screen.Logs -> logsPage(log)
                    Screen.Settings -> settingsPage(model)
                }
            }
        }
    }

    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }, sheetState = rememberModalBottomSheetState(), containerColor = Bridge.popover) {
            AddServersSheetContent(model, onDone = { addSheet = false }, onEnter = { enterDialog = true }, onPublic = { publicDialog = true }, onScan = onScan)
        }
    }
    if (pickSheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { pickSheet = false }, sheetState = state, containerColor = Bridge.popover) {
            ServerPickerContent(
                model,
                onDone = { scope.launch { state.hide() }.invokeOnCompletion { pickSheet = false } },
                onManage = { onPage(Screen.Servers) },
            )
        }
    }
    if (enterDialog) {
        EnterLinkDialog(onAdd = {
            enterDialog = false
            onPage(Screen.Servers)
            model.addText(it)
        }, onDismiss = { enterDialog = false })
    }
    if (publicDialog) {
        PublicConfigsDialog(onConfirm = {
            publicDialog = false
            onPage(Screen.Servers)
            model.findPublic()
        }, onDismiss = { publicDialog = false })
    }
}

/** The pages, along the bottom like the Windows sidebar: the chosen one filled and lit. */
@Composable
private fun NavBar(page: Screen, onPage: (Screen) -> Unit) {
    val items: List<Triple<Screen, Int, Pair<ImageVector, ImageVector>>> = listOf(
        Triple(Screen.Home, R.string.nav_home, Icons.SquaresFour to Icons.SquaresFourFill),
        Triple(Screen.Servers, R.string.nav_servers, Icons.GlobeHemisphereWest to Icons.GlobeHemisphereWestFill),
        Triple(Screen.Apps, R.string.nav_apps, Icons.AppWindow to Icons.AppWindowFill),
        Triple(Screen.Logs, R.string.nav_logs, Icons.Scroll to Icons.ScrollFill),
        Triple(Screen.Settings, R.string.nav_settings, Icons.GearSix to Icons.GearSixFill),
    )
    Column(Modifier.background(Bridge.sidebar)) {
        HorizontalDivider(color = Bridge.border)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            for ((target, label, icons) in items) {
                val on = page == target
                Column(
                    Modifier
                        .weight(1f)
                        .clip(ItemShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPage(target) }
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.clip(ItemShape).background(if (on) Bridge.sidebarAccent else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 16.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (on) icons.second else icons.first, null, Modifier.size(20.dp), tint = if (on) Bridge.foreground else Bridge.mutedForeground)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) Bridge.foreground else Bridge.mutedForeground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
