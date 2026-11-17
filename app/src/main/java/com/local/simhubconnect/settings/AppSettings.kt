package com.local.simhubconnect.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.local.simhubconnect.R

/**
 * Small set of user preferences: dark/light/system theme, the accent of the app
 * shell and the accent used by the native dashboards.
 */
object AppSettings {

    const val MODE_DARK = 0
    const val MODE_LIGHT = 1
    const val MODE_SYSTEM = 2

    /** App accents. [color] is used by the dashboards, [themeRes] tints the UI. */
    data class Accent(val label: String, val color: Int, val themeRes: Int)

    val ACCENTS: List<Accent> = listOf(
        Accent("Arancione", 0xFFFF6B2C.toInt(), R.style.Theme_SimHubConnect_AccentOrange),
        Accent("Verde acqua", 0xFF2DD4BF.toInt(), R.style.Theme_SimHubConnect_AccentTeal),
        Accent("Azzurro", 0xFF38BDF8.toInt(), R.style.Theme_SimHubConnect_AccentBlue),
        Accent("Viola", 0xFFA78BFA.toInt(), R.style.Theme_SimHubConnect_AccentPurple),
        Accent("Lime", 0xFFB6FF3B.toInt(), R.style.Theme_SimHubConnect_AccentLime),
        Accent("Magenta", 0xFFFF2ED6.toInt(), R.style.Theme_SimHubConnect_AccentPink)
    )

    private const val PREFS = "simhub_settings"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_APP_ACCENT = "app_accent"
    private const val KEY_DASH_ACCENT = "dash_accent"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun themeMode(context: Context): Int {
        val mode = prefs(context).getInt(KEY_MODE, MODE_DARK)
        return if (mode in MODE_DARK..MODE_SYSTEM) mode else MODE_DARK
    }

    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit { putInt(KEY_MODE, mode) }
    }

    fun appAccent(context: Context): Int =
        prefs(context).getInt(KEY_APP_ACCENT, 0).coerceIn(ACCENTS.indices)

    fun setAppAccent(context: Context, index: Int) {
        prefs(context).edit { putInt(KEY_APP_ACCENT, index.coerceIn(ACCENTS.indices)) }
    }

    fun dashboardAccent(context: Context): Int =
        prefs(context).getInt(KEY_DASH_ACCENT, 0).coerceIn(ACCENTS.indices)

    fun setDashboardAccent(context: Context, index: Int) {
        prefs(context).edit { putInt(KEY_DASH_ACCENT, index.coerceIn(ACCENTS.indices)) }
    }

    fun appAccentColor(context: Context): Int = ACCENTS[appAccent(context)].color

    fun dashboardAccentColor(context: Context): Int = ACCENTS[dashboardAccent(context)].color

    /** Theme overlay (accent) applied on top of the DayNight base theme. */
    fun themeRes(context: Context): Int = ACCENTS[appAccent(context)].themeRes

    fun applyNightMode(context: Context) {
        val mode = when (themeMode(context)) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
