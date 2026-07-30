package com.dashline.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Loads the list of launchable apps from PackageManager.
 * This is the heart of any launcher: query all activities that respond to
 * ACTION_MAIN + CATEGORY_LAUNCHER, which is exactly what a normal app icon does.
 */
object AppRepository {

    fun loadLaunchableApps(context: Context, exclude: Set<String> = emptySet()): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val activities = pm.queryIntentActivities(intent, 0)
        val ownPackage = context.packageName

        return activities
            .asSequence()
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName ?: return@mapNotNull null
                // Don't list ourselves, or apps the user has hidden.
                if (pkg == ownPackage || pkg in exclude) return@mapNotNull null
                AppInfo(
                    label = resolveInfo.loadLabel(pm).toString(),
                    packageName = pkg,
                    icon = resolveInfo.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Try each package in order; launch the first one that's installed. */
    fun launchFirstAvailable(context: Context, packages: List<String>): Boolean {
        for (pkg in packages) {
            if (pkg.isNotEmpty() && launch(context, pkg)) return true
        }
        return false
    }

    /** True if the package is installed and launchable. */
    fun isInstalled(context: Context, packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    /** App icon for a package, or null if it isn't installed. */
    fun iconFor(context: Context, packageName: String?): android.graphics.drawable.Drawable? {
        if (packageName.isNullOrEmpty()) return null
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Human-readable label for a package, or null if it isn't installed. */
    fun labelFor(context: Context, packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Launch an app by package name. Returns false if it can't be launched
     * (e.g. it was uninstalled), so the caller can show feedback.
     */
    fun launch(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            Usage.record(context, packageName)
            true
        } catch (e: Exception) {
            false
        }
    }
}
