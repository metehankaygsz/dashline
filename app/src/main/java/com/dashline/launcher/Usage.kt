package com.dashline.launcher

import android.content.Context

/** Tracks how often each app is launched, to surface frequently-used apps. */
object Usage {

    private fun prefs(context: Context) =
        context.getSharedPreferences("usage_counts", Context.MODE_PRIVATE)

    fun record(context: Context, packageName: String) {
        val sp = prefs(context)
        sp.edit().putInt(packageName, sp.getInt(packageName, 0) + 1).apply()
    }

    /** Package names ordered by launch count (most used first). */
    fun topApps(context: Context, limit: Int): List<String> =
        prefs(context).all
            .mapNotNull { (pkg, count) -> (count as? Int)?.let { pkg to it } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
}
