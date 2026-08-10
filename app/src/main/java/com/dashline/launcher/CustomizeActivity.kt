package com.dashline.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
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
    /** Id reserved for a widget that's mid-bind; the result may not carry it. */
    private var pendingWidgetId = WidgetHost.INVALID_ID
    private var dragHandle: View? = null
    private var dragFraction = 0.72f
    private var dragging = false

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
        paintPreviewAccent()
    }

    /**
     * Mirrors HomeActivity.applyAccent. The preview is only useful if it looks
     * like the dashboard, and the media card is themed in code rather than XML.
     */
    private fun paintPreviewAccent() {
        val accent = accentColor()
        val density = resources.displayMetrics.density
        val night = GradientThemes.isNight(this)

        val cardColor =
            if (night) GradientThemes.darken(accent, 0.30f)
            else GradientThemes.withAlpha(accent, 0x33)
        preview.mediaCard.background = GradientThemes.roundedRect(cardColor, 8f * density)
        preview.btnPlayPause.background = GradientThemes.roundedRect(accent, 25f * density)
    }

    /**
     * A grab bar between the two cards. Dragging rewrites the split continuously.
     *
     * The move handler deliberately does as little as possible: no preference
     * writes (that's a disk commit per touch event) and no view re-parenting.
     * It only adjusts the two weights and lets the parent re-measure. The value
     * is persisted once, on release.
     */
    private fun addDragHandle() {
        if (!isLandscape()) return   // portrait stacks and scrolls; weights don't apply
        val column = preview.mediaCard.parent as? LinearLayout ?: return

        val handle = View(this).apply {
            setBackgroundResource(R.drawable.drag_handle)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (22 * resources.displayMetrics.density).toInt()
            )
        }
        val loc = IntArray(2)   // reused; allocating per event causes GC churn

        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val height = column.height.toFloat()
                    if (height > 0f) {
                        column.getLocationOnScreen(loc)
                        val ratio = ((event.rawY - loc[1]) / height)
                            .coerceIn(Prefs.MIN_FRACTION, Prefs.MAX_FRACTION)
                        val mediaFirst = prefs.panelOrder != Prefs.PANEL_PHONE_FIRST
                        dragFraction = if (mediaFirst) ratio else 1f - ratio
                        applyWeights(dragFraction)
                        showSplitHint(dragFraction)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                    prefs.mediaFraction = dragFraction   // single commit
                    true
                }
                else -> false
            }
        }
        column.addView(handle, 1)
        dragHandle = handle
    }

    /** Cheap path used while dragging — weights only, no re-parenting. */
    private fun applyWeights(fraction: Float) {
        weight(preview.mediaCard, fraction)
        weight(preview.phoneCard, 1f - fraction)
        applyMediaDensityPreview(fraction)
    }

    private fun showSplitHint(fraction: Float) {
        binding.customizeHint.text =
            getString(R.string.customize_hint_split, (fraction * 100).toInt())
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** Re-applies order, split and second-card mode to the preview. */
    private fun applyToPreview() {
        if (dragging) return   // the drag path owns the layout until release
        val column = preview.mediaCard.parent as? LinearLayout ?: return
        val handle = dragHandle

        // In portrait the cards sit after the clock panel, so reorder relative to
        // wherever they currently are rather than assuming index 0.
        val base = minOf(
            column.indexOfChild(preview.mediaCard),
            column.indexOfChild(preview.phoneCard)
        ).coerceAtLeast(0)

        column.removeView(preview.mediaCard)
        column.removeView(preview.phoneCard)
        handle?.let { column.removeView(it) }

        val mediaFirst = prefs.panelOrder != Prefs.PANEL_PHONE_FIRST
        val first = if (mediaFirst) preview.mediaCard else preview.phoneCard
        val second = if (mediaFirst) preview.phoneCard else preview.mediaCard

        var at = base
        column.addView(first, at++)
        handle?.let { column.addView(it, at++) }
        column.addView(second, at)

        dragFraction = prefs.mediaFraction
        if (isLandscape()) {
            applyWeights(dragFraction)
            showSplitHint(dragFraction)
        } else {
            // No weights in portrait — say so instead of showing a meaningless %.
            binding.customizeHint.setText(R.string.customize_hint_portrait)
        }

        applySecondCardPreview()
        // The preview inflates its own views, so it needs the icon tinting too.
        tintIcons(preview.root)
    }

    /** Mirrors HomeActivity: hide controls before they get cramped. */
    private fun applyMediaDensityPreview(fraction: Float) {
        val density = resources.displayMetrics.density
        val showVolume = fraction >= 0.46f
        val showControls = fraction >= 0.34f
        val showSeek = fraction >= 0.56f

        preview.mediaVolumeRow.visibility = if (showVolume) View.VISIBLE else View.GONE
        preview.mediaControls.visibility = if (showControls) View.VISIBLE else View.GONE
        preview.mediaSeekRow.visibility = if (showSeek) View.VISIBLE else View.GONE

        val art = ((if (fraction < 0.46f) 52f else 86f) * density).toInt()
        preview.mediaArt.layoutParams = preview.mediaArt.layoutParams.apply {
            width = art
            height = art
        }
        preview.mediaArt.requestLayout()
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
                item.appIcon.clearColorFilter()
                item.appLabel.text = AppRepository.labelFor(this, pkg)
            } else {
                item.appIcon.setImageResource(R.drawable.ic_add)
                item.appIcon.setColorFilter(
                    accentColor(), android.graphics.PorterDuff.Mode.SRC_IN
                )
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
        runCatching {
            container.addView(
                view,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
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

    /**
     * Choose a widget from our own list of installed providers.
     *
     * The system's ACTION_APPWIDGET_PICK is not usable here: it binds the chosen
     * provider itself, which requires the signature-level BIND_APPWIDGET
     * permission. Without it the picker hands back an id that was never bound,
     * so every pick ended up falling back to the phone card.
     */
    private fun pickWidget() {
        val providers = WidgetHost.providers(this)
        if (providers.isEmpty()) {
            android.widget.Toast
                .makeText(this, R.string.widget_none, android.widget.Toast.LENGTH_SHORT)
                .show()
            revertToPhone()
            return
        }

        val labels = providers
            .map { it.loadLabel(packageManager)?.toString().orEmpty() }
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.customize_pick_widget)
            .setItems(labels) { _, which -> beginBind(providers[which]) }
            .setOnCancelListener {
                // Backing out of the list with nothing bound leaves widget mode
                // showing an empty card, so drop back to the phone card.
                if (prefs.panelWidgetId == WidgetHost.INVALID_ID) revertToPhone()
            }
            .show()
    }

    /** Reserve an id for the chosen provider and get it bound, asking if needed. */
    private fun beginBind(provider: AppWidgetProviderInfo) {
        // Release the previous binding first so we don't leak reserved ids.
        WidgetHost.delete(host(), prefs.panelWidgetId)
        prefs.panelWidgetId = WidgetHost.INVALID_ID

        val id = WidgetHost.allocateId(host())
        if (id == WidgetHost.INVALID_ID) {
            revertToPhone()
            return
        }
        pendingWidgetId = id

        when {
            // Already allowed to bind — no dialog needed.
            WidgetHost.bind(this, id, provider) -> afterBind(id)
            // Otherwise the system asks the user to allow this one widget.
            WidgetHost.requestBind(this, id, provider, REQ_BIND_WIDGET) -> Unit
            else -> {
                WidgetHost.delete(host(), id)
                pendingWidgetId = WidgetHost.INVALID_ID
                revertToPhone()
            }
        }
    }

    /** Bound — run the provider's configuration step if it insists on one. */
    private fun afterBind(widgetId: Int) {
        if (!WidgetHost.configureIfNeeded(this, widgetId, REQ_CONFIGURE_WIDGET)) {
            commitWidget(widgetId)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // The bind dialog returns no data when the user declines, so track the id
        // ourselves rather than reading it back out of the result.
        val widgetId = data?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, WidgetHost.INVALID_ID
        )?.takeIf { it != WidgetHost.INVALID_ID } ?: pendingWidgetId

        when {
            requestCode == REQ_BIND_WIDGET && resultCode == Activity.RESULT_OK ->
                afterBind(widgetId)

            requestCode == REQ_CONFIGURE_WIDGET && resultCode == Activity.RESULT_OK ->
                commitWidget(widgetId)

            requestCode == REQ_BIND_WIDGET || requestCode == REQ_CONFIGURE_WIDGET -> {
                // Declined or cancelled — give the id back and show the phone card.
                WidgetHost.delete(host(), widgetId)
                pendingWidgetId = WidgetHost.INVALID_ID
                revertToPhone()
            }

            requestCode >= REQ_SHORTCUT_BASE -> {
                data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)?.let { pkg ->
                    prefs.setPanelShortcut(requestCode - REQ_SHORTCUT_BASE, pkg)
                }
                applyToPreview()
            }
        }
    }

    private fun revertToPhone() {
        if (prefs.panelWidgetId == WidgetHost.INVALID_ID) {
            prefs.secondCardMode = Prefs.SECOND_PHONE
        }
        applyToPreview()
    }

    private fun commitWidget(widgetId: Int) {
        pendingWidgetId = WidgetHost.INVALID_ID
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
        const val REQ_CONFIGURE_WIDGET = 901
        const val REQ_BIND_WIDGET = 902
        const val REQ_SHORTCUT_BASE = 910
    }
}
