package io.github.m4hdisa.bridge

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a phone that had saved WARP configs (0.4.2 and 0.4.3) finds after updating. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class StoreMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    private fun updateFrom(selected: String) {
        prefs.edit().clear().putString("selected", selected).putString("warpConfigs", """[{"id":"abc"}]""").commit()
    }

    @Test
    fun aPickedSavedWarpConfigBecomesFreeWarp() {
        updateFrom("warp:abc")
        assertEquals(Warp.ID, Store(context).selected)
        assertFalse(prefs.contains("warpConfigs"))
    }

    @Test
    fun anyOtherChoiceStays() {
        for (selected in listOf(AUTO, Warp.ID, "b831381d-6324-4d53-ad4f-8cda48b30811")) {
            updateFrom(selected)
            assertEquals(selected, Store(context).selected)
            assertFalse(prefs.contains("warpConfigs"))
        }
    }
}
