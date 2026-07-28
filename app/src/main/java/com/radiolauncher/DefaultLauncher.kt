package com.radiolauncher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Helpers for checking / requesting to be the device's default HOME app. */
object DefaultLauncher {

    fun isDefault(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = context.packageManager.resolveActivity(intent, 0)
        return res?.activityInfo?.packageName == context.packageName
    }

    /**
     * Ask the system to make us the default launcher. On Android 10+ this shows the
     * clean role dialog; on older versions it opens the Home-app settings screen.
     */
    fun request(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = activity.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !rm.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                activity.startActivityForResult(
                    rm.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode
                )
                return
            }
        }
        openHomeSettings(activity)
    }

    private fun openHomeSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Very old / stripped units may lack this screen; nothing else we can do.
        }
    }
}
