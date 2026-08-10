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
     * Step 1: reserve an id, then let the user choose a provider. The system
     * picker handles permission and provider listing for us.
     */
    fun pick(activity: Activity, host: AppWidgetHost, requestCode: Int) {
        val id = host.allocateAppWidgetId()
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            // No custom items — we only want real widgets.
            .putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, ArrayList())
            .putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, ArrayList())
        activity.startActivityForResult(intent, requestCode)
    }

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

    /** Tell the widget how much room it has, so it can pick a layout. */
    fun resize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        if (widthDp <= 0 || heightDp <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            runCatching { view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp) }
        }
    }

    /**
     * The system picker usually binds for us, but some OEM pickers return an id
     * without binding it. Rendering an unbound id throws, so check first and ask
     * the user explicitly when needed.
     */
    fun isBound(context: Context, widgetId: Int): Boolean =
        widgetId != INVALID_ID && manager(context).getAppWidgetInfo(widgetId) != null

    fun requestBind(activity: Activity, widgetId: Int, requestCode: Int): Boolean = try {
        activity.startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            requestCode
        )
        true
    } catch (e: Exception) {
        false
    }

    fun delete(host: AppWidgetHost, widgetId: Int) {
        if (widgetId != INVALID_ID) runCatching { host.deleteAppWidgetId(widgetId) }
    }

    const val INVALID_ID = -1
}
