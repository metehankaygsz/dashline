// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context
import android.provider.Settings
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The dashboard's clock and date formats, shared with the Customize preview so
 * the two can't drift apart.
 *
 * Neither is a fixed pattern: the language decides order and punctuation
 * ("Monday, January 1" in US English, "Δευτέρα 1 Ιανουαρίου" in Greek, "1月1日星期一"
 * in Chinese) and the system setting decides 12 or 24 hours.
 */
object ClockFormats {

    /**
     * The big clock. It leaves out AM/PM: it's read at a glance, and the marker
     * would take a third of the digits' width on a narrow panel.
     */
    fun clock(context: Context, locale: Locale): SimpleDateFormat {
        val pattern =
            if (is24Hour(context)) DateFormat.getBestDateTimePattern(locale, "Hm")
            else withoutDayPeriod(DateFormat.getBestDateTimePattern(locale, "hm"))
        return SimpleDateFormat(pattern, locale)
    }

    /** Weekday, day and month — never the year, which nobody needs on a dash. */
    fun longDate(locale: Locale): SimpleDateFormat =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)

    /**
     * The system's 12/24-hour setting. DateFormat.is24HourFormat would answer
     * from the in-app language when the setting is "use locale default", so a
     * Turkish dashboard on a US unit would disagree with the unit's own clock.
     */
    private fun is24Hour(context: Context): Boolean {
        val setting = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        if (setting != null) return setting == "24"
        val pattern = DateFormat.getBestDateTimePattern(LocaleManager.systemLocale(), "jm")
        return unquoted(pattern).any { it == 'H' || it == 'k' }
    }

    /**
     * Drops the AM/PM and day-period fields (a, b, B) with the spaces beside
     * them, and turns a 0–11 hour (K, as Japanese uses) into 1–12 (h) — without
     * its marker, "0:34" would read as just after midnight. Quoted literal text
     * is left alone.
     */
    private fun withoutDayPeriod(pattern: String): String {
        val out = StringBuilder()
        var quoted = false
        var skipSpace = false
        for (c in pattern) {
            if (c == '\'') {
                quoted = !quoted
                out.append(c)
                skipSpace = false
                continue
            }
            when {
                quoted -> out.append(c)
                c == 'a' || c == 'b' || c == 'B' -> {
                    while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
                    skipSpace = true
                    continue
                }
                skipSpace && c.isWhitespace() -> continue
                c == 'K' -> out.append('h')
                else -> out.append(c)
            }
            skipSpace = false
        }
        return out.toString().trim()
    }

    /** [pattern] with its quoted literal text removed, for inspecting fields. */
    private fun unquoted(pattern: String): String =
        pattern.split('\'').filterIndexed { index, _ -> index % 2 == 0 }.joinToString("")
}
