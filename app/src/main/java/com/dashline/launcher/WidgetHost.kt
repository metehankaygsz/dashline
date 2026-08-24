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
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup

/**
 * Hosts real Android app widgets in the dashboard.
 *
 * `AppWidgetHost` is the only sanctioned way for one app to render another's
 * content, and it's a launcher's privilege to use it. Binding needs the user's
 * consent, which the system asks for via ACTION_APPWIDGET_BIND — a normal app
 * can't grant itself BIND_APPWIDGET.
 *
 * Everything here is main-thread only: it is called from activity lifecycle and
 * view callbacks, and the state below is deliberately unsynchronised.
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
     * placeholder forever. Nothing outside this file may construct one, which is
     * why no function here takes a host as a parameter.
     */
    private var host: AppWidgetHost? = null

    /** Screens currently showing widgets, so the last one out stops listening. */
    private var screens = 0
    private var listening = false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Ids reserved by [allocateId] that aren't in the user's settings yet.
     *
     * Between reserving an id and the user finishing with the consent and
     * configuration dialogs, the id belongs to nothing — [sweepOrphans] must not
     * reclaim it out from under a bind that's still in progress.
     */
    private val reserved = mutableSetOf<Int>()

    private fun host(context: Context): AppWidgetHost = host ?: AppWidgetHost(
        context.applicationContext, HOST_ID
    ).also { host = it }

    /** Call from onResume of any screen that shows widgets. */
    fun attach(context: Context) {
        screens++
        handler.removeCallbacks(stopListening)
        startListening(context)
    }

    /**
     * Call from onPause.
     *
     * The stop is deferred, because moving between two screens that both show
     * widgets pauses one before resuming the next: stopping immediately would
     * drop the host's callback and re-register it on every navigation, and any
     * update arriving in that gap has to be recovered from the framework's
     * cache. A short delay makes the common case a no-op.
     */
    fun detach() {
        screens = (screens - 1).coerceAtLeast(0)
        if (screens == 0) {
            handler.removeCallbacks(stopListening)
            handler.postDelayed(stopListening, STOP_DELAY_MS)
        }
    }

    private val stopListening = Runnable {
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
    fun allocateId(context: Context): Int {
        val id = runCatching { host(context).allocateAppWidgetId() }.getOrDefault(INVALID_ID)
        if (id != INVALID_ID) reserved += id
        return id
    }

    /** The id is now in the user's settings (or gone); it no longer needs shielding. */
    fun settled(widgetId: Int) {
        reserved -= widgetId
    }

    /**
     * Give back ids the framework still thinks we own but nothing refers to.
     *
     * Every allocated id keeps its provider bound, and an id whose bind was
     * interrupted — the process dying behind the consent dialog, say — is
     * referenced by nothing and would otherwise leak for the life of the
     * install.
     */
    fun sweepOrphans(context: Context, keep: Set<Int>) {
        // AppWidgetHost only started reporting its own ids in Oreo; before that
        // there is no way to enumerate them, so old units simply keep them.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val current = host(context)
        val owned = runCatching { current.appWidgetIds }.getOrNull() ?: return
        owned.forEach { id ->
            if (id !in keep && id !in reserved) {
                runCatching { current.deleteAppWidgetId(id) }
            }
        }
    }

    /**
     * True only when the framework itself no longer has this widget.
     *
     * The distinction matters: a provider being updated makes
     * `getAppWidgetInfo` return null for a few seconds while the binding stays
     * perfectly valid, whereas an uninstalled or disabled provider has its
     * binding dropped outright. Deleting the user's settings is only safe in the
     * second case — and before Oreo there's no way to tell the two apart, so
     * nothing is ever deleted there.
     */
    fun isGoneForGood(context: Context, widgetId: Int): Boolean {
        if (widgetId == INVALID_ID) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (widgetId in reserved) return false
        if (manager(context).getAppWidgetInfo(widgetId) != null) return false
        val owned = runCatching { host(context).appWidgetIds }.getOrNull() ?: return false
        return widgetId !in owned
    }

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
     * Build the view for an already-bound widget, or null if it can't be shown
     * *right now*.
     *
     * Null is not proof that the widget is gone for good: `getAppWidgetInfo`
     * returns null for a few seconds while the provider's app is being updated,
     * and a provider can throw from its own RemoteViews at any time. Callers
     * must treat it as "unavailable" and leave the user's settings alone.
     *
     * Three things matter here and each one crashed a build at some point:
     *  - the host must be listening before createView, or the returned view
     *    never receives its RemoteViews and can throw on first layout;
     *  - createView already calls setAppWidget internally — calling it again
     *    re-inflates and throws;
     *  - the view must be built with the *activity* context, since it goes into
     *    an activity's hierarchy and needs its theme.
     */
    fun createView(context: Context, widgetId: Int): AppWidgetHostView? {
        if (widgetId == INVALID_ID) return null
        return try {
            val info: AppWidgetProviderInfo =
                manager(context).getAppWidgetInfo(widgetId) ?: return null
            startListening(context)
            host(context).createView(context, widgetId, info)
        } catch (e: Throwable) {
            // Providers can throw anything at all from their RemoteViews, so this
            // is deliberately wide — but not so wide that it hides our own
            // failures or an OutOfMemoryError.
            if (e is Error) throw e
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
    fun sizeContainer(container: View, fill: Boolean) {
        val density = container.resources.displayMetrics.density
        container.layoutParams = container.layoutParams?.apply {
            height = if (fill) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                (PANEL_HEIGHT_DP * density).toInt()
            }
        } ?: return
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
        // The application context: this listener outlives nothing, but holding an
        // Activity from a view callback is a leak waiting for a mistake.
        val app = context.applicationContext
        applySize(app, view, widgetId)

        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            (v as? AppWidgetHostView)?.let { applySize(app, it, widgetId) }
        }
        view.addOnLayoutChangeListener(listener)
        // Views are replaced whenever a card is rebuilt, so drop the listener with
        // the view rather than leaving them to pile up.
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnLayoutChangeListener(listener)
                v.removeOnAttachStateChangeListener(this)
            }
        })
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

    /** Tell the widget how much room it has, so it can pick a layout. */
    private fun resize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        if (widthDp <= 0 || heightDp <= 0) return
        runCatching { view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp) }
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

    fun delete(context: Context, widgetId: Int) {
        if (widgetId == INVALID_ID) return
        reserved -= widgetId
        runCatching { host(context).deleteAppWidgetId(widgetId) }
    }

    /** Room a widget gets where the container would otherwise wrap it. */
    private const val PANEL_HEIGHT_DP = 180f

    /** Long enough to cover a screen change, short enough to still release. */
    private const val STOP_DELAY_MS = 2_000L

    const val INVALID_ID = -1
}
