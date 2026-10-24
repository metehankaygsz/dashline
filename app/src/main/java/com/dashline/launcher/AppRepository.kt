// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * Loads the list of launchable apps from PackageManager.
 * This is the heart of any launcher: query all activities that respond to
 * ACTION_MAIN + CATEGORY_LAUNCHER, which is exactly what a normal app icon does.
 *
 * Saved choices (favourites, card shortcuts, the phone/nav/media roles, hidden
 * apps, usage counts) are "targets": either a bare package name — what older
 * versions saved, and what the built-in defaults are — or a flattened component
 * naming one icon. Every function here takes either form.
 */
object AppRepository {

    fun loadLaunchableApps(context: Context, exclude: Set<String> = emptySet()): List<AppInfo> {
        val pm = context.packageManager
        val ownPackage = context.packageName
        val collator = LocaleManager.collator(context)

        return pm.queryIntentActivities(launcherIntent(), 0)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                val pkg = activity.packageName ?: return@mapNotNull null
                if (pkg == ownPackage) return@mapNotNull null
                // Hidden entries are saved per icon; older versions saved the
                // package, which still hides every icon that app has.
                if (pkg in exclude || targetOf(pkg, activity.name) in exclude) {
                    return@mapNotNull null
                }
                AppInfo(
                    label = resolveInfo.loadLabel(pm).toString(),
                    packageName = pkg,
                    className = activity.name,
                    icon = resolveInfo.loadIcon(pm)
                )
            }
            // One entry per icon, not per package. Collapsing by package kept
            // whichever icon PackageManager happened to list first, so a dialer
            // that also installs a Contacts icon could appear only as "Contacts".
            .distinctBy { it.target }
            // A collator, not code-point order: accented letters (Greek ά, French
            // é) belong beside their base letter, not after the whole alphabet.
            .sortedWith(compareBy(collator) { it.label })
            .toList()
    }

    /** The saved form of one icon. Readable back by [componentOf]. */
    fun targetOf(packageName: String, className: String): String =
        ComponentName(packageName, className).flattenToString()

    /** The package a target belongs to, whichever form it was saved in. */
    fun packageOf(target: String): String = componentOf(target)?.packageName ?: target

    /**
     * The icon a target actually opens, as a target. A bare package and the
     * default icon it opens are the same thing for counting and lookup; without
     * this a package-keyed count would attach to whichever of the app's icons
     * sorted first.
     */
    fun resolve(context: Context, target: String): String =
        launchIntent(context, target)?.component?.let { iconKey(context.packageManager, it) }
            ?: target

    /**
     * [component] as a drawer target. An app's default activity is usually one of
     * its icons, but an app may name a separate "front door" (CATEGORY_INFO)
     * instead; that never appears in the drawer, so count it as the app's first
     * icon rather than under a key nothing will ever match.
     */
    private fun iconKey(pm: PackageManager, component: ComponentName): String {
        if (launcherEntry(pm, component) != null) return component.flattenToString()
        val first = pm.queryIntentActivities(launcherIntent().setPackage(component.packageName), 0)
            .firstOrNull()?.activityInfo
        return if (first != null) targetOf(first.packageName, first.name)
        else component.flattenToString()
    }

    /** Try each target in order; launch the first one that's installed. */
    fun launchFirstAvailable(context: Context, targets: List<String>): Boolean {
        for (target in targets) {
            if (target.isNotEmpty() && launch(context, target)) return true
        }
        return false
    }

    /** True if the target's app is installed and launchable. */
    fun isInstalled(context: Context, target: String): Boolean =
        launchIntent(context, target) != null

    /**
     * Icon for a target — the specific icon when one was saved — or null if it's
     * gone. Read the same way the drawer reads it, so a shortcut looks like the
     * entry it was picked from.
     */
    fun iconFor(context: Context, target: String?): android.graphics.drawable.Drawable? {
        if (target.isNullOrEmpty()) return null
        val pm = context.packageManager
        componentOf(target)?.let { launcherEntry(pm, it) }?.let { return it.loadIcon(pm) }
        return try {
            pm.getApplicationIcon(packageOf(target))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Human-readable label for a target, or null if it isn't installed. */
    fun labelFor(context: Context, target: String?): String? {
        if (target.isNullOrEmpty()) return null
        val pm = context.packageManager
        componentOf(target)?.let { launcherEntry(pm, it) }?.let { return it.loadLabel(pm).toString() }
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageOf(target), 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Launch a target. Returns false if it can't be launched (e.g. it was
     * uninstalled), so the caller can show feedback.
     */
    fun launch(context: Context, target: String): Boolean {
        val pm = context.packageManager
        val intent = launchIntent(context, target) ?: return false
        if (start(context, intent)) {
            Usage.record(context, intent.component?.let { iconKey(pm, it) } ?: target)
            return true
        }
        // A saved icon can stop being startable without disappearing from the
        // launcher list; the app's default icon is still worth a try — unless
        // that's what just failed.
        if (componentOf(target) == null) return false
        val fallback = pm.getLaunchIntentForPackage(packageOf(target)) ?: return false
        if (fallback.component == intent.component || !start(context, fallback)) return false
        Usage.record(context, fallback.component?.let { iconKey(pm, it) } ?: target)
        return true
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The saved icon while it's still a launcher entry, otherwise the app's
     * default one. An update can drop a secondary icon, and opening the app is
     * still better than reporting it missing.
     */
    private fun launchIntent(context: Context, target: String): Intent? {
        val pm = context.packageManager
        componentOf(target)?.let { component ->
            if (launcherEntry(pm, component) != null) {
                return launcherIntent().setComponent(component)
            }
        }
        return pm.getLaunchIntentForPackage(packageOf(target))
    }

    private fun componentOf(target: String): ComponentName? =
        if ('/' in target) ComponentName.unflattenFromString(target) else null

    /**
     * [component] as the drawer sees it — or null if it's no longer a launcher
     * icon. Resolving the bare component would also accept an activity that lost
     * its launcher filter (and with it, usually, its export), which then fails
     * to start.
     */
    private fun launcherEntry(pm: PackageManager, component: ComponentName): ResolveInfo? =
        pm.queryIntentActivities(launcherIntent().setPackage(component.packageName), 0)
            .firstOrNull { it.activityInfo?.name == component.className }

    private fun launcherIntent(): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}
