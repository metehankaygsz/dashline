// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.graphics.drawable.Drawable

/**
 * A single launchable entry shown in the drawer or as a favorite.
 *
 * One app can have several of these: a dialer that also installs a Contacts
 * icon is two entries with the same [packageName] and different [className].
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val className: String,
    val icon: Drawable
) {
    /**
     * What gets saved when this entry is picked, and what [AppRepository]
     * launches. Naming the activity, not just the package, is what makes the
     * second icon of a multi-icon app open that icon rather than the first.
     * Computed once: lookups compare it for every icon on every resume.
     */
    val target: String = AppRepository.targetOf(packageName, className)
}
