package com.pcscan.pcscan

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME_MODE = "key_theme_mode"

    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"
    const val MODE_SYSTEM = "system"

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, MODE_DARK).apply()
    }

    fun getCurrentMode(): String {
        return MODE_DARK
    }

    fun setMode(mode: String) {
        applyCurrentMode()
    }

    fun applyCurrentMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }

    fun applyToActivity(activity: android.app.Activity) {
        applyCurrentMode()
    }
}
