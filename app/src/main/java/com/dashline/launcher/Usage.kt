// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context

/**
 * Tracks how often each app is launched, to surface frequently-used apps.
 *
 * Counts are kept per icon — the component that actually opened — so the two
 * icons of a multi-icon app are counted separately, and opening an app from a
 * role shortcut counts toward the same icon as opening it from the drawer.
 */
object Usage {

    private fun prefs(context: Context) =
        context.getSharedPreferences("usage_counts", Context.MODE_PRIVATE)

    /** [target] should be what actually opened; see AppRepository.launch. */
    fun record(context: Context, target: String) {
        val sp = prefs(context)
        sp.edit().putInt(target, sp.getInt(target, 0) + 1).apply()
    }

    /**
     * The most-used of [apps], in order. Keys whose icon isn't in [apps] — a
     * hidden app, or an icon an update renamed — are dropped before counting
     * out [limit], so they never cost the strip a slot.
     */
    fun frequentApps(context: Context, apps: List<AppInfo>, limit: Int): List<AppInfo> {
        migrate(context)
        val byTarget = apps.associateBy { it.target }
        return prefs(context).all
            .mapNotNull { (target, count) -> (count as? Int)?.let { target to it } }
            .sortedByDescending { it.second }
            .mapNotNull { byTarget[it.first] }
            .take(limit)
    }

    /**
     * Versions before per-icon counting keyed counts by package. Fold each into
     * the icon that package opens, once, so they rank alongside new counts
     * instead of splitting an app's launches across two keys.
     */
    private fun migrate(context: Context) {
        val sp = prefs(context)
        if (sp.getBoolean(KEY_PER_ICON, false)) return
        val editor = sp.edit()
        val merged = HashMap<String, Int>()
        sp.all.forEach { (key, value) ->
            val count = value as? Int ?: return@forEach
            if ('/' in key) {
                merged[key] = (merged[key] ?: 0) + count
                return@forEach
            }
            // Not installed any more: leave the old key; it simply never matches.
            val icon = AppRepository.resolve(context, key)
            if (icon == key) return@forEach
            editor.remove(key)
            merged[icon] = (merged[icon] ?: 0) + count
        }
        merged.forEach { (key, count) -> editor.putInt(key, count) }
        editor.putBoolean(KEY_PER_ICON, true).apply()
    }

    private const val KEY_PER_ICON = "_per_icon_counts"
}
