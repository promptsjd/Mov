package com.example.blocker.settings

import android.content.Context
import com.example.blocker.session.Complexity

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Complexity ────────────────────────────────────────────────────────────

    var complexity: Complexity
        get() {
            val name = prefs.getString(KEY_COMPLEXITY, Complexity.MEDIUM.name)!!
            return runCatching { Complexity.valueOf(name) }.getOrDefault(Complexity.MEDIUM)
        }
        set(value) = prefs.edit().putString(KEY_COMPLEXITY, value.name).apply()

    // ── Blocked apps ──────────────────────────────────────────────────────────

    var blockedApps: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED_APPS, DEFAULT_BLOCKED_APPS)!!.toSet()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED_APPS, value).apply()

    fun addBlockedApp(packageName: String) {
        blockedApps = blockedApps + packageName
    }

    fun removeBlockedApp(packageName: String) {
        blockedApps = blockedApps - packageName
    }

    companion object {
        private const val PREFS_NAME        = "mov_settings"
        private const val KEY_COMPLEXITY    = "complexity"
        private const val KEY_BLOCKED_APPS  = "blocked_apps"

        private val DEFAULT_BLOCKED_APPS = setOf("com.instagram.android")
    }
}
