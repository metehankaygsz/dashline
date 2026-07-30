package com.dashline.launcher

import android.content.Context
import android.content.res.Configuration
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
        val locale = Locale(code)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale) // flips RTL for Arabic
        return context.createConfigurationContext(config)
    }

    fun displayName(context: Context, code: String): String =
        if (code.isEmpty()) context.getString(R.string.language_system)
        else LANGUAGES.firstOrNull { it.code == code }?.name ?: code
}
