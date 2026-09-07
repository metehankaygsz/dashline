// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.app.Activity
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

    /** Id reserved for a widget that's mid-bind; the result may not carry it. */
    private var pendingWidgetId = WidgetHost.INVALID_ID
    /** Which card the pending widget is for, and the id it replaces (if any). */
    private var pendingSlot: String? = null
    private var pendingReplaced = WidgetHost.INVALID_ID
    /** The widget's name, so a failure can say which one it was about. */
    private var pendingLabel: String? = null
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

        // A bind can be interrupted by the process dying behind the system's
        // consent dialog or a widget's own configuration activity, so the id we
        // reserved has to survive that or it leaks and the bind is lost.
        savedInstanceState?.let {
            pendingWidgetId = it.getInt(STATE_PENDING_ID, WidgetHost.INVALID_ID)
            pendingSlot = it.getString(STATE_PENDING_SLOT)
            pendingReplaced = it.getInt(STATE_PENDING_REPLACED, WidgetHost.INVALID_ID)
            pendingLabel = it.getString(STATE_PENDING_LABEL)
        }

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
        binding.btnFavorites.setOnClickListener { chooseFavorites() }
        binding.btnClockPanel.setOnClickListener { chooseClockPanel() }
        binding.btnMediaCard.setOnClickListener { chooseCard(Prefs.SLOT_MEDIA) }
        binding.btnSecondCard.setOnClickListener { chooseCard(Prefs.SLOT_SECOND) }
        binding.btnReset.setOnClickListener {
            prefs.mediaFraction = 0.72f
            prefs.panelOrder = Prefs.PANEL_MEDIA_FIRST
            prefs.setCardMode(Prefs.SLOT_MEDIA, Prefs.CARD_MEDIA)
            prefs.setCardMode(Prefs.SLOT_SECOND, Prefs.CARD_PHONE)
            prefs.favoritesEnabled = true
            prefs.favoriteCount = Prefs.FAVORITE_COUNT
            prefs.favoriteSize = FavoriteDock.SIZE_MEDIUM
            prefs.projectionVisible = true
            prefs.setCardMode(Prefs.SLOT_CLOCK, Prefs.CARD_CLOCK)
            prefs.setCardMode(Prefs.SLOT_WEATHER, Prefs.CARD_WEATHER)
            applyToPreview()
        }

        applyToPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_ID, pendingWidgetId)
        outState.putString(STATE_PENDING_SLOT, pendingSlot)
        outState.putInt(STATE_PENDING_REPLACED, pendingReplaced)
        outState.putString(STATE_PENDING_LABEL, pendingLabel)
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
        // Mirrors HomeActivity.showPlaceholderArt: the tile opts out of global
        // tinting for real artwork's sake, so the stand-in glyph tints itself.
        preview.mediaArt.setColorFilter(accentColor(), android.graphics.PorterDuff.Mode.SRC_IN)

        renderFavoritesPreview()

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

    /**
     * The dock, drawn at the size and count the user has chosen. Slots aren't
     * tappable here — this screen is about how it looks, and the apps themselves
     * are assigned from the dashboard.
     */
    private fun renderFavoritesPreview() {
        val dock = preview.favoritesDock
        if (!prefs.favoritesEnabled) {
            dock.removeAllViews()
            dock.visibility = View.GONE
            FavoriteDock.applyBarHeight(preview.topBar, FavoriteDock.collapsed())
            return
        }
        dock.visibility = View.VISIBLE

        val spec = FavoriteDock.specFor(this, prefs.favoriteSize, prefs.favoriteCount)
        FavoriteDock.applyBarHeight(preview.topBar, spec)

        dock.removeAllViews()
        for (index in 0 until prefs.favoriteCount) {
            val slot = FavoriteDock.slotView(this, spec)
            val pkg = prefs.getFavorite(index)
            if (pkg != null && AppRepository.isInstalled(this, pkg)) {
                slot.setImageDrawable(AppRepository.iconFor(this, pkg))
                slot.clearColorFilter()
            } else {
                slot.setImageResource(R.drawable.ic_add)
                slot.setColorFilter(accentColor(), android.graphics.PorterDuff.Mode.SRC_IN)
            }
            slot.isClickable = false
            slot.isFocusable = false
            dock.addView(slot)
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

    /** Mirrors HomeActivity.splitFraction — a hidden card yields the column. */
    private fun splitFraction(): Float {
        val mediaOff = prefs.cardMode(Prefs.SLOT_MEDIA) == Prefs.CARD_NONE
        val secondOff = prefs.cardMode(Prefs.SLOT_SECOND) == Prefs.CARD_NONE
        return when {
            mediaOff && !secondOff -> 0f
            secondOff && !mediaOff -> 1f
            else -> prefs.mediaFraction
        }
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

        // A card that's been turned off can't be resized against, so the split
        // stops applying — and the handle goes with it.
        val splittable = prefs.cardMode(Prefs.SLOT_MEDIA) != Prefs.CARD_NONE &&
            prefs.cardMode(Prefs.SLOT_SECOND) != Prefs.CARD_NONE
        handle?.visibility = if (splittable) View.VISIBLE else View.GONE

        dragFraction = splitFraction()
        if (isLandscape()) {
            applyWeights(dragFraction)
            if (splittable) showSplitHint(dragFraction)
            else binding.customizeHint.setText(R.string.customize_hint_no_split)
        } else {
            // No weights in portrait — say so instead of showing a meaningless %.
            binding.customizeHint.setText(R.string.customize_hint_portrait)
        }

        renderFavoritesPreview()
        applyCardPreviews()
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

    private fun applyCardPreviews() {
        applyCardPreview(Prefs.SLOT_MEDIA)
        applyCardPreview(Prefs.SLOT_SECOND)
        applyPanelSlotPreview(Prefs.SLOT_CLOCK)
        applyPanelSlotPreview(Prefs.SLOT_WEATHER)
        applySectionVisibilityPreview()
    }

    /** Mirrors HomeActivity.applySectionVisibility. */
    private fun applySectionVisibilityPreview() {
        val projection = prefs.projectionVisible
        preview.projectionRow.visibility = if (projection) View.VISIBLE else View.GONE

        val clockOff = prefs.cardMode(Prefs.SLOT_CLOCK) == Prefs.CARD_NONE
        val weatherOff = prefs.cardMode(Prefs.SLOT_WEATHER) == Prefs.CARD_NONE
        preview.clockPanel.visibility =
            if (clockOff && weatherOff && !projection) View.GONE else View.VISIBLE

        if (!isLandscape()) return
        val cardsOff = prefs.cardMode(Prefs.SLOT_MEDIA) == Prefs.CARD_NONE &&
            prefs.cardMode(Prefs.SLOT_SECOND) == Prefs.CARD_NONE
        (preview.mediaCard.parent as? View)?.visibility =
            if (cardsOff) View.GONE else View.VISIBLE
    }

    /** Mirrors HomeActivity.applyPanelSlot — portrait only, widgets or not. */
    private fun applyPanelSlotPreview(slot: String) {
        val clock = slot == Prefs.SLOT_CLOCK
        val content = if (clock) preview.clockSlot else preview.weatherSlot
        val widgets = if (clock) preview.clockWidget else preview.weatherWidget

        val mode = prefs.cardMode(slot)
        val off = mode == Prefs.CARD_NONE
        val asWidget = !off && !isLandscape() && mode == Prefs.CARD_WIDGET
        content.visibility = if (off || asWidget) View.GONE else View.VISIBLE
        widgets.visibility = if (asWidget) View.VISIBLE else View.GONE

        if (asWidget) renderWidgetPreview(slot, widgets)
    }

    /** Same three states as the dashboard, drawn into the live preview. */
    private fun applyCardPreview(slot: String) {
        val media = slot == Prefs.SLOT_MEDIA
        val mode = prefs.cardMode(slot)
        val default = if (media) preview.mediaDefault else preview.phoneDefault
        val shortcuts = if (media) preview.mediaShortcuts else preview.panelShortcuts
        val widgets = if (media) preview.mediaWidget else preview.panelWidget

        val isShortcuts = mode == Prefs.CARD_SHORTCUTS
        val isWidget = mode == Prefs.CARD_WIDGET
        val isOff = mode == Prefs.CARD_NONE

        (if (media) preview.mediaCard else preview.phoneCard).visibility =
            if (isOff) View.GONE else View.VISIBLE
        default.visibility =
            if (!isShortcuts && !isWidget && !isOff) View.VISIBLE else View.GONE
        shortcuts.visibility = if (isShortcuts) View.VISIBLE else View.GONE
        widgets.visibility = if (isWidget) View.VISIBLE else View.GONE

        if (isShortcuts) renderShortcutSlots(slot, shortcuts)
        if (isWidget) renderWidgetPreview(slot, widgets)
    }

    private fun renderShortcutSlots(slot: String, row: LinearLayout) {
        row.removeAllViews()
        val base = if (slot == Prefs.SLOT_MEDIA) REQ_MEDIA_SHORTCUT_BASE else REQ_SHORTCUT_BASE
        for (index in 0 until Prefs.PANEL_SHORTCUT_COUNT) {
            val pkg = prefs.cardShortcut(slot, index)
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
                AppPickerActivity.start(this, Prefs.ROLE_FAVORITE, base + index)
            }
            row.addView(
                item.root,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    /**
     * Draws the card's widgets side by side. An empty card shows the "add a
     * widget" prompt instead, so the mode is never a blank rectangle.
     */
    private fun renderWidgetPreview(slot: String, container: LinearLayout) {
        container.removeAllViews()
        val ids = prefs.cardWidgets(slot)

        if (ids.isEmpty()) {
            val prompt = layoutInflater.inflate(R.layout.view_widget_empty, container, false)
            prompt.setOnClickListener { pickWidget(slot) }
            container.addView(
                prompt,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        // Same rule as the dashboard, or the preview lies about the height.
        val panelSlot = slot == Prefs.SLOT_CLOCK || slot == Prefs.SLOT_WEATHER
        WidgetHost.sizeContainer(container, fill = isLandscape() && !panelSlot)

        ids.forEach { id ->
            val cell = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            val view = WidgetHost.createView(this, id)
            runCatching {
                if (view != null) {
                    container.addView(view, cell)
                    WidgetHost.sizeOnLayout(this, view, id)
                } else if (WidgetHost.isGoneForGood(this, id)) {
                    // The framework has dropped this binding — the provider was
                    // uninstalled or disabled, so the setting is dead weight.
                    prefs.removeCardWidget(slot, id)
                } else {
                    // Unavailable is not the same as gone — a provider being
                    // updated returns null for a few seconds. Leave the user's
                    // settings alone and let them remove it if they want to.
                    container.addView(
                        layoutInflater.inflate(
                            R.layout.view_widget_unavailable, container, false
                        ),
                        cell
                    )
                }
            }
        }
        // Long-press the card to add, swap or remove widgets.
        container.setOnLongClickListener { manageWidgets(slot); true }
    }

    // ---- card content -------------------------------------------------------

    /**
     * Put widgets where the clock or the weather normally sits.
     *
     * Portrait only. In landscape those two fill the left column between them,
     * so swapping one for a widget leaves a gap with nothing to close it — the
     * button says so rather than offering a choice that wouldn't be honoured.
     */
    private fun chooseClockPanel() {
        val slots = listOf(
            Prefs.SLOT_CLOCK to R.string.card_clock,
            Prefs.SLOT_WEATHER to R.string.card_weather
        )
        val labels = slots.map { (slot, nameRes) ->
            getString(
                R.string.favorites_option,
                getString(nameRes),
                getString(
                    when (prefs.cardMode(slot)) {
                        Prefs.CARD_WIDGET -> R.string.second_widget
                        Prefs.CARD_NONE -> R.string.card_hidden
                        else -> nameRes
                    }
                )
            )
        }.toMutableList()

        // The projection tiles sit in the same panel, so they're managed here
        // rather than getting a button of their own on an already busy screen.
        labels.add(
            getString(
                R.string.favorites_option,
                getString(R.string.customize_projection),
                getString(if (prefs.projectionVisible) R.string.on else R.string.off)
            )
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.customize_clock_panel)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= slots.size) {
                    prefs.projectionVisible = !prefs.projectionVisible
                    applyToPreview()
                } else {
                    choosePanelMode(slots[which].first)
                }
            }
            .show()
    }

    /** Either the panel's own content, or widgets in its place. */
    private fun choosePanelMode(slot: String) {
        val default = prefs.defaultCardMode(slot)
        val options = listOf(
            default to if (slot == Prefs.SLOT_CLOCK) R.string.card_clock else R.string.card_weather,
            Prefs.CARD_WIDGET to R.string.second_widget,
            Prefs.CARD_NONE to R.string.card_hidden
        )
        AlertDialog.Builder(this)
            .setTitle(if (slot == Prefs.SLOT_CLOCK) R.string.card_clock else R.string.card_weather)
            .setItems(options.map { getString(it.second) }.toTypedArray()) { _, which ->
                val mode = options[which].first
                // Hiding a half works either way up, but a widget in its place
                // only fits in portrait — landscape needs both halves to fill
                // the column. Say so rather than saving a choice we'd ignore.
                if (mode == Prefs.CARD_WIDGET && isLandscape()) {
                    toast(getString(R.string.customize_panel_landscape))
                    return@setItems
                }
                prefs.setCardMode(slot, mode)
                when {
                    mode == Prefs.CARD_WIDGET && prefs.cardWidgets(slot).isEmpty() ->
                        pickWidget(slot)
                    mode == Prefs.CARD_WIDGET -> {
                        applyToPreview()
                        manageWidgets(slot)
                    }
                    else -> applyToPreview()
                }
            }
            .show()
    }

    /**
     * Slot count and icon size for the top-bar dock.
     *
     * They're offered separately because they aren't really the trade-off they
     * look like: the dock has width to spare, so more slots doesn't have to mean
     * smaller icons. Where they genuinely collide — many large icons on a narrow
     * unit — [FavoriteDock] resolves it by using a smaller size rather than
     * refusing the count.
     */
    private fun chooseFavorites() {
        val options = arrayOf(
            getString(
                R.string.favorites_option,
                getString(R.string.favorites_slots),
                if (prefs.favoritesEnabled) prefs.favoriteCount.toString()
                else getString(R.string.off)
            ),
            getString(
                R.string.favorites_option,
                getString(R.string.favorites_size),
                getString(FavoriteDock.labelFor(prefs.favoriteSize))
            )
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.customize_favorites)
            .setItems(options) { _, which ->
                if (which == 0) chooseFavoriteCount() else chooseFavoriteSize()
            }
            .show()
    }

    /**
     * How many slots — or none at all. Off is first because it's a choice about
     * whether to have the dock, not a smaller amount of it: someone who pins
     * their apps to a card doesn't want a second copy of them in the bar.
     */
    private fun chooseFavoriteCount() {
        // Only offer counts that fit even at the smallest icon size.
        val counts = (FavoriteDock.MIN_SLOTS..FavoriteDock.maxSlots(this)).toList()
        val labels = listOf(getString(R.string.off)) + counts.map { it.toString() }
        AlertDialog.Builder(this)
            .setTitle(R.string.favorites_slots)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    prefs.favoritesEnabled = false
                } else {
                    prefs.favoritesEnabled = true
                    prefs.favoriteCount = counts[which - 1]
                }
                applyToPreview()
            }
            .show()
    }

    private fun chooseFavoriteSize() {
        val sizes = FavoriteDock.SIZES
        AlertDialog.Builder(this)
            .setTitle(R.string.favorites_size)
            .setItems(sizes.map { getString(FavoriteDock.labelFor(it.id)) }.toTypedArray()) { _, which ->
                prefs.favoriteSize = sizes[which].id
                applyToPreview()
            }
            .show()
    }

    /** What this card should show. The media card can also stay a player. */
    private fun chooseCard(slot: String) {
        val options = buildList {
            if (slot == Prefs.SLOT_MEDIA) {
                add(Prefs.CARD_MEDIA to R.string.card_media_player)
            } else {
                add(Prefs.CARD_PHONE to R.string.second_phone)
            }
            add(Prefs.CARD_SHORTCUTS to R.string.second_shortcuts)
            add(Prefs.CARD_WIDGET to R.string.second_widget)
            add(Prefs.CARD_NONE to R.string.card_hidden)
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (slot == Prefs.SLOT_MEDIA) R.string.customize_media_card
                else R.string.settings_second_card
            )
            .setItems(options.map { getString(it.second) }.toTypedArray()) { _, which ->
                val mode = options[which].first
                prefs.setCardMode(slot, mode)
                when {
                    // A widget card with nothing on it yet goes straight to the picker.
                    mode == Prefs.CARD_WIDGET && prefs.cardWidgets(slot).isEmpty() ->
                        pickWidget(slot)
                    mode == Prefs.CARD_WIDGET -> {
                        // Show the card in its new mode behind the manage dialog.
                        applyToPreview()
                        manageWidgets(slot)
                    }
                    else -> applyToPreview()
                }
            }
            .show()
    }

    /**
     * Add, swap or remove the widgets on a card. Reached from this screen's card
     * button and by long-pressing the card in the preview.
     */
    private fun manageWidgets(slot: String) {
        val ids = prefs.cardWidgets(slot)
        val entries = ids.map { id ->
            id to (WidgetHost.label(this, id) ?: getString(R.string.second_widget))
        }
        val labels = entries.map { it.second }.toMutableList()
        val canAdd = ids.size < Prefs.MAX_CARD_WIDGETS
        if (canAdd) labels.add(getString(R.string.widget_add))

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_customize)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= entries.size) pickWidget(slot)
                else widgetActions(slot, entries[which].first, entries[which].second)
            }
            .show()
    }

    private fun widgetActions(slot: String, widgetId: Int, label: String) {
        val actions = arrayOf(
            getString(R.string.widget_change), getString(R.string.widget_remove)
        )
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(actions) { _, which ->
                if (which == 0) {
                    pickWidget(slot, replacing = widgetId)
                } else {
                    prefs.removeCardWidget(slot, widgetId)
                    WidgetHost.delete(this, widgetId)
                    if (prefs.cardWidgets(slot).isEmpty()) revertToDefault(slot)
                    applyToPreview()
                }
            }
            .show()
    }

    /**
     * Choose a widget from our own list of installed providers.
     *
     * The system's ACTION_APPWIDGET_PICK is not usable here: it binds the chosen
     * provider itself, which requires the signature-level BIND_APPWIDGET
     * permission. Without it the picker hands back an id that was never bound,
     * so every pick ended up falling back to the phone card.
     */
    private fun pickWidget(slot: String, replacing: Int = WidgetHost.INVALID_ID) {
        val providers = WidgetHost.providers(this)
        if (providers.isEmpty()) {
            toast(getString(R.string.widget_none))
            revertToDefault(slot)
            applyToPreview()
            return
        }
        if (replacing == WidgetHost.INVALID_ID &&
            prefs.cardWidgets(slot).size >= Prefs.MAX_CARD_WIDGETS
        ) {
            toast(getString(R.string.widget_full, Prefs.MAX_CARD_WIDGETS))
            return
        }

        val labels = providers
            .map { it.loadLabel(packageManager)?.toString().orEmpty() }
            .toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.customize_pick_widget)
            .setItems(labels) { _, which -> beginBind(slot, providers[which], replacing) }
            .setOnCancelListener {
                // Backing out with nothing on the card leaves an empty rectangle.
                if (prefs.cardWidgets(slot).isEmpty()) revertToDefault(slot)
                applyToPreview()
            }
            .show()
    }

    /** Reserve an id for the chosen provider and get it bound, asking if needed. */
    private fun beginBind(slot: String, provider: AppWidgetProviderInfo, replacing: Int) {
        val label = provider.loadLabel(packageManager)?.toString()
            ?: getString(R.string.second_widget)

        val id = WidgetHost.allocateId(this)
        if (id == WidgetHost.INVALID_ID) {
            revertToDefault(slot)
            applyToPreview()
            explainFailure(R.string.widget_fail_slot)
            return
        }
        pendingWidgetId = id
        pendingSlot = slot
        pendingReplaced = replacing
        pendingLabel = label

        when {
            // Already allowed to bind — no dialog needed.
            WidgetHost.bind(this, id, provider) -> afterBind(id)
            // Otherwise the system asks the user to allow this one widget.
            WidgetHost.requestBind(this, id, provider, REQ_BIND_WIDGET) -> Unit
            // No bind screen at all: a stripped-down ROM, and nothing we can do.
            else -> abandonPending(R.string.widget_fail_unsupported)
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

            // The user declined the system's permission prompt.
            requestCode == REQ_BIND_WIDGET -> abandonPending(R.string.widget_fail_permission)

            // The widget's own setup screen was closed without finishing.
            requestCode == REQ_CONFIGURE_WIDGET -> abandonPending(R.string.widget_fail_setup)

            requestCode >= REQ_MEDIA_SHORTCUT_BASE -> {
                data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)?.let { pkg ->
                    prefs.setCardShortcut(
                        Prefs.SLOT_MEDIA, requestCode - REQ_MEDIA_SHORTCUT_BASE, pkg
                    )
                }
                applyToPreview()
            }

            requestCode >= REQ_SHORTCUT_BASE -> {
                data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)?.let { pkg ->
                    prefs.setCardShortcut(
                        Prefs.SLOT_SECOND, requestCode - REQ_SHORTCUT_BASE, pkg
                    )
                }
                applyToPreview()
            }
        }
    }

    /**
     * Give the reserved id back, leave the card be, and say what went wrong.
     *
     * Every one of these used to revert in silence, which left the user with a
     * card that had quietly gone back to its old contents and no idea why.
     */
    private fun abandonPending(reason: Int) {
        WidgetHost.delete(this, pendingWidgetId)
        val slot = pendingSlot
        clearPending()
        if (slot != null && prefs.cardWidgets(slot).isEmpty()) revertToDefault(slot)
        applyToPreview()
        explainFailure(reason)
    }

    /** Names the widget where the message has room for it. */
    private fun explainFailure(reason: Int) {
        val label = pendingLabel ?: getString(R.string.second_widget)
        AlertDialog.Builder(this)
            .setTitle(R.string.widget_add_failed_title)
            .setMessage(getString(reason, label))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun commitWidget(widgetId: Int) {
        val slot = pendingSlot ?: Prefs.SLOT_SECOND
        val replaced = pendingReplaced
        clearPending()

        if (replaced != WidgetHost.INVALID_ID) {
            // Keep the swapped widget's position on the card.
            prefs.replaceCardWidget(slot, replaced, widgetId)
            WidgetHost.delete(this, replaced)
        } else if (!prefs.addCardWidget(slot, widgetId)) {
            // Card filled up while the picker was open.
            WidgetHost.delete(this, widgetId)
            prefs.setCardMode(slot, Prefs.CARD_WIDGET)
            applyToPreview()
            AlertDialog.Builder(this)
                .setTitle(R.string.widget_add_failed_title)
                .setMessage(getString(R.string.widget_full, Prefs.MAX_CARD_WIDGETS))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            pendingLabel = null
            return
        }
        prefs.setCardMode(slot, Prefs.CARD_WIDGET)
        applyToPreview()

        // Bound as far as the system was concerned, but nothing is actually
        // attached — usually the provider's app updating underneath us.
        if (WidgetHost.manager(this).getAppWidgetInfo(widgetId) == null) {
            explainFailure(R.string.widget_fail_bind)
        }
        pendingLabel = null
    }

    /** Keeps [pendingLabel] — the failure message that follows still needs it. */
    private fun clearPending() {
        WidgetHost.settled(pendingWidgetId)
        pendingWidgetId = WidgetHost.INVALID_ID
        pendingSlot = null
        pendingReplaced = WidgetHost.INVALID_ID
    }

    private fun revertToDefault(slot: String) =
        prefs.setCardMode(slot, prefs.defaultCardMode(slot))

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        WidgetHost.attach(this)
    }

    override fun onPause() {
        super.onPause()
        WidgetHost.detach()
    }

    private companion object {
        const val REQ_CONFIGURE_WIDGET = 901
        const val REQ_BIND_WIDGET = 902
        // One block of request codes per card, so a result knows where it belongs.
        const val REQ_SHORTCUT_BASE = 910
        const val REQ_MEDIA_SHORTCUT_BASE = 920

        const val STATE_PENDING_ID = "pending_widget_id"
        const val STATE_PENDING_SLOT = "pending_widget_slot"
        const val STATE_PENDING_REPLACED = "pending_widget_replaced"
        const val STATE_PENDING_LABEL = "pending_widget_label"
    }
}
