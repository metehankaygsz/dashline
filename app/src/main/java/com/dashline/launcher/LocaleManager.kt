// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Applies an in-app language override. Because appcompat 1.4.2 (pinned for KitKat
 * support) predates AppCompatDelegate.setApplicationLocales, we wrap each activity's
 * base context with a Configuration carrying the chosen locale — the classic
 * approach that works from API 19 up.
 */
object LocaleManager {

    /** Supported languages, labelled with their own autonym so users recognise them. */
    data class Lang(val code: String, val name: String)

    val LANGUAGES = listOf(
        Lang("en", "English"),
        Lang("tr", "Türkçe"),
        Lang("es", "Español"),
        Lang("de", "Deutsch"),
        Lang("fr", "Français"),
        Lang("it", "Italiano"),
        Lang("pt", "Português"),
        Lang("ru", "Русский"),
        Lang("zh", "中文"),
        Lang("ar", "العربية")
    )

    /** Wrap [context] so its resources use the saved language (no-op if "system"). */
    fun applyTo(context: Context): Context {
        val code = Prefs(context).language
        if (code.isEmpty()) return context
        val locale = localeFor(code)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale) // flips RTL for Arabic
        return context.createConfigurationContext(config)
    }

    /**
     * The locale for an in-app language choice. When it's the system's own
     * language, the system's region comes along: "English" on an en-GB unit
     * keeps day-month dates and 24-hour time instead of the US defaults that a
     * bare "en" implies. Strings still come from values-<code> either way.
     *
     * Not when the scripts differ, though. From Android 7 resources match on
     * script, so on a Traditional Chinese (zh-TW) unit values-zh — Simplified —
     * would no longer match, and choosing 中文 would show English.
     */
    private fun localeFor(code: String): Locale {
        val system = systemLocale()
        val chosen = Locale(code)
        if (system.language != code) return chosen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            likelyScript(system) != likelyScript(chosen)
        ) {
            return chosen
        }
        return system
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun likelyScript(locale: Locale): String =
        ULocale.addLikelySubtags(ULocale.forLocale(locale)).script

    /** The locale [context]'s resources are actually using — the override, if any. */
    fun locale(context: Context): Locale = primary(context.resources.configuration)

    /** The device's own language and region, whatever Dashline is set to. */
    fun systemLocale(): Locale = primary(Resources.getSystem().configuration)

    private fun primary(config: Configuration): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales.get(0)
        } else {
            @Suppress("DEPRECATION") config.locale
        }

    /**
     * Orders labels the way a reader of the current language expects. Primary
     * strength ignores case and accents, so "Ήχος" sorts among the Η words
     * rather than before the whole alphabet, as raw code points put it.
     */
    fun collator(context: Context): Collator =
        Collator.getInstance(locale(context)).apply { strength = Collator.PRIMARY }

    /**
     * Folds text for search: lowercase, accents stripped, Greek final sigma and
     * Turkish dotless ı made ordinary. People type without accents on a car
     * screen, so "χαρτες" has to find "Χάρτες", "telephone" has to find
     * "Téléphone" and "isik" has to find "Işık".
     */
    fun searchKey(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace('ς', 'σ')
            .replace('ı', 'i')

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    fun displayName(context: Context, code: String): String =
        if (code.isEmpty()) context.getString(R.string.language_system)
        else LANGUAGES.firstOrNull { it.code == code }?.name ?: code
}
