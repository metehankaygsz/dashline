// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the system package installer.
 *
 * There is no way for an ordinary app to install silently — that needs system or
 * device-owner privileges — so the most we can do is spare the user the browser
 * and the file manager, and leave them one Install button to press. Head-unit
 * browsers are often unusable or missing, which is why this doesn't simply open
 * the release page (that's the fallback when anything here fails).
 */
object UpdateInstaller {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * From Oreo the user must allow this specific app to install packages. Send
     * them to that screen; they can start the update again when they return.
     */
    fun needsUnknownSourcesConsent(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()

    fun requestUnknownSourcesConsent(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        if (!start(activity, intent)) {
            // Some head units strip that settings screen; the general one is closer
            // than nothing.
            start(activity, Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    /**
     * Fetches [url] on a background thread, then opens the installer.
     * [onFailure] runs on the UI thread so the caller can fall back to a browser.
     */
    fun download(activity: Activity, url: String, onFailure: () -> Unit) {
        Thread {
            val file = runCatching { fetch(activity, url) }.getOrNull()
            mainHandler.post {
                if (file == null || !install(activity, file)) onFailure()
            }
        }.start()
    }

    private fun fetch(activity: Activity, url: String): File? {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        // One file, overwritten: a half-finished download must never be installed,
        // so it's written to a temp name and only then renamed into place.
        val target = File(dir, "dashline-update.apk")
        val temp = File(dir, "dashline-update.part")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Dashline/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (temp.length() <= 0L) return null
        if (target.exists()) target.delete()
        return if (temp.renameTo(target)) target else null
    }

    /** Opens the system installer for [file]. False when it can't be launched. */
    private fun install(activity: Activity, file: File): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // A file:// URI throws FileUriExposedException from Nougat on, so the
            // APK has to be handed over as a content:// URI we grant access to.
            val uri = runCatching {
                FileProvider.getUriForFile(
                    activity, "${activity.packageName}.updates", file
                )
            }.getOrNull() ?: return false
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            file.setReadable(true, false)
            intent.setDataAndType(
                Uri.fromFile(file), "application/vnd.android.package-archive"
            )
        }
        return start(activity, intent)
    }

    /** Last resort: let the user fetch the release however their unit can. */
    fun openReleasePage(activity: Activity, url: String): Boolean =
        start(activity, Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    private fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        // No browser, no installer, no settings screen — plenty of units lack one.
        false
    }
}
