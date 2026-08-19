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

    fun host(context: Context): AppWidgetHost =
        AppWidgetHost(context.applicationContext, HOST_ID)

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
            runCatching { host.startListening() }
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
