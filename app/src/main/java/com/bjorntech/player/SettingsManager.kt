package com.bjorntech.player

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/** App preferences backed by SharedPreferences. */
object SettingsManager {

    private const val PREFS = "settings"
    private const val KEY_THEME = "theme_mode"            // an AppCompatDelegate.MODE_NIGHT_* value
    private const val KEY_AUTOPLAY = "autoplay_on_launch"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Theme ────────────────────────────────────────────────────────────────

    fun getThemeMode(context: Context): Int =
        prefs(context).getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    /** Persist and apply immediately (AppCompat recreates visible activities). */
    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit { putInt(KEY_THEME, mode) }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Apply the saved theme at process start. */
    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getThemeMode(context))
    }

    fun themeLabel(context: Context): String = when (getThemeMode(context)) {
        AppCompatDelegate.MODE_NIGHT_NO -> "Light"
        AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
        else -> "System default"
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    fun isAutoplayOnLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOPLAY, true)

    fun setAutoplayOnLaunch(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_AUTOPLAY, enabled) }
}
