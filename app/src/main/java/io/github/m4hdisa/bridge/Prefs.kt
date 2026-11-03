package io.github.m4hdisa.bridge

import android.content.Context

/** What the user entered. Stays on the phone; apps may not back it up (allowBackup is off). */
class Prefs(context: Context) {
    private val store = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var link: String
        get() = store.getString("link", "") ?: ""
        set(value) = store.edit().putString("link", value).apply()

    /** On: only the chosen apps use the VPN. Off: every app does. */
    var perApp: Boolean
        get() = store.getBoolean("perApp", true)
        set(value) = store.edit().putBoolean("perApp", value).apply()

    var apps: Set<String>
        get() = store.getStringSet("apps", emptySet()) ?: emptySet()
        set(value) = store.edit().putStringSet("apps", value).apply()
}
