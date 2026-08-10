package com.dashline.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.dashline.launcher.databinding.ActivityCustomizeBinding
import com.dashline.launcher.databinding.ActivityHomeBinding

/**
 * Live layout editor for the dashboard.
 *
 * The preview is the *real* activity_home layout inflated into this screen, not a
 * mock-up — so it can't drift from the actual dashboard, and what the user drags
 * is exactly what they'll get. Its content is filled with representative
 * placeholders since none of the live sources (media session, weather) are
 * running here.
 */
class CustomizeActivity : BaseActivity() {

    private lateinit var binding: ActivityCustomizeBinding
    private lateinit var preview: ActivityHomeBinding
    private lateinit var prefs: Prefs

    private var widgetHost: AppWidgetHost? = null
    private var dragHandle: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.header.pageTitle.setText(R.string.settings_customize)
        bindPageNav(binding.header.navBack, binding.header.navHome)

        preview = ActivityHomeBinding.inflate(layoutInflater, binding.previewFrame, true)
        fillPlaceholders()
        makePreviewInert()
        addDragHandle()

        binding.btnSwap.setOnClickListener {
            prefs.panelOrder =
                if (prefs.panelOrder == Prefs.PANEL_PHONE_FIRST) Prefs.PANEL_MEDIA_FIRST
                else Prefs.PANEL_PHONE_FIRST
            applyToPreview()
        }
        binding.btnSecondCard.setOnClickListener { chooseSecondCard() }
        binding.btnReset.setOnClickListener {
            prefs.mediaFraction = 0.72f
            prefs.panelOrder = Prefs.PANEL_MEDIA_FIRST
            prefs.secondCardMode = Prefs.SECOND_PHONE
            applyToPreview()
        }

