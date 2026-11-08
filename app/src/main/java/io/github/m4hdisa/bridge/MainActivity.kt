package io.github.m4hdisa.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.github.m4hdisa.bridge.ui.AppsPage
import io.github.m4hdisa.bridge.ui.Bridge
import io.github.m4hdisa.bridge.ui.BridgeTheme
import io.github.m4hdisa.bridge.ui.BusyBar
import io.github.m4hdisa.bridge.ui.ConnectedLight
import io.github.m4hdisa.bridge.ui.EnterLinkDialog
import io.github.m4hdisa.bridge.ui.HomeScreen
import io.github.m4hdisa.bridge.ui.Icons
import io.github.m4hdisa.bridge.ui.ItemShape
import io.github.m4hdisa.bridge.ui.LogsPage
import io.github.m4hdisa.bridge.ui.PublicConfigsDialog
import io.github.m4hdisa.bridge.ui.ServersPage
import io.github.m4hdisa.bridge.ui.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var addSheet by remember { mutableStateOf(false) }
    var enterDialog by remember { mutableStateOf(false) }
    var publicDialog by remember { mutableStateOf(false) }
    var appFilter by rememberSaveable { mutableStateOf("") }
    var log by remember { mutableStateOf("") }

    // Apps opens from Settings now, so back goes there.
    BackHandler(enabled = page != Screen.Home) { onPage(if (page == Screen.Apps) Screen.Settings else Screen.Home) }

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
        Box(Modifier.fillMaxSize()) {
            ConnectedLight(model.running && page == Screen.Home)
            Column(Modifier.fillMaxSize().padding(inner)) {
                Spacer(Modifier.statusBarsPadding().height(8.dp))
                // Tests and updates show in their own lists; only the long search for public configs has a bar.
                model.busy?.takeIf { it == R.string.public_downloading || it == R.string.public_testing }?.let {
                    BusyBar(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                // Each page lays itself out: the dashboard fits the screen, the others scroll what they need to.
                when (page) {
                    Screen.Home -> HomeScreen(model, onConnect = onConnect, onOpenServers = { onPage(Screen.Servers) }, onAdd = { addSheet = true })
                    Screen.Servers -> ServersPage(model, onAdd = { addSheet = true }, onPublic = { publicDialog = true }, onPicked = { onPage(Screen.Home) })
                    Screen.Apps -> AppsPage(model, appFilter, { appFilter = it }, onBack = { onPage(Screen.Settings) })
                    Screen.Logs -> LogsPage(log)
                    Screen.Settings -> SettingsPage(model, onOpenApps = { onPage(Screen.Apps) })
                }
            }
        }
    }

    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }, sheetState = rememberModalBottomSheetState(), containerColor = Bridge.popover) {
            AddServersSheetContent(model, onDone = { addSheet = false }, onEnter = { enterDialog = true }, onPublic = { publicDialog = true }, onScan = onScan)
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
    // Apps lives in Settings now (Per-App is a setting on a phone).
    val items: List<Triple<Screen, Int, Pair<ImageVector, ImageVector>>> = listOf(
        Triple(Screen.Home, R.string.nav_home, Icons.SquaresFour to Icons.SquaresFourFill),
        Triple(Screen.Servers, R.string.nav_servers, Icons.GlobeHemisphereWest to Icons.GlobeHemisphereWestFill),
        Triple(Screen.Logs, R.string.nav_logs, Icons.Scroll to Icons.ScrollFill),
        Triple(Screen.Settings, R.string.nav_settings, Icons.GearSix to Icons.GearSixFill),
    )
    val current = if (page == Screen.Apps) Screen.Settings else page
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
    // A floating bar as wide as what's in it: the chosen page grows into a lit pill with its name; the others are icons.
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .shadow(18.dp, pill, ambientColor = androidx.compose.ui.graphics.Color.Black, spotColor = androidx.compose.ui.graphics.Color.Black)
                .clip(pill)
                .background(Bridge.card)
                .border(1.dp, Bridge.border, pill)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((target, label, icons) in items) {
                val on = current == target
                Row(
                    Modifier
                        .height(50.dp)
                        .then(if (on) Modifier else Modifier.width(60.dp))
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(25.dp))
                        .background(if (on) Bridge.primary.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPage(target) }
                        .animateContentSize()
                        .padding(horizontal = if (on) 16.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(if (on) icons.second else icons.first, stringResource(label), Modifier.size(24.dp), tint = if (on) Bridge.brand else Bridge.mutedForeground)
                    if (on) {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = Bridge.brand, maxLines = 1)
                    }
                }
            }
        }
    }
}
