package com.dashline.launcher

/**
 * The bottom bar is built from this list rather than fixed layout entries, so the
 * user can reorder tabs and hide the ones their unit doesn't need.
 *
 * [id] is persisted — never rename one without a migration.
 */
enum class TabAction(val id: String, val iconRes: Int, val labelRes: Int) {
    AUDIO("audio", R.drawable.ic_audio, R.string.tab_audio),
    RADIO("radio", R.drawable.ic_radio, R.string.audio_radio),
    PHONE("phone", R.drawable.ic_phone, R.string.tab_phone),
    NAV("nav", R.drawable.ic_nav, R.string.tab_nav),
    APPS("apps", R.drawable.ic_apps, R.string.tab_apps),
    SETTINGS("settings", R.drawable.ic_settings, R.string.tab_settings);

    companion object {
        fun byId(id: String): TabAction? = entries.firstOrNull { it.id == id }

        /** Default order, matching the original hardcoded bar. */
        val DEFAULT_ORDER: List<TabAction> = entries.toList()
    }
}
