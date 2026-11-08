package io.github.m4hdisa.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which release counts as an update, and what is shown about it. */
class UpdatesTest {
    private val arm = listOf("arm64-v8a", "armeabi-v7a")

    private fun asset(name: String, digest: String? = "sha256:ABC123") = JSONObject()
        .put("name", name)
        .put("size", 60_000_000L)
        .put("browser_download_url", "https://github.com/m4hdisa/Bridge-Android/releases/download/x/$name")
        .also { if (digest != null) it.put("digest", digest) }

    private fun release(tag: String, vararg assets: JSONObject, draft: Boolean = false, pre: Boolean = false) = JSONObject()
        .put("tag_name", tag)
        .put("draft", draft)
        .put("prerelease", pre)
        .put("body", "text")
        .put("assets", JSONArray(assets.toList()))
        .toString()

    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(Updates.isNewer("0.4.10", "0.4.9"))
        assertTrue(Updates.isNewer("0.5", "0.4.9"))
        assertTrue(Updates.isNewer("1.0.0", "0.9.9"))
        assertFalse(Updates.isNewer("0.4.4", "0.4.4"))
        assertFalse(Updates.isNewer("0.4", "0.4.0"))
        assertFalse(Updates.isNewer("0.4.3", "0.4.4"))
    }

    @Test
    fun theApkForThisPhonesProcessorIsChosen() {
        val json = release("v0.4.5", asset("Bridge-0.4.5-armeabi-v7a.apk"), asset("Bridge-0.4.5-arm64-v8a.apk"), asset("Bridge-0.4.5-x86_64.apk"))
        val info = Updates.parse(json, arm, "0.4.4")!!
        assertEquals("0.4.5", info.version)
        assertTrue(info.url.endsWith("/Bridge-0.4.5-arm64-v8a.apk"))
        assertEquals("abc123", info.sha256)
        assertEquals(60_000_000L, info.size)
        // A 32-bit phone gets its own file.
        assertTrue(Updates.parse(json, listOf("armeabi-v7a"), "0.4.4")!!.url.endsWith("/Bridge-0.4.5-armeabi-v7a.apk"))
    }

    @Test
    fun onlyAFinishedNewerReleaseWithAMatchingFileIsAnUpdate() {
        val apk = asset("Bridge-0.4.5-arm64-v8a.apk")
        assertNull(Updates.parse(release("v0.4.4", asset("Bridge-0.4.4-arm64-v8a.apk")), arm, "0.4.4"))
        assertNull(Updates.parse(release("v0.4.5", apk, draft = true), arm, "0.4.4"))
        assertNull(Updates.parse(release("v0.4.5", apk, pre = true), arm, "0.4.4"))
        assertNull(Updates.parse(release("v0.4.5", asset("Bridge-0.4.5-x86_64.apk")), arm, "0.4.4"))
        // Tags that aren't a version, such as android-v0.4.5, never count.
        assertNull(Updates.parse(release("android-v0.4.5", apk), arm, "0.4.4"))
    }

    @Test
    fun aReleaseWithoutAChecksumStillParses() {
        assertNull(Updates.parse(release("v0.4.5", asset("Bridge-0.4.5-arm64-v8a.apk", null)), arm, "0.4.4")!!.sha256)
    }

    @Test
    fun theNotesAreInTheAppsLanguage() {
        val body = """
            **English** · [فارسی](#فارسی)

            ## Bridge for Android 0.4.5

            **Which file?** `Bridge-0.4.5-arm64-v8a.apk` for almost every phone.

            - **Dashboard:** a new look.
            - Faster `WARP`.

            ---

            <div dir="rtl">

            ## فارسی

            - **داشبورد:** ظاهر تازه.

            </div>
        """.trimIndent()
        assertEquals(listOf("Dashboard: a new look.", "Faster WARP."), Updates.notes(body, persian = false))
        assertEquals(listOf("داشبورد: ظاهر تازه."), Updates.notes(body, persian = true))
    }
}
