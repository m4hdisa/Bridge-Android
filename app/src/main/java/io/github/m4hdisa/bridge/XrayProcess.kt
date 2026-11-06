package io.github.m4hdisa.bridge

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The official Xray build, shipped in the APK as libxray.so (Android runs programs only from
 * the app's library folder) and run as its own process, like xray.exe on Windows. Bridge is
 * left out of the VPN, so Xray's connections to the servers go straight to the network.
 */
class XrayProcess(private val context: Context) {
    private var process: Process? = null

    private val binary get() = File(context.applicationInfo.nativeLibraryDir, "libxray.so")

    val available get() = binary.exists()

    /** Starts Xray and waits until its first SOCKS port answers. */
    fun start(config: JSONObject, firstPort: Int, log: File) {
        if (!available) throw IOException("this build has no Xray")
        val file = File(context.filesDir, CONFIG_FILE)
        // Holds the servers' passwords; the app's own folder is private to it.
        file.writeText(config.toString(2))
        val builder = ProcessBuilder(binary.path, "run", "-c", file.path)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        // Go looks for certificates in Linux folders; Android keeps them here.
        builder.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        builder.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.path
        val started = builder.start()
        process = started
        // ProcessBuilder.Redirect needs Android 8, so its output is copied by hand.
        Thread {
            runCatching {
                started.inputStream.bufferedReader().useLines { lines -> lines.forEach { log.appendText("[xray] $it\n") } }
            }
        }.apply { isDaemon = true }.start()
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            exitCode(started)?.let { code ->
                process = null
                throw IOException("Xray stopped right away (exit $code): ${lastLines(log)}")
            }
            if (answers(firstPort)) return
            Thread.sleep(100)
        }
        stop()
        throw IOException("Xray didn't start in time: ${lastLines(log)}")
    }

    val running get() = process?.let { exitCode(it) == null } == true

    fun stop() {
        val p = process ?: return
        process = null
        p.destroy()
        // Process.waitFor with a timeout needs Android 8; Bridge runs from Android 7.
        val deadline = System.currentTimeMillis() + 2_000
        while (exitCode(p) == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (exitCode(p) == null) Log.w(TAG, "Xray didn't stop in time")
        File(context.filesDir, CONFIG_FILE).delete()
    }

    /** The exit code, or null while it runs (Process.isAlive needs Android 8). */
    private fun exitCode(p: Process): Int? = try {
        p.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    private fun answers(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
        true
    } catch (_: IOException) {
        false
    }

    private fun lastLines(log: File): String = runCatching { log.readLines().takeLast(3).joinToString(" | ") }.getOrDefault("")

    companion object {
        private const val TAG = "Bridge"
        private const val CONFIG_FILE = "xray.json"
        private const val START_TIMEOUT_MS = 8_000L
    }
}
