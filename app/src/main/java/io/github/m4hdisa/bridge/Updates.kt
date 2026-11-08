package io.github.m4hdisa.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A newer version on GitHub, with the APK for this phone's processor. `notes` is the release's text. */
data class UpdateInfo(val version: String, val url: String, val size: Long, val sha256: String?, val notes: String)

/** Where an update is. */
sealed interface UpdateState {
    val info: UpdateInfo? get() = null

    data object None : UpdateState
    data class Available(override val info: UpdateInfo) : UpdateState
    data class Downloading(override val info: UpdateInfo, val done: Float) : UpdateState
    data class Ready(override val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(override val info: UpdateInfo, val rejected: Boolean) : UpdateState
}

/** The file isn't the update it should be, so it must not be installed. */
class UpdateRejected(message: String) : IOException(message)

/** The rules of an update, without Android, so unit tests can check them. */
object Updates {
    const val LATEST_RELEASE = "https://api.github.com/repos/m4hdisa/Bridge-Android/releases/latest"

    private val VERSION = Regex("^\\d+(\\.\\d+)*$")

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** GitHub's answer for the latest release: an update for one of `abis` (best first), or null if there is none. */
    fun parse(json: String, abis: List<String>, current: String): UpdateInfo? {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val version = release.optString("tag_name").removePrefix("v")
        if (!VERSION.matches(version) || !isNewer(version, current)) return null
        val assets = release.optJSONArray("assets") ?: return null
        val all = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        for (abi in abis) {
            val asset = all.firstOrNull { it.optString("name") == "Bridge-$version-$abi.apk" } ?: continue
            val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
            return UpdateInfo(version, asset.getString("browser_download_url"), asset.optLong("size"), digest, release.optString("body"))
        }
        return null
    }

    /** What changed, as a few lines in the app's language: the release text has English first, then Persian in an RTL block. */
    fun notes(body: String, persian: Boolean, max: Int = 6): List<String> {
        val text = body.replace("\r\n", "\n")
        val part = if (persian) {
            text.substringAfter("<div dir=\"rtl\">", "").substringBefore("</div>")
        } else {
            text.substringBefore("\n---")
        }
        return part.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").replace("**", "").replace("`", "") }.take(max)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** Finds, downloads, checks and opens an update. Android itself still asks the user before installing. */
class Updater(private val context: Context) {
    private val dir = File(context.cacheDir, "updates")

    /** Bridge's own requests skip the VPN, so GitHub is tried directly and through the VPN's proxy if that is blocked. */
    fun latest(proxy: LocalAccess?, current: String = BuildConfig.VERSION_NAME): UpdateInfo? {
        val direct = runCatching { Net.direct(Updates.LATEST_RELEASE, 10_000) }.getOrNull()?.takeIf { it.ok }
        val page = direct ?: proxy?.let { Net.through(it, Updates.LATEST_RELEASE, 15_000).takeIf { p -> p.ok } } ?: throw IOException("GitHub didn't answer")
        return Updates.parse(page.body, Build.SUPPORTED_ABIS.toList(), current)
    }

    /** The APK, checked. A broken download is picked up where it stopped. */
    fun download(info: UpdateInfo, proxy: LocalAccess?, keepGoing: () -> Boolean, progress: (Long, Long) -> Unit): File {
        dir.mkdirs()
        val apk = File(dir, "Bridge-${info.version}.apk")
        val part = File(dir, "Bridge-${info.version}.part")
        dir.listFiles()?.filter { it != apk && it != part }?.forEach { it.delete() }
        if (!apk.exists()) {
            fetch(info, part, proxy, keepGoing, progress)
            if (!part.renameTo(apk)) throw IOException("couldn't keep the download")
        }
        try {
            verify(apk, info)
        } catch (e: UpdateRejected) {
            apk.delete()
            throw e
        }
        return apk
    }

    private fun fetch(info: UpdateInfo, part: File, proxy: LocalAccess?, keepGoing: () -> Boolean, progress: (Long, Long) -> Unit) {
        var last: IOException? = null
        // A few tries on the phone's own network, then through the VPN if there is one.
        for (way in listOf<LocalAccess?>(null, null, null) + listOfNotNull(proxy, proxy)) {
            try {
                Net.download(info.url, part, way, keepGoing, progress)
                return
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("the download failed")
    }

    /** It must be the expected file, Bridge itself, newer, and signed with the key this copy of Bridge was signed with. */
    fun verify(apk: File, info: UpdateInfo) {
        if (info.sha256 != null && sha256(apk) != info.sha256) throw UpdateRejected("the download doesn't match GitHub's checksum")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val packages = context.packageManager
        val update = packages.getPackageArchiveInfo(apk.path, flags) ?: throw UpdateRejected("not an app")
        if (update.packageName != context.packageName) throw UpdateRejected("not Bridge")
        val mine = packages.getPackageInfo(context.packageName, flags)
        val signers = signers(update)
        if (signers.isEmpty() || signers != signers(mine)) throw UpdateRejected("signed by someone else")
        if (PackageInfoCompat.getLongVersionCode(update) <= PackageInfoCompat.getLongVersionCode(mine)) throw UpdateRejected("not newer than this version")
    }

    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return signatures.orEmpty().map { Updates.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return Updates.hex(digest.digest())
    }

    /** Whether Bridge may ask Android to install; if not, Android's page to allow it is opened instead. */
    fun install(apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return true
    }
}
