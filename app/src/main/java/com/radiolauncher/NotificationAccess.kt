package com.radiolauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Helpers for the "Notification access" permission that gates media reading. */
object NotificationAccess {

    fun isGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        // The setting is a colon-separated list of ComponentName flattenings.
        return flat.split(":").any { it.contains(context.packageName) }
    }

    /** Open the system screen where the user toggles notification access on. */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Some minimal head units lack this settings screen; ignore.
        }
    }
}
