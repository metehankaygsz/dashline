package com.radiolauncher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Runtime-permission helpers shared by Setup, Settings and the dashboard. */
object Permissions {

    private val LOCATION = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /** Location is optional — it only powers the weather panel. */
    fun hasLocation(context: Context): Boolean = LOCATION.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requestLocation(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, LOCATION, requestCode)
    }

    /**
     * True when the user has permanently denied the permission, so a request
     * dialog would no longer appear and we must send them to app settings.
     */
    fun locationPermanentlyDenied(activity: Activity): Boolean =
        !hasLocation(activity) && LOCATION.none {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }

    /** Open this app's system settings page (to re-enable a denied permission). */
    fun openAppSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Some stripped head units lack this screen; nothing else we can do.
        }
    }
}
