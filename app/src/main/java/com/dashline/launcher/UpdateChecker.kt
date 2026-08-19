// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A published release newer than the running build. */
data class Update(
    val versionName: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String
)

/**
 * Checks GitHub Releases for a newer build.
 *
 * Only for the sideloaded flavour. The Play build updates itself through Play,
 * and could not use this even if it wanted to: the two are signed with different
 * keys, so installing one over the other fails outright.
 */
object UpdateChecker {

    private const val LATEST_RELEASE =
        "https://api.github.com/repos/metehankaygsz/dashline/releases/latest"

    /** A day is often enough for a launcher that may be started many times a trip. */
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True when this build is allowed to offer its own updates. */
    fun isSupported(context: Context): Boolean {
        if (!BuildConfig.UPDATE_CHECK) return false
        // Belt and braces: if this copy came from Play, its updates come from Play.
        val installer = runCatching {
            context.packageManager.getInstallerPackageName(context.packageName)
        }.getOrNull()
        return installer != "com.android.vending"
    }

    /**
     * Looks for a newer release, at most once a day, and calls back on the UI
     * thread only when there's something to show. Any failure — no network, an
     * old TLS stack, a rate limit — is silent: an update prompt is not worth an
     * error message on a dashboard.
     */
    fun check(context: Context, force: Boolean = false, callback: (Update) -> Unit) {
        if (!isSupported(context)) return
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastUpdateCheck < CHECK_INTERVAL_MS) return
        prefs.lastUpdateCheck = now

        Thread {
            val update = runCatching { fetchLatest() }.getOrNull()
            if (update != null && isNewer(update.versionName, BuildConfig.VERSION_NAME) &&
                update.versionName != prefs.skippedVersion
            ) {
                mainHandler.post { callback(update) }
            }
        }.start()
    }

    private fun fetchLatest(): Update? {
        val connection = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests without one.
            setRequestProperty("User-Agent", "Dashline/${BuildConfig.VERSION_NAME}")
        }
        val body = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(body)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

        val tag = json.optString("tag_name").removePrefix("v")
        if (tag.isEmpty()) return null

        // The .aab is for the Play Console; only the APK is installable here.
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val asset = assets!!.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk")) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }

        return Update(
            versionName = tag,
            notes = json.optString("body").trim(),
            apkUrl = apkUrl,
            pageUrl = json.optString("html_url")
        )
    }

    /**
     * Compares dotted versions numerically, so 0.10.0 beats 0.9.0 — a string
     * comparison would get that backwards.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v")
            .split(".")
            .map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
