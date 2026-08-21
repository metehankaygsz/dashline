// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Hosts real Android app widgets in the dashboard.
 *
 * `AppWidgetHost` is the only sanctioned way for one app to render another's
 * content, and it's a launcher's privilege to use it. Binding needs the user's
 * consent, which the system asks for via ACTION_APPWIDGET_BIND — a normal app
 * can't grant itself BIND_APPWIDGET.
 */
object WidgetHost {

    /** Arbitrary but must stay stable, or previously bound widgets are orphaned. */
    private const val HOST_ID = 0x0DA5

    /**
     * One host for the whole process.
     *
     * This has to be a singleton. The framework keeps a single callback per host
     * id, so a second AppWidgetHost with the same id takes delivery away from the
     * first: the dashboard's widgets would then be waiting for RemoteViews that
     * are being handed to the editor's host instead, and sit on their "Loading…"
     * placeholder forever.
     */
    private var host: AppWidgetHost? = null

    /** Screens currently showing widgets, so the last one out stops listening. */
    private var screens = 0
    private var listening = false

    fun host(context: Context): AppWidgetHost = host ?: AppWidgetHost(
        context.applicationContext, HOST_ID
    ).also { host = it }

    /** Call from onResume of any screen that shows widgets. */
    fun attach(context: Context) {
        screens++
        startListening(context)
    }

    /** Call from onPause. Listening stops only once nothing is showing widgets. */
    fun detach() {
        screens = (screens - 1).coerceAtLeast(0)
        if (screens == 0 && listening) {
            runCatching { host?.stopListening() }
            listening = false
        }
    }

    /**
     * Updates only arrive while the host is listening, and a repeated
     * startListening re-registers the callback for no reason, so this is
     * idempotent.
     */
    private fun startListening(context: Context) {
        if (listening) return
        runCatching { host(context).startListening() }
        listening = true
    }

    fun manager(context: Context): AppWidgetManager =
        AppWidgetManager.getInstance(context.applicationContext)

    /**
     * Every widget the device can offer, in display order.
     *
     * We list providers ourselves rather than firing ACTION_APPWIDGET_PICK: that
     * picker binds the chosen widget on the caller's behalf, which needs the
     * signature-level BIND_APPWIDGET permission. A normal app never has it, so
     * the picker either refuses outright or hands back an id it never bound —
     * which is why choosing a widget used to fall straight back to the phone
     * card. Listing + [bind] keeps the whole flow in permissions we can actually
     * get.
     */
    fun providers(context: Context): List<AppWidgetProviderInfo> =
        runCatching {
            manager(context).installedProviders
                .sortedBy { it.loadLabel(context.packageManager).orEmpty().lowercase() }
        }.getOrDefault(emptyList())

    /** Reserve an id for a widget we're about to bind. */
    fun allocateId(host: AppWidgetHost): Int =
        runCatching { host.allocateAppWidgetId() }.getOrDefault(INVALID_ID)

    /**
     * Bind without user interaction. Only succeeds when the user has already
     * allowed this host to bind widgets, so a false here is normal, not an
     * error — the caller falls through to [requestBind].
     */
    fun bind(context: Context, widgetId: Int, provider: AppWidgetProviderInfo): Boolean =
        runCatching {
            manager(context).bindAppWidgetIdIfAllowed(widgetId, provider.provider)
        }.getOrDefault(false)

