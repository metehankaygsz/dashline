package com.dashline.launcher

import android.content.Context

/**
 * Thin wrapper over SharedPreferences for launcher settings the user configures
 * (chosen phone / nav / radio / media apps) plus the first-run flag.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("radio_launcher", Context.MODE_PRIVATE)

    var setupDone: Boolean
        get() = sp.getBoolean(KEY_SETUP_DONE, false)
        set(v) = sp.edit().putBoolean(KEY_SETUP_DONE, v).apply()

    /** Package name of the user's chosen phone app, or null if not set yet. */
    var phoneApp: String?
        get() = sp.getString(KEY_PHONE, null)
        set(v) = sp.edit().putString(KEY_PHONE, v).apply()

    /** Navigation app. Defaults to Google Maps per product decision. */
    var navApp: String
        get() = sp.getString(KEY_NAV, DEFAULT_NAV) ?: DEFAULT_NAV
        set(v) = sp.edit().putString(KEY_NAV, v).apply()

    var radioApp: String?
        get() = sp.getString(KEY_RADIO, null)
        set(v) = sp.edit().putString(KEY_RADIO, v).apply()

    /** App to launch from the media widget when nothing is playing. */
    var mediaApp: String?
        get() = sp.getString(KEY_MEDIA, null)
        set(v) = sp.edit().putString(KEY_MEDIA, v).apply()

    /** true = Fahrenheit, false = Celsius (default). */
    var useFahrenheit: Boolean
        get() = sp.getBoolean(KEY_FAHRENHEIT, false)
        set(v) = sp.edit().putBoolean(KEY_FAHRENHEIT, v).apply()

    /**
     * Whether we've already shown the location prompt once. Setup asks first; the
     * dashboard only asks if setup never got the chance (e.g. upgraded installs),
     * so the user is never nagged on every launch.
     */
    var askedLocation: Boolean
        get() = sp.getBoolean(KEY_ASKED_LOCATION, false)
        set(v) = sp.edit().putBoolean(KEY_ASKED_LOCATION, v).apply()

    /** Language override as an ISO code (e.g. "tr"), or "" to follow the system. */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, "") ?: ""
        set(v) = sp.edit().putString(KEY_LANGUAGE, v).apply()

    /**
     * Theme mode: "auto" (by time), "light", "dark", or "system".
     * Defaults to dark — it's the right look for a dash at night and matches the
     * SYNC-style design; users can switch to auto/light in Settings.
     */
    var themeMode: String
        get() = sp.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    /** Id of the selected UI colour gradient (see GradientThemes.PRESETS). */
    var gradient: String
        get() = sp.getString(KEY_GRADIENT, "midnight") ?: "midnight"
        set(v) = sp.edit().putString(KEY_GRADIENT, v).apply()

    /** Keep the screen on while the dashboard is showing. */
    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_ON, true)
        set(v) = sp.edit().putBoolean(KEY_KEEP_ON, v).apply()

    // ---- favorite-apps dock ------------------------------------------------

    fun getFavorite(index: Int): String? = sp.getString(favKey(index), null)

    fun setFavorite(index: Int, pkg: String) =
        sp.edit().putString(favKey(index), pkg).apply()

    fun clearFavorite(index: Int) = sp.edit().remove(favKey(index)).apply()

    private fun favKey(index: Int) = "fav_$index"

    // ---- hidden apps (from the drawer) -------------------------------------

    /** Defensive copy — the Set returned by SharedPreferences must not be mutated. */
    val hiddenApps: Set<String>
        get() = HashSet(sp.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet())

    fun hideApp(pkg: String) =
        sp.edit().putStringSet(KEY_HIDDEN, hiddenApps + pkg).apply()

    fun clearHiddenApps() = sp.edit().remove(KEY_HIDDEN).apply()

    /** Resolve the effective candidate list for a role (user choice first). */
    fun candidatesFor(role: String): List<String> = when (role) {
        ROLE_PHONE -> listOfNotNull(phoneApp) + Defaults.PHONE
        ROLE_NAV -> listOf(navApp) + Defaults.NAV
        ROLE_RADIO -> listOfNotNull(radioApp) + Defaults.RADIO
        ROLE_MEDIA -> listOfNotNull(mediaApp) + Defaults.MEDIA
        else -> emptyList()
    }

    fun setForRole(role: String, pkg: String) {
        when (role) {
            ROLE_PHONE -> phoneApp = pkg
            ROLE_NAV -> navApp = pkg
            ROLE_RADIO -> radioApp = pkg
            ROLE_MEDIA -> mediaApp = pkg
        }
    }

    companion object {
        const val DEFAULT_NAV = "com.google.android.apps.maps"

        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_PHONE = "phone_app"
        private const val KEY_NAV = "nav_app"
        private const val KEY_RADIO = "radio_app"
        private const val KEY_MEDIA = "media_app"
        private const val KEY_FAHRENHEIT = "use_fahrenheit"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_KEEP_ON = "keep_screen_on"
        private const val KEY_HIDDEN = "hidden_apps"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_ASKED_LOCATION = "asked_location"
        private const val KEY_GRADIENT = "gradient"

        const val THEME_AUTO = "auto"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        const val ROLE_PHONE = "phone"
        const val ROLE_NAV = "nav"
        const val ROLE_RADIO = "radio"
        const val ROLE_MEDIA = "media"
        const val ROLE_FAVORITE = "favorite"

        /** Number of quick-launch slots in the top-bar dock. */
        const val FAVORITE_COUNT = 5
    }
}

/** Common package names to try when the user hasn't picked one for a role. */
object Defaults {
    val PHONE = listOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.samsung.android.dialer"
    )
    val NAV = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.sygic.aura"
    )
    val RADIO = listOf(
        "com.mtk.fm",
        "com.microntek.radio",
        "com.android.fmradio",
        "com.rtk.fmradio"
    )
    val MEDIA = listOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.music",
        "com.maxmpz.audioplayer",
        "com.android.music"
    )
}
