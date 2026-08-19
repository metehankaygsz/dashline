// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import androidx.appcompat.app.AppCompatDelegate
import java.util.Calendar

/**
 * Applies the user's chosen day/night theme. "auto" switches by local time
 * (night between 19:00 and 07:00) — a universal rule that needs no device signal.
 */
object ThemeManager {

    fun apply(prefs: Prefs) {
        val mode = when (prefs.themeMode) {
            Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            Prefs.THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> if (isNightTime()) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun isNightTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < DAY_START_HOUR || hour >= NIGHT_START_HOUR
    }

    private const val DAY_START_HOUR = 7
    private const val NIGHT_START_HOUR = 19
}
