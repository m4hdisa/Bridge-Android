package io.github.m4hdisa.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The real thing against GitHub, on the emulator: this test build is signed with another key, so the release must be refused. */
@RunWith(AndroidJUnit4::class)
class UpdateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aRealReleaseIsFoundDownloadedAndRefusedWithTheWrongKey() {
        val updater = Updater(context)
        val info = updater.latest(null, current = "0.0.1")
        assertNotNull(info)
        var seen = 0L
        try {
            updater.download(info!!, null, { true }) { done, _ -> seen = done }
            fail("a release signed with another key was accepted")
        } catch (e: UpdateRejected) {
            assertEquals("signed by someone else", e.message)
        }
        assertEquals(info!!.size, seen)
    }
}
