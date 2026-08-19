// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.graphics.drawable.Drawable

/** A single launchable app shown in the drawer or as a favorite. */
data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable
)
