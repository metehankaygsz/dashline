// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.service.notification.NotificationListenerService

/**
 * Empty notification listener. We don't handle notifications — its only purpose is
 * to let the user grant "Notification access", which is what unlocks
 * MediaSessionManager.getActiveSessions() so we can read the now-playing media.
 */
class MediaNotificationListener : NotificationListenerService()