        applyToPreview()
    }

    // ---- preview -----------------------------------------------------------

    /** The live sources aren't running here, so show representative content. */
    private fun fillPlaceholders() {
        preview.bigClock.text = "12:34"
        preview.bigDate.text = getString(R.string.customize_sample_date)
        preview.weatherTemp.text = "21°"
        preview.weatherDesc.setText(R.string.wx_partly_cloudy)
        preview.mediaTitle.setText(R.string.customize_sample_track)
        preview.mediaArtist.setText(R.string.customize_sample_artist)
        preview.mediaControls.visibility = View.VISIBLE
        preview.mediaSeekRow.visibility = View.VISIBLE
        preview.phoneLabel.setText(R.string.tab_phone)

        // Bottom bar mirrors the user's real tab choices.
        preview.tabBar.removeAllViews()
        prefs.visibleTabs().forEach { tab ->
            val item = com.dashline.launcher.databinding.ItemTabBinding
                .inflate(layoutInflater, preview.tabBar, false)
            item.tabIcon.setImageResource(tab.iconRes)
            item.tabLabel.setText(tab.labelRes)
            preview.tabBar.addView(
                item.root,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
        }
    }

    /** Nothing in the preview should launch anything. */
    private fun makePreviewInert() {
        listOf<View>(
            preview.mediaCard, preview.phoneCard, preview.homeButton,
            preview.shortcutAndroidAuto, preview.shortcutCarPlay,
            preview.mediaProgress, preview.volumeSlider
        ).forEach {
            it.setOnClickListener(null)
            it.isClickable = false
            it.isFocusable = false
        }
        preview.mediaProgress.isEnabled = false
        preview.volumeSlider.isEnabled = false
        preview.root.background = GradientThemes.background(
            this, GradientThemes.current(this)
        )
    }

    /**
     * A grab bar sitting between the two cards. Dragging it rewrites the media
     * fraction continuously, so the size is set by feel rather than by picking
     * from a list.
     */
    private fun addDragHandle() {
        val column = preview.mediaCard.parent as? LinearLayout ?: return
        val handle = View(this).apply {
            setBackgroundResource(R.drawable.drag_handle)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (18 * resources.displayMetrics.density).toInt()
            )
        }
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                    val height = column.height.toFloat()
                    if (height > 0f) {
                        // Position within the column becomes the split point.
                        val y = event.rawY - intArrayOf(0, 0).also {
                            column.getLocationOnScreen(it)
                        }[1]
                        val f = (y / height).coerceIn(Prefs.MIN_FRACTION, Prefs.MAX_FRACTION)
                        val mediaFirst = prefs.panelOrder != Prefs.PANEL_PHONE_FIRST
                        prefs.mediaFraction = if (mediaFirst) f else 1f - f
                        applyToPreview()
                    }
                    true
                }
                else -> true
            }
        }
        column.addView(handle, 1)
        dragHandle = handle
    }

    /** Re-applies order, split and second-card mode to the preview. */
    private fun applyToPreview() {
        val column = preview.mediaCard.parent as? LinearLayout ?: return
        val handle = dragHandle

        column.removeView(preview.mediaCard)
        column.removeView(preview.phoneCard)
        handle?.let { column.removeView(it) }

        val mediaFirst = prefs.panelOrder != Prefs.PANEL_PHONE_FIRST
        val first = if (mediaFirst) preview.mediaCard else preview.phoneCard
        val second = if (mediaFirst) preview.phoneCard else preview.mediaCard
        column.addView(first, 0)
        handle?.let { column.addView(it, 1) }
        column.addView(second, if (handle != null) 2 else 1)

        val fraction = prefs.mediaFraction
        weight(preview.mediaCard, fraction)
        weight(preview.phoneCard, 1f - fraction)

        applySecondCardPreview()
        binding.customizeHint.text = getString(
            R.string.customize_hint_split, (fraction * 100).toInt()
        )
    }

    private fun weight(card: View, w: Float) {
        val lp = card.layoutParams as? LinearLayout.LayoutParams ?: return
        lp.height = 0
        lp.weight = w
        (card as? android.view.ViewGroup)?.minimumHeight = 0
        card.layoutParams = lp
    }

    private fun applySecondCardPreview() {
        val mode = prefs.secondCardMode
        preview.phoneDefault.visibility =
            if (mode == Prefs.SECOND_PHONE) View.VISIBLE else View.GONE
        preview.panelShortcuts.visibility =
            if (mode == Prefs.SECOND_SHORTCUTS) View.VISIBLE else View.GONE
        preview.panelWidget.visibility =
            if (mode == Prefs.SECOND_WIDGET) View.VISIBLE else View.GONE

        if (mode == Prefs.SECOND_SHORTCUTS) renderShortcutSlots()
        if (mode == Prefs.SECOND_WIDGET) renderWidgetPreview()
    }

    private fun renderShortcutSlots() {
        val row = preview.panelShortcuts
        row.removeAllViews()
        for (index in 0 until Prefs.PANEL_SHORTCUT_COUNT) {
            val pkg = prefs.getPanelShortcut(index)
            val item = com.dashline.launcher.databinding.ItemAppBinding
                .inflate(layoutInflater, row, false)
            if (pkg != null && AppRepository.isInstalled(this, pkg)) {
                item.appIcon.setImageDrawable(AppRepository.iconFor(this, pkg))
                item.appLabel.text = AppRepository.labelFor(this, pkg)
            } else {
                item.appIcon.setImageResource(R.drawable.ic_add)
                item.appLabel.text = ""
            }
            // In the editor, tapping a slot assigns it.
            item.root.setOnClickListener {
                AppPickerActivity.start(this, Prefs.ROLE_FAVORITE, REQ_SHORTCUT_BASE + index)
            }
            row.addView(
                item.root,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun renderWidgetPreview() {
        val container = preview.panelWidget
        container.removeAllViews()
        val id = prefs.panelWidgetId
        val view = if (id == WidgetHost.INVALID_ID) null
        else WidgetHost.createView(this, host(), id)

        if (view == null) {
            val prompt = layoutInflater.inflate(R.layout.view_widget_empty, container, false)
            prompt.setOnClickListener { pickWidget() }
            container.addView(prompt)
            return
        }
        container.addView(view)
        // Long-press to swap the widget out.
        container.setOnLongClickListener { pickWidget(); true }
    }

    // ---- second card mode ---------------------------------------------------

    private fun chooseSecondCard() {
        val options = listOf(
            Prefs.SECOND_PHONE to R.string.second_phone,
            Prefs.SECOND_SHORTCUTS to R.string.second_shortcuts,
            Prefs.SECOND_WIDGET to R.string.second_widget
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_second_card)
            .setItems(options.map { getString(it.second) }.toTypedArray()) { _, which ->
                val mode = options[which].first
                prefs.secondCardMode = mode
                if (mode == Prefs.SECOND_WIDGET && prefs.panelWidgetId == WidgetHost.INVALID_ID) {
                    pickWidget()
                } else {
                    applyToPreview()
                }
            }
            .show()
    }

    private fun host(): AppWidgetHost {
        var h = widgetHost
        if (h == null) {
            h = WidgetHost.host(this)
            widgetHost = h
        }
        return h
    }

    private fun pickWidget() {
        // Drop any previous binding so we don't leak reserved ids.
        WidgetHost.delete(host(), prefs.panelWidgetId)
        prefs.panelWidgetId = WidgetHost.INVALID_ID
        WidgetHost.pick(this, host(), REQ_PICK_WIDGET)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val widgetId = data?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, WidgetHost.INVALID_ID
        ) ?: WidgetHost.INVALID_ID

        when {
            requestCode == REQ_PICK_WIDGET && resultCode == Activity.RESULT_OK -> {
                // Some widgets insist on a configuration step before they render.
                if (!WidgetHost.configureIfNeeded(this, widgetId, REQ_CONFIGURE_WIDGET)) {
                    commitWidget(widgetId)
                }
            }
            requestCode == REQ_PICK_WIDGET -> {
                WidgetHost.delete(host(), widgetId)
                if (prefs.panelWidgetId == WidgetHost.INVALID_ID) {
                    prefs.secondCardMode = Prefs.SECOND_PHONE
                }
                applyToPreview()
            }
            requestCode == REQ_CONFIGURE_WIDGET && resultCode == Activity.RESULT_OK ->
                commitWidget(widgetId)
            requestCode == REQ_CONFIGURE_WIDGET -> {
                WidgetHost.delete(host(), widgetId)
                prefs.secondCardMode = Prefs.SECOND_PHONE
                applyToPreview()
            }
            requestCode >= REQ_SHORTCUT_BASE -> {
                data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)?.let { pkg ->
                    prefs.setPanelShortcut(requestCode - REQ_SHORTCUT_BASE, pkg)
                }
                applyToPreview()
            }
        }
    }

    private fun commitWidget(widgetId: Int) {
        prefs.panelWidgetId = widgetId
        prefs.secondCardMode = Prefs.SECOND_WIDGET
        applyToPreview()
    }

    override fun onResume() {
        super.onResume()
        runCatching { host().startListening() }
    }

    override fun onPause() {
        super.onPause()
        runCatching { widgetHost?.stopListening() }
    }

    private companion object {
        const val REQ_PICK_WIDGET = 900
        const val REQ_CONFIGURE_WIDGET = 901
        const val REQ_SHORTCUT_BASE = 910
    }
}
