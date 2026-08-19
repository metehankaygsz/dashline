// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * Offers a newer sideloaded build, showing its version and release notes.
 *
 * Three ways out, because "no" means two different things: not now, and not this
 * one. Only the second is remembered, so declining an update never stops later
 * ones being offered.
 */
object UpdatePrompt {

    /**
     * Held while the user is away granting install permission, so returning
     * carries straight on with the update instead of stranding them — the daily
     * check won't run again for another day, and asking them to relaunch to
     * finish something they already agreed to is not an answer.
     */
    private var awaitingConsent: Update? = null

    /** Called when the dashboard resumes; picks the update back up if it can. */
    fun resumeIfReady(activity: Activity) {
        val update = awaitingConsent ?: return
        if (UpdateInstaller.needsUnknownSourcesConsent(activity)) return
        awaitingConsent = null
        install(activity, update)
    }

    fun show(activity: Activity, update: Update) {
        val prefs = Prefs(activity)
        val notes = update.notes.ifEmpty { activity.getString(R.string.update_no_notes) }

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, update.versionName))
            .setMessage(trimNotes(notes))
            .setPositiveButton(R.string.update_install) { _, _ -> install(activity, update) }
            .setNeutralButton(R.string.update_later, null)
            .setNegativeButton(R.string.update_skip) { _, _ ->
                prefs.skippedVersion = update.versionName
            }
            .show()
    }

    private fun install(activity: Activity, update: Update) {
        // From Oreo the user has to allow this app to install packages first.
        if (UpdateInstaller.needsUnknownSourcesConsent(activity)) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_allow_title)
                .setMessage(R.string.update_allow_body)
                .setPositiveButton(R.string.update_allow_open) { _, _ ->
                    awaitingConsent = update
                    UpdateInstaller.requestUnknownSourcesConsent(activity)
                }
                .setNegativeButton(R.string.update_later) { _, _ -> awaitingConsent = null }
                .show()
            return
        }

        val apkUrl = update.apkUrl
        if (apkUrl == null) {
            fallback(activity, update)
            return
        }

        // A download over a head unit's connection is not instant; say so, and
        // don't let a stray tap start a second one.
        val progress = AlertDialog.Builder(activity)
            .setMessage(R.string.update_downloading)
            .setCancelable(false)
            .show()

        UpdateInstaller.download(activity, apkUrl) {
            progress.dismiss()
            fallback(activity, update)
        }
        // The installer opens over us; the progress dialog goes when we resume.
        activity.window.decorView.postDelayed({ runCatching { progress.dismiss() } }, DISMISS_MS)
    }

    /** Anything went wrong — offer the release page instead of a dead end. */
    private fun fallback(activity: Activity, update: Update) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_failed_title)
            .setMessage(R.string.update_failed_body)
            .setPositiveButton(R.string.update_open_page) { _, _ ->
                if (!UpdateInstaller.openReleasePage(activity, update.pageUrl)) {
                    android.widget.Toast.makeText(
                        activity, R.string.update_no_browser, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /**
     * Release notes are Markdown on GitHub and a plain TextView here, so the
     * syntax has to come off — otherwise the dialog is full of ## and ** that
     * mean nothing to a driver. Long notes are cut: this is a summary, and the
     * release page has the rest.
     */
    private fun trimNotes(notes: String): String {
        val plain = notes
            .replace(Regex("(?m)^#{1,6}\\s*"), "")          // headings
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")        // bold
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")      // bullets
            .replace(Regex("`([^`]*)`"), "$1")             // inline code
            .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")     // links: keep the text
            .replace(Regex("\\n{3,}"), "\n\n")             // runs of blank lines
            .trim()
        return if (plain.length <= MAX_NOTES) plain else plain.take(MAX_NOTES).trimEnd() + "…"
    }

    private const val MAX_NOTES = 700
    private const val DISMISS_MS = 60_000L
}