    /**
     * Step 2: some widgets need a configuration activity before they'll render.
     * Returns true when one was launched and the caller should wait for it.
     */
    fun configureIfNeeded(activity: Activity, widgetId: Int, requestCode: Int): Boolean {
        val info = manager(activity).getAppWidgetInfo(widgetId) ?: return false
        val configure = info.configure ?: return false
        return try {
            activity.startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                requestCode
            )
            true
        } catch (e: Exception) {
            // Some widgets declare a configure activity they don't export.
            false
        }
    }

    /**
     * Build the view for an already-bound widget, or null if it can't be shown.
     *
     * Three things matter here and each one crashed a build at some point:
     *  - the host must be listening before createView, or the returned view
     *    never receives its RemoteViews and can throw on first layout;
     *  - createView already calls setAppWidget internally — calling it again
     *    re-inflates and throws;
     *  - the view must be built with the *activity* context, since it goes into
     *    an activity's hierarchy and needs its theme.
     */
    fun createView(
        context: Context,
        host: AppWidgetHost,
        widgetId: Int
    ): AppWidgetHostView? {
        if (widgetId == INVALID_ID) return null
        return try {
            val info: AppWidgetProviderInfo =
                manager(context).getAppWidgetInfo(widgetId) ?: return null
            startListening(context)
            host.createView(context, widgetId, info)
        } catch (e: Throwable) {
            // Providers can throw anything at all from their RemoteViews.
            null
        }
    }

    /**
     * Give a widget row a height.
     *
     * A widget asks for as much room as its provider's minHeight wants, so in any
     * container that wraps its content — the portrait cards, and the clock panel
     * in both orientations — it has to be told, or one widget swallows the whole
     * screen. Only a card that already has a fixed height can let it fill.
     */
    fun sizeContainer(container: android.view.View, fill: Boolean) {
        val density = container.resources.displayMetrics.density
        container.layoutParams = container.layoutParams?.apply {
            height = if (fill) {
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                (PANEL_HEIGHT_DP * density).toInt()
            }
        } ?: return
    }

    /** Room a widget gets where the container would otherwise wrap it. */
    const val PANEL_HEIGHT_DP = 180f

    /** Tell the widget how much room it has, so it can pick a layout. */
    fun resize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        if (widthDp <= 0 || heightDp <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            runCatching { view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp) }
        }
    }

    /** Last size handed to each widget, so a layout pass can't loop. */
    private val sized = java.util.WeakHashMap<AppWidgetHostView, Long>()

    /**
     * Keep the provider told of the widget's real size, for as long as the view
     * lives.
     *
     * This is not a nicety — for a large class of widgets it is the difference
     * between content and a permanent "Loading…". Anything built with Glance
     * (Google's Battery, Maps' Nearby Traffic, At a Glance, and most modern
     * widgets) derives its layout from the size options and emits nothing at all
     * until it has them. Classic RemoteViews widgets like the Clock render
     * without, which is why those worked while the rest sat on their
     * placeholder.
     *
     * A one-shot post() also isn't enough: it can run before the first layout
     * pass, when the view is still 0x0 and there is no size to send.
     */
    fun sizeOnLayout(context: Context, view: AppWidgetHostView, widgetId: Int) {
        applySize(context, view, widgetId)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            applySize(context, v as? AppWidgetHostView ?: return@addOnLayoutChangeListener, widgetId)
        }
    }

    private fun applySize(context: Context, view: AppWidgetHostView, widgetId: Int) {
        val density = view.resources.displayMetrics.density
        val width = (view.width / density).toInt()
        val height = (view.height / density).toInt()
        if (width <= 0 || height <= 0) return

        val key = (width.toLong() shl 32) or height.toLong()
        if (sized[view] == key) return
        sized[view] = key

        resize(view, width, height)
        publishSize(context, widgetId, width, height)
    }

    /**
     * Write the size into the widget's options ourselves rather than relying on
     * the view helper alone. This is what wakes a Glance widget up: it delivers
     * onAppWidgetOptionsChanged to the provider, which is its cue to produce
     * content.
     */
    private fun publishSize(context: Context, widgetId: Int, widthDp: Int, heightDp: Int) {
        if (widgetId == INVALID_ID) return
        val options = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            // From API 31 widgets are given a list of sizes they may be shown at;
            // Glance reads this one in preference to the four above.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    arrayListOf(android.util.SizeF(widthDp.toFloat(), heightDp.toFloat()))
                )
            }
        }
        runCatching { manager(context).updateAppWidgetOptions(widgetId, options) }
    }

    /** Provider name for a bound widget, for menus. Null if it's gone. */
    fun label(context: Context, widgetId: Int): String? =
        manager(context).getAppWidgetInfo(widgetId)
            ?.loadLabel(context.packageManager)
            ?.toString()

    /** Rendering an unbound id throws, so callers check before creating a view. */
    fun isBound(context: Context, widgetId: Int): Boolean =
        widgetId != INVALID_ID && manager(context).getAppWidgetInfo(widgetId) != null

    /**
     * Ask the system to bind on our behalf; it shows the user a consent dialog.
     *
     * The provider extra is mandatory — without it the dialog has nothing to
     * name and the request is rejected, which is the second half of why picking
     * a widget silently did nothing.
     */
    fun requestBind(
        activity: Activity,
        widgetId: Int,
        provider: AppWidgetProviderInfo,
        requestCode: Int
    ): Boolean = try {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intent.putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile
            )
        }
        activity.startActivityForResult(intent, requestCode)
        true
    } catch (e: Exception) {
        // Head units with a stripped-down framework may not have the activity.
        false
    }

    fun delete(host: AppWidgetHost, widgetId: Int) {
        if (widgetId != INVALID_ID) runCatching { host.deleteAppWidgetId(widgetId) }
    }

    const val INVALID_ID = -1
}
