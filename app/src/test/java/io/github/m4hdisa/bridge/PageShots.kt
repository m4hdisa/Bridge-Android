package io.github.m4hdisa.bridge

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws every page on the build machine (Robolectric), in English and Persian, so the design can
 * be checked without a phone. Runs only when BRIDGE_SHOTS names a folder for the pictures.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PageShots {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("BRIDGE_SHOTS")
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    @Before
    fun seed() {
        assumeTrue("set BRIDGE_SHOTS to draw the pages", out != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().clear().commit()
        val store = Store(context)
        val now = System.currentTimeMillis()
        val sub = Subscription("sub", "https://panel.example.com/sub/abc123", "My provider", updatedAt = now - 3_600_000, usage = Usage(12_400_000_000, 50_000_000_000, now / 1000 + 18 * 86_400))
        // Free WARP shows as it does on a phone that has Aether.
        val libs = File("build/shot-libs").absoluteFile.apply { mkdirs() }
        File(libs, "libaether.so").writeText("")
        context.applicationInfo.nativeLibraryDir = libs.path
        store.replaceSubscription(
            sub,
            listOf(
                "vless://$uuid@de1.example.com:443?type=tcp&security=reality&sni=www.google.com&pbk=KEY#🇩🇪 Germany Reality",
                "vless://$uuid@nl1.example.com:443?type=xhttp&security=tls&sni=nl1.example.com#Amsterdam xhttp",
                "trojan://pw@fi1.example.com:443?type=ws&security=tls#Helsinki",
                "wireguard://x@wg.example.com:51820#WireGuard",
            ),
        )
        store.addLinks(listOf("vmess://" + Base64Text.encode("""{"v":"2","ps":"Tokyo","add":"jp1.example.com","port":"443","id":"$uuid","net":"grpc","tls":"tls","path":"g"}""")))
        val ids = store.servers.associate { it.name to it.id }
        store.setDelays(mapOf(ids.getValue("🇩🇪 Germany Reality") to 84, ids.getValue("Amsterdam xhttp") to 412, ids.getValue("Helsinki") to -1, ids.getValue("Tokyo") to 162))
        store.selected = ids.getValue("🇩🇪 Germany Reality")
        store.perApp = false
    }

    private fun store() = Store(ApplicationProvider.getApplicationContext())

    private fun shoot(prefix: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (screen in Screen.entries) {
                scenario.onActivity { it.show(screen) }
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
                compose.onRoot().captureRoboImage(File(out, "$prefix-${screen.name.lowercase()}.png").path)
            }
            VpnState.set(VpnState.Status.Running)
            scenario.onActivity { it.show(Screen.Home) }
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity {
                App.model(it).pretend(
                    Health(Health.State.Ok, 84, "203.0.113.24", "DE", "Frankfurt am Main", 50.1 to 8.7),
                    "IR",
                    Traffic(808_000, 22_500, 412_000_000, 18_300_000),
                )
            }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(out, "$prefix-home-connected.png").path)
            VpnState.set(VpnState.Status.Stopped)
            shadowOf(Looper.getMainLooper()).idle()
            // Free WARP chosen, while it looks for a way through.
            scenario.onActivity { App.model(it).select(Warp.ID) }
            VpnState.set(VpnState.Status.Starting)
            VpnState.progress(R.string.progress_warp_search)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(out, "$prefix-home-warp-connecting.png").path)
            VpnState.set(VpnState.Status.Stopped)
            scenario.onActivity { it.show(Screen.Servers) }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(out, "$prefix-servers-warp.png").path)
            scenario.onActivity { App.model(it).select(store().servers.first().id) }
            shadowOf(Looper.getMainLooper()).idle()
            val notes = "- **Dashboard:** a new look.\n- Faster updates.\n\n---\n\n<div dir=\"rtl\">\n\n- **داشبورد:** ظاهر تازه.\n- به‌روزرسانی سریع‌تر.\n\n</div>"
            scenario.onActivity { App.model(it).pretendUpdate(UpdateInfo("0.4.5", "https://example.com/a.apk", 60_153_386, null, notes)) }
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            compose.onAllNodes(isRoot()).onLast().captureRoboImage(File(out, "$prefix-update.png").path)
        }
    }

    @Test
    @Config(qualifiers = "en-w400dp-h840dp-xxhdpi")
    fun english() = shoot("en")

    @Test
    @Config(qualifiers = "fa-w400dp-h840dp-xxhdpi")
    fun persian() = shoot("fa")

    @Test
    @Config(qualifiers = "en-w360dp-h640dp-xxhdpi")
    fun aNarrowPhone() = shoot("narrow")
}
