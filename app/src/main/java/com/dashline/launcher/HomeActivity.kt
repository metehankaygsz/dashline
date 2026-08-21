// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Typeface
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dashline.launcher.databinding.ActivityHomeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SYNC-style home dashboard: top status bar (home / clock / wifi), a clock +
 * weather panel on the left, a media + phone panel on the right, and a bottom
 * tab bar.
 */
class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var lastWeatherFetch = 0L

    private var mediaMonitor: MediaMonitor? = null
    private var currentMedia: MediaInfo? = null
    private var draggingMedia = false
    private var needsMediaAccess = false

    private lateinit var audioManager: android.media.AudioManager
    private var draggingVolume = false
    private var mediaCompact = false
    private var showSeek = true
    /** Packages currently drawn in the portrait drawer, to skip pointless rebuilds. */
    private var drawerSignature: String? = null
    private var drawerPages: List<List<AppInfo>> = emptyList()
    private var drawerPage = 0
    private var drawerRows = 0
    /** One update prompt per launch, however often the dashboard resumes. */
    private var offeredUpdate = false


    // Built from the current locale in onCreate, so they follow the chosen language.
    private lateinit var timeFormat: SimpleDateFormat
    private lateinit var dateFormat: SimpleDateFormat
    private lateinit var dayFormat: SimpleDateFormat
    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            updateWifi()
            updateMediaProgress()
            syncVolume()
            updateChrono()
            maybeRefreshWeather()
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * The dashboard ticker runs once a second, which leaves the stopwatch tenths
     * looking frozen. This one runs only while the stopwatch is actually running,
     * and stops itself the moment it isn't.
     */
    private val chronoTicker = object : Runnable {
        override fun run() {
            updateChrono()
            if (prefs.chronoEnabled && prefs.chronoRunning) {
                handler.postDelayed(this, CHRONO_TICK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val loc = currentLocale()
        timeFormat = SimpleDateFormat("HH:mm", loc)
        dateFormat = SimpleDateFormat("EEEE, d MMMM", loc)
        dayFormat = SimpleDateFormat("EEE", loc)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        setupTabs()
        setupCardsAndBar()
        setupMedia()
        setupVolume()
        setupFavorites()
        setupChrono()
        applyClockStyle()
        applyPanelLayout()
        setupPortraitDrawer()
        applyAccent()
        ensureLocationPermission()
    }

    /**
     * Send the user (back) to first-run setup until it's actually finished.
     *
     * This lives in onResume, not onCreate: we're a singleTask HOME activity, so
     * pressing the Home button while setup is open destroys SetupActivity and
     * resumes this one via onNewIntent — onCreate never runs again, which would
     * otherwise let the user skip setup entirely.
     */
    private fun enforceSetup(): Boolean {
        if (prefs.setupDone) return false
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        return true
    }

    /**
     * Setup asks for location first. This is only a fallback for installs that
     * never went through setup, and it asks at most once so the driver isn't
     * prompted on every launch.
     */
    private fun ensureLocationPermission() {
        if (Permissions.hasLocation(this) || prefs.askedLocation) return
        prefs.askedLocation = true
        Permissions.requestLocation(this, REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) loadWeather()
    }

    /**
     * Builds the bottom bar from the user's saved tab order, skipping hidden
     * ones. Rebuilt on resume so changes in Settings show up immediately.
     */
    private fun setupTabs() {
        val bar = binding.tabBar
        bar.removeAllViews()
        val inflater = layoutInflater

        prefs.visibleTabs().forEach { tab ->
            val item = com.dashline.launcher.databinding.ItemTabBinding
                .inflate(inflater, bar, false)
            item.tabIcon.setImageResource(tab.iconRes)
            item.tabLabel.setText(tab.labelRes)
            item.root.setOnClickListener { onTab(tab) }
            bar.addView(
                item.root,
                android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
            )
        }
    }

    private fun onTab(tab: TabAction) = when (tab) {
        TabAction.AUDIO -> launchRole(Prefs.ROLE_MEDIA)
        TabAction.RADIO -> launchRole(Prefs.ROLE_RADIO)
        TabAction.PHONE -> openPhone()
        TabAction.NAV -> openNav()
        TabAction.APPS -> startActivity(Intent(this, AppDrawerActivity::class.java))
        TabAction.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun setupCardsAndBar() {
        binding.mediaCard.setOnClickListener { openMedia() }
        binding.phoneCard.setOnClickListener { openPhone() }
        // Phone projection is usually a third-party link app (ZLink, EasyConnected…),
        // so these fall through to the picker when nothing known is installed.
        binding.shortcutAndroidAuto.setOnClickListener { launchRole(Prefs.ROLE_ANDROID_AUTO) }
        binding.shortcutCarPlay.setOnClickListener { launchRole(Prefs.ROLE_CARPLAY) }
        // Home button: we're already on the dashboard — force a weather refresh.
        binding.homeButton.setOnClickListener { loadWeather() }

        binding.phoneLabel.text = getString(R.string.tab_phone)
    }

    /**
     * Paints the accent-coloured pieces of the dashboard with the selected
     * gradient's accent: the media card, the play button and the seek bar.
     */
    private fun applyAccent() {
        val accent = accentColor()
        val density = resources.displayMetrics.density
        val night = GradientThemes.isNight(this)

        // Card reads as a solid accent panel at night, a pale wash in daylight.
        val cardColor =
            if (night) GradientThemes.darken(accent, 0.30f)
            else GradientThemes.withAlpha(accent, 0x33)
        binding.mediaCard.background = GradientThemes.roundedRect(cardColor, 8f * density)

        binding.btnPlayPause.background = GradientThemes.roundedRect(accent, 25f * density)

        // Seek fill stays white on the dark card; accent-tinted on the light one.
        // Only the progress layer is recoloured — the track must stay muted.
        @Suppress("DEPRECATION")
        run {
            val seekColor = if (night) android.graphics.Color.WHITE else accent
            // Both bars live on the media card, so they share the same treatment.
            listOf(binding.mediaProgress, binding.volumeSlider).forEach { bar ->
                val progressDrawable = bar.progressDrawable?.mutate()
                if (progressDrawable is android.graphics.drawable.LayerDrawable) {
                    progressDrawable.findDrawableByLayerId(android.R.id.progress)
                        ?.setColorFilter(seekColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    bar.progressDrawable = progressDrawable
                }
                bar.thumb?.mutate()
                    ?.setColorFilter(seekColor, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
    }



    // ---- Home panel layout -------------------------------------------------

    /**
     * Applies the user's chosen order, split and lower-card mode to the right
     * column, then rescales the media card's contents to match the space it
     * ended up with. Portrait stacks everything and has no weights, so the
     * resize is skipped there.
     */
    private fun applyPanelLayout() {
        val landscape =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val parent = binding.mediaCard.parent as? android.widget.LinearLayout
        if (parent != null) {
            // Card order applies in both orientations. In portrait the cards sit
            // after the clock panel, so reorder relative to their current spot.
            val base = minOf(
                parent.indexOfChild(binding.mediaCard),
                parent.indexOfChild(binding.phoneCard)
            ).coerceAtLeast(0)
            val phoneFirst = prefs.panelOrder == Prefs.PANEL_PHONE_FIRST
            parent.removeView(binding.mediaCard)
            parent.removeView(binding.phoneCard)
            if (phoneFirst) {
                parent.addView(binding.phoneCard, base)
                parent.addView(binding.mediaCard, base + 1)
            } else {
                parent.addView(binding.mediaCard, base)
                parent.addView(binding.phoneCard, base + 1)
            }

            if (landscape) {
                // Weights only mean something in the fixed-height column.
                val fraction = prefs.mediaFraction
                setCardWeight(binding.mediaCard, fraction)
                setCardWeight(binding.phoneCard, 1f - fraction)
                applyMediaDensity(fraction)
            } else {
                // Portrait scrolls, so everything gets its full size.
                showSeek = true
                mediaCompact = false
                binding.mediaVolumeRow.visibility = android.view.View.VISIBLE
            }
        }

        applyCardModes()
    }

    private fun setCardWeight(card: android.view.View, weight: Float) {
        val lp = card.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        lp.height = 0
        lp.weight = weight
        // A weighted card must not also enforce a minimum, or it can't shrink.
        (card as? android.view.ViewGroup)?.minimumHeight = 0
        card.layoutParams = lp
    }

    /**
     * Scales the media card's contents to the height it was given. A small card
     * drops the seek row and shrinks the artwork rather than clipping.
     */
    /**
     * Scales the media card to the height it was given, and — more importantly —
     * removes controls *before* they'd be squashed. A cramped seek bar or volume
     * slider looks broken; showing fewer, correctly sized controls does not.
     */
    private fun applyMediaDensity(mediaFraction: Float) {
        // Nothing to scale when the card has been given over to shortcuts or widgets.
        if (prefs.cardMode(Prefs.SLOT_MEDIA) != Prefs.CARD_MEDIA) return
        val density = resources.displayMetrics.density

        // Drop rows in order of expendability as the card shrinks.
        showSeek = mediaFraction >= 0.56f
        val showVolume = mediaFraction >= 0.46f
        val showControls = mediaFraction >= 0.34f
        val compact = mediaFraction < 0.46f

        binding.mediaVolumeRow.visibility =
            if (showVolume) android.view.View.VISIBLE else android.view.View.GONE
        if (!showControls) binding.mediaControls.visibility = android.view.View.GONE

        val art = ((if (compact) 52f else resources.getDimension(R.dimen.album_art) / density) * density).toInt()
        binding.mediaArt.layoutParams = binding.mediaArt.layoutParams.apply {
            width = art
            height = art
        }
        if (currentMedia?.art == null) {
            val pad = ((if (compact) 13f else resources.getDimension(R.dimen.album_art_padding) / density) * density).toInt()
            binding.mediaArt.setPadding(pad, pad, pad, pad)
        }
        binding.mediaArt.requestLayout()

        binding.mediaTitle.textSize = if (compact) 15f else
            resources.getDimension(R.dimen.media_title) / resources.displayMetrics.scaledDensity

        val btn = ((if (compact) 40f else 50f) * density).toInt()
        binding.btnPlayPause.layoutParams = binding.btnPlayPause.layoutParams.apply {
            width = btn
            height = btn
        }
        binding.btnPlayPause.background = GradientThemes.roundedRect(accentColor(), btn / 2f)

        mediaCompact = compact
        updateMediaProgress()
    }

    /**
     * Both cards are configurable and share this code: each shows its default
     * content (the player, or the phone shortcut), a row of app shortcuts, or
     * hosted app widgets.
     */
    private fun applyCardModes() {
        applyCardMode(Prefs.SLOT_MEDIA)
        applyCardMode(Prefs.SLOT_SECOND)
        applyPanelSlot(Prefs.SLOT_CLOCK)
        applyPanelSlot(Prefs.SLOT_WEATHER)
    }

    /**
     * The clock and weather halves of the left panel, each replaceable by
     * widgets — but only upright, where the panel is a stacked card with the
     * drawer below to absorb the difference. In landscape the two of them fill
     * the column, so a widget there would leave a hole; the saved choice is
     * simply ignored rather than reset, so rotating back restores it.
     */
    private fun applyPanelSlot(slot: String) {
        val clock = slot == Prefs.SLOT_CLOCK
        val content = if (clock) binding.clockSlot else binding.weatherSlot
        val widgets = if (clock) binding.clockWidget else binding.weatherWidget

        val asWidget = isPortraitNow() && prefs.cardMode(slot) == Prefs.CARD_WIDGET
        content.visibility = if (asWidget) android.view.View.GONE else android.view.View.VISIBLE
        widgets.visibility = if (asWidget) android.view.View.VISIBLE else android.view.View.GONE

        if (asWidget) bindCardWidgets(slot, widgets)
    }

    private fun isPortraitNow(): Boolean = !isLandscapeNow()

    private fun applyCardMode(slot: String) {
        val media = slot == Prefs.SLOT_MEDIA
        val mode = prefs.cardMode(slot)
        val default = if (media) binding.mediaDefault else binding.phoneDefault
        val shortcuts = if (media) binding.mediaShortcuts else binding.panelShortcuts
        val widgets = if (media) binding.mediaWidget else binding.panelWidget
        val card = if (media) binding.mediaCard else binding.phoneCard

        val isShortcuts = mode == Prefs.CARD_SHORTCUTS
        val isWidget = mode == Prefs.CARD_WIDGET
        val isDefault = !isShortcuts && !isWidget

        default.visibility = if (isDefault) android.view.View.VISIBLE else android.view.View.GONE
        shortcuts.visibility = if (isShortcuts) android.view.View.VISIBLE else android.view.View.GONE
        widgets.visibility = if (isWidget) android.view.View.VISIBLE else android.view.View.GONE

        // The card only acts as a shortcut while it's showing its default.
        card.setOnClickListener(
            if (!isDefault) null
            else android.view.View.OnClickListener { if (media) openMedia() else openPhone() }
        )
        card.isClickable = isDefault

        when {
            isShortcuts -> bindCardShortcuts(slot, shortcuts)
            isWidget -> bindCardWidgets(slot, widgets)
        }
    }

    /**
     * Renders a card's widgets side by side. Anything missing — never picked,
     * uninstalled, or permission revoked — is dropped rather than left as an
     * empty hole, and a card with nothing left falls back to its default.
     */
    private fun bindCardWidgets(slot: String, container: android.widget.LinearLayout) {
        val ids = prefs.cardWidgets(slot)

        // Rebuilding on every resume throws away live widget views and starts
        // them back at their placeholder, so only do it when the set changed.
        val signature = ids.joinToString(",")
        if (container.tag == signature && container.childCount == ids.size) return
        container.tag = signature
        container.removeAllViews()
        val views = ids.mapNotNull { id ->
            val view = WidgetHost.createView(this, WidgetHost.host(this), id)
            if (view == null) prefs.removeCardWidget(slot, id)
            view?.let { id to it }
        }
        if (views.isEmpty()) {
            prefs.setCardMode(slot, defaultModeFor(slot))
            applyCardMode(slot)
            return
        }

        // The clock panel wraps in both orientations; the cards only in portrait.
        val panelSlot = slot == Prefs.SLOT_CLOCK || slot == Prefs.SLOT_WEATHER
        WidgetHost.sizeContainer(container, fill = isLandscapeNow() && !panelSlot)

        views.forEach { (_, view) ->
            container.addView(
                view,
                android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
            )
        }
        views.forEach { (id, view) -> WidgetHost.sizeOnLayout(this, view, id) }
    }

    private fun defaultModeFor(slot: String) = prefs.defaultCardMode(slot)

    // ---- portrait app drawer -----------------------------------------------

    /**
     * Portrait stacks the cards and still leaves a large empty area underneath,
     * so the app drawer lives there rather than behind a tab press. Landscape
     * has no such space, and keeps the drawer on the Apps tab only.
     *
     * The drawer never scrolls: it takes whatever height the cards leave, works
     * out how many rows fit, and pages the rest. Reaching for a scrollbar while
     * driving is worse than a swipe, and a half-visible row of icons cut off by
     * the tab bar looks broken.
     */
    private fun setupPortraitDrawer() {
        if (isLandscapeNow()) {
            binding.portraitDrawer.visibility = android.view.View.GONE
            return
        }
        binding.portraitDrawer.visibility = android.view.View.VISIBLE
        binding.portraitDrawerHeader.setOnClickListener {
            startActivity(Intent(this, AppDrawerActivity::class.java))
        }
        attachDrawerSwipe()

        val apps = drawerApps()
        // Rebuilding every tile on resume is wasted work on a slow head unit, so
        // only do it when the app list or the space available actually changed.
        val signature = apps.joinToString(",") { it.packageName }
        val grid = binding.portraitDrawerGrid
        val rows = rowsThatFit(grid)
        if (signature == drawerSignature && rows == drawerRows && grid.childCount > 0) return
        drawerSignature = signature
        drawerRows = rows

        drawerPages = apps.chunked(rows * DRAWER_COLUMNS)
        drawerPage = drawerPage.coerceIn(0, maxOf(0, drawerPages.lastIndex))
        renderDrawerPage()
    }

    /**
     * How many rows of icons fit in the space the cards left over. Measured
     * rather than assumed, since head-unit screens vary wildly — and the grid
     * has no height at all on the very first pass, so fall back to one page.
     */
    private fun rowsThatFit(grid: android.view.View): Int {
        val cell = DRAWER_CELL_DP * resources.displayMetrics.density
        val available = grid.height
        if (available <= 0) {
            // Nothing measured yet; re-run once layout has happened.
            grid.post { setupPortraitDrawer() }
            return 1
        }
        return (available / cell).toInt().coerceIn(1, DRAWER_MAX_ROWS)
    }

    private fun renderDrawerPage() {
        val grid = binding.portraitDrawerGrid
        grid.removeAllViews()
        val page = drawerPages.getOrNull(drawerPage) ?: emptyList()

        page.chunked(DRAWER_COLUMNS).forEach { rowApps ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            rowApps.forEach { app ->
                val item = com.dashline.launcher.databinding.ItemAppBinding
                    .inflate(layoutInflater, row, false)
                item.appIcon.setImageDrawable(app.icon)
                item.appLabel.text = app.label
                item.root.setOnClickListener { AppRepository.launch(this, app.packageName) }
                row.addView(item.root, cellParams())
            }
            // Pad the last row so four apps and two apps are the same size.
            repeat(DRAWER_COLUMNS - rowApps.size) {
                row.addView(android.view.View(this), cellParams())
            }
            grid.addView(row)
        }
        // Keep every page the same shape: a short last page shouldn't spread its
        // one row down the middle of the card.
        repeat(drawerRows - page.chunked(DRAWER_COLUMNS).size) {
            grid.addView(
                android.view.View(this),
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
        }
        renderDrawerDots()
        tintIcons(grid)
    }

    private fun renderDrawerDots() {
        val dots = binding.portraitDrawerDots
        dots.removeAllViews()
        if (drawerPages.size < 2) return

        val density = resources.displayMetrics.density
        val size = (DRAWER_DOT_DP * density).toInt()
        val touch = (DRAWER_DOT_TOUCH_DP * density).toInt()
        val accent = accentColor()

        drawerPages.indices.forEach { index ->
            val dot = android.view.View(this).apply {
                background = GradientThemes.roundedRect(
                    if (index == drawerPage) accent
                    else GradientThemes.withAlpha(accent, 0x55),
                    size / 2f
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).also {
                    it.setMargins((touch - size) / 2, 0, (touch - size) / 2, 0)
                }
                // The dot itself is small, so give it a driver-sized touch area.
                minimumWidth = touch
                minimumHeight = touch
                setOnClickListener { showDrawerPage(index) }
                contentDescription =
                    getString(R.string.drawer_page, index + 1, drawerPages.size)
            }
            dots.addView(dot)
        }
    }

    private fun showDrawerPage(index: Int) {
        if (index !in drawerPages.indices || index == drawerPage) return
        drawerPage = index
        renderDrawerPage()
    }

    /** Swipe left/right anywhere on the grid to change page. */
    private fun attachDrawerSwipe() {
        binding.portraitDrawerGrid.onSwipe = { direction ->
            showDrawerPage(drawerPage + direction)
        }
    }

    private fun cellParams() = android.widget.LinearLayout.LayoutParams(
        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
    )

    /** Most-used apps first, then the rest alphabetically. */
    private fun drawerApps(): List<AppInfo> {
        val all = AppRepository.loadLaunchableApps(this, prefs.hiddenApps)
        val top = Usage.topApps(this, DRAWER_COLUMNS * DRAWER_MAX_ROWS)
        val byPackage = all.associateBy { it.packageName }
        val frequent = top.mapNotNull { byPackage[it] }
        val rest = all.filter { it.packageName !in top }
        return frequent + rest
    }

    private fun isLandscapeNow(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun bindCardShortcuts(slot: String, row: android.widget.LinearLayout) {
        row.removeAllViews()
        val inflater = layoutInflater

        for (index in 0 until Prefs.PANEL_SHORTCUT_COUNT) {
            val pkg = prefs.cardShortcut(slot, index)
            val item = com.dashline.launcher.databinding.ItemAppBinding
                .inflate(inflater, row, false)

            if (pkg != null && AppRepository.isInstalled(this, pkg)) {
                item.appIcon.setImageDrawable(AppRepository.iconFor(this, pkg))
                item.appIcon.clearColorFilter()
                item.appLabel.text = AppRepository.labelFor(this, pkg)
                item.root.setOnClickListener { AppRepository.launch(this, pkg) }
                item.root.setOnLongClickListener { pickCardShortcut(slot, index); true }
            } else {
                item.appIcon.setImageResource(R.drawable.ic_add)
                item.appIcon.setColorFilter(
                    accentColor(), android.graphics.PorterDuff.Mode.SRC_IN
                )
                item.appLabel.text = ""
                item.root.setOnClickListener { pickCardShortcut(slot, index) }
                item.root.setOnLongClickListener { false }
            }
            row.addView(
                item.root,
                android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            )
        }
    }

    /** Request codes are laid out per slot so the result knows where to land. */
    private fun pickCardShortcut(slot: String, index: Int) {
        val base = if (slot == Prefs.SLOT_MEDIA) REQ_MEDIA_PANEL_BASE else REQ_PANEL_BASE
        AppPickerActivity.start(this, Prefs.ROLE_FAVORITE, base + index)
    }

    // ---- Clock style + stopwatch -------------------------------------------

    /** Swaps between the digital, analog and minimal faces. */
    private fun applyClockStyle() {
        val style = prefs.clockStyle
        val analog = style == Prefs.CLOCK_ANALOG
        val minimal = style == Prefs.CLOCK_MINIMAL

        binding.analogClock.visibility =
            if (analog) android.view.View.VISIBLE else android.view.View.GONE
        binding.bigClock.visibility =
            if (analog) android.view.View.GONE else android.view.View.VISIBLE
        // Minimal is time only — the date is what makes it "not minimal".
        binding.bigDate.visibility =
            if (minimal) android.view.View.GONE else android.view.View.VISIBLE

        binding.bigClock.setTypeface(null, if (minimal) Typeface.NORMAL else Typeface.BOLD)

        if (analog) {
            binding.analogClock.setColors(
                hands = ContextCompat.getColor(this, R.color.text_primary),
                ticks = ContextCompat.getColor(this, R.color.text_secondary),
                accentColor = accentColor()
            )
        }
        updateChronoVisibility()
    }

    private fun setupChrono() {
        binding.chronoToggle.setOnClickListener {
            if (prefs.chronoRunning) {
                // Bank the elapsed time and stop.
                prefs.chronoAccumulated += SystemClock.elapsedRealtime() - prefs.chronoStartedAt
                prefs.chronoStartedAt = 0L
            } else {
                prefs.chronoStartedAt = SystemClock.elapsedRealtime()
            }
            updateChrono()
            startChronoTicker()
        }
        binding.chronoReset.setOnClickListener {
            prefs.chronoStartedAt = 0L
            prefs.chronoAccumulated = 0L
            updateChrono()
        }
    }

    private fun updateChronoVisibility() {
        binding.chronoRow.visibility =
            if (prefs.chronoEnabled) android.view.View.VISIBLE else android.view.View.GONE
        updateChrono()
        startChronoTicker()
    }

    /** Runs the fast ticker only while the stopwatch is counting. */
    private fun startChronoTicker() {
        handler.removeCallbacks(chronoTicker)
        if (prefs.chronoEnabled && prefs.chronoRunning) handler.post(chronoTicker)
    }

    private fun updateChrono() {
        if (!prefs.chronoEnabled) return
        val running = prefs.chronoRunning
        val elapsed = prefs.chronoAccumulated +
            if (running) SystemClock.elapsedRealtime() - prefs.chronoStartedAt else 0L
        binding.chronoText.text = formatChrono(elapsed)
        binding.chronoToggle.setImageResource(
            if (running) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    /** m:ss.hh with hundredths, or h:mm:ss once past an hour. */
    private fun formatChrono(ms: Long): String {
        val hundredths = (ms / 10) % 100
        val secs = ms / 1000
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val sec = secs % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d.%02d", m, sec, hundredths)
    }

    // ---- Volume ------------------------------------------------------------

    private fun setupVolume() {
        val stream = android.media.AudioManager.STREAM_MUSIC
        binding.volumeSlider.max = audioManager.getStreamMaxVolume(stream)
        syncVolume()

        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                runCatching { audioManager.setStreamVolume(stream, progress, 0) }
                updateVolumeIcon(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { draggingVolume = true }
            override fun onStopTrackingTouch(sb: SeekBar) { draggingVolume = false }
        })
        binding.volumeIcon.setOnClickListener { toggleMute() }
    }

    /** Keeps the slider in step with the unit's own volume knob / hardware keys. */
    private fun syncVolume() {
        if (draggingVolume) return
        val vol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        binding.volumeSlider.progress = vol
        updateVolumeIcon(vol)
    }

    private fun updateVolumeIcon(vol: Int) {
        binding.volumeIcon.setImageResource(
            if (vol == 0) R.drawable.ic_volume_off else R.drawable.ic_volume
        )
    }

    private fun toggleMute() {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val vol = audioManager.getStreamVolume(stream)
        val target = if (vol > 0) 0 else audioManager.getStreamMaxVolume(stream) / 2
        runCatching { audioManager.setStreamVolume(stream, target, 0) }
        binding.volumeSlider.progress = target
        updateVolumeIcon(target)
    }

    // ---- Media widget ------------------------------------------------------

    private fun setupMedia() {
        binding.btnPlayPause.setOnClickListener { mediaMonitor?.playPause() }
        binding.btnNext.setOnClickListener { mediaMonitor?.next() }
        binding.btnPrev.setOnClickListener { mediaMonitor?.previous() }

        binding.mediaProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.mediaElapsed.text = formatDuration(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { draggingMedia = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                draggingMedia = false
                mediaMonitor?.seekTo(sb.progress.toLong())
            }
        })

        // MediaSessionManager is API 21+. On older units, stay in launch-only mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaMonitor = MediaMonitor(this) { info -> renderMedia(info) }
        }
        renderMedia(null)
    }

    /** Advances the seek bar + time labels; called each tick while media is active. */
    private fun updateMediaProgress() {
        val monitor = mediaMonitor ?: return
        if (currentMedia == null || draggingMedia) return
        val duration = monitor.durationMs()
        if (duration <= 0) {
            // Live streams / radio report no duration — hide the seek row entirely.
            binding.mediaSeekRow.visibility = android.view.View.GONE
            return
        }
        if (!showSeek) {
            // Not enough height — a squashed seek bar reads as broken.
            binding.mediaSeekRow.visibility = android.view.View.GONE
            return
        }
        val position = monitor.positionMs().coerceAtMost(duration)
        binding.mediaSeekRow.visibility = android.view.View.VISIBLE
        binding.mediaProgress.max = duration.toInt()
        binding.mediaProgress.progress = position.toInt()
        binding.mediaElapsed.text = formatDuration(position)
        binding.mediaTotal.text = formatDuration(duration)
    }

    /** m:ss, or h:mm:ss for anything an hour or longer. */
    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun renderMedia(info: MediaInfo?) {
        currentMedia = info
        if (info == null) {
            showPlaceholderArt()
            binding.mediaTitle.isSelected = false
            binding.mediaControls.visibility = android.view.View.GONE
            binding.mediaSeekRow.visibility = android.view.View.GONE

            // On 5.0+, reading what's playing needs Notification access. If it isn't
            // granted, the widget can never see media — guide the user to enable it.
            needsMediaAccess = mediaMonitor != null && !NotificationAccess.isGranted(this)
            if (needsMediaAccess) {
                binding.mediaTitle.text = getString(R.string.media_nothing_playing)
                binding.mediaArtist.text = getString(R.string.media_enable_access)
                return
            }
            val mediaLabel = AppRepository.labelFor(this, prefs.mediaApp)
            binding.mediaTitle.text = getString(R.string.media_nothing_playing)
            binding.mediaArtist.text = mediaLabel?.let {
                "${getString(R.string.media_tap_to_open)} $it"
            } ?: getString(R.string.media_tap_to_open)
            return
        }
        needsMediaAccess = false
        if (info.art != null) showAlbumArt(info.art) else showPlaceholderArt()

        binding.mediaTitle.text = info.title.ifEmpty { getString(R.string.now_playing) }
        binding.mediaTitle.isSelected = true  // starts the marquee for long titles
        binding.mediaArtist.text = info.artist
        binding.mediaArtist.visibility =
            if (info.artist.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnPlayPause.setImageResource(
            if (info.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        // Respect whatever applyMediaDensity decided there was room for.
        if (prefs.mediaFraction >= 0.34f || !isLandscapeNow()) {
            binding.mediaControls.visibility = android.view.View.VISIBLE
        }
        updateMediaProgress()
    }

    /** Square-crops the album art and rounds its corners to match the card. */
    private fun showAlbumArt(bitmap: android.graphics.Bitmap) {
        val square = try {
            val size = minOf(bitmap.width, bitmap.height)
            if (bitmap.width == bitmap.height) bitmap
            else android.graphics.Bitmap.createBitmap(
                bitmap, (bitmap.width - size) / 2, (bitmap.height - size) / 2, size, size
            )
        } catch (e: Exception) {
            bitmap
        }
        val rounded = RoundedBitmapDrawableFactory.create(resources, square)
        rounded.cornerRadius = 8f * resources.displayMetrics.density
        binding.mediaArt.setPadding(0, 0, 0, 0)
        binding.mediaArt.setImageDrawable(rounded)
    }

    /** No artwork: show the inset music glyph on the rounded tile. */
    private fun showPlaceholderArt() {
        val pad = (12f * resources.displayMetrics.density).toInt()
        binding.mediaArt.setPadding(pad, pad, pad, pad)
        binding.mediaArt.setImageResource(R.drawable.ic_audio)
    }

    /** Tapping the media card: enable access, else open the playing / chosen app. */
    private fun openMedia() {
        if (needsMediaAccess) {
            NotificationAccess.openSettings(this)
            return
        }
        val playingPkg = currentMedia?.packageName
        if (playingPkg != null && AppRepository.launch(this, playingPkg)) return
        if (!AppRepository.launchFirstAvailable(this, prefs.candidatesFor(Prefs.ROLE_MEDIA))) {
            AppPickerActivity.start(this, Prefs.ROLE_MEDIA, REQ_PICK_GENERIC)
        }
    }

    // ---- Tab actions -------------------------------------------------------

    private fun openPhone() {
        val phone = prefs.phoneApp
        if (phone == null || !AppRepository.isInstalled(this, phone)) {
            // Not chosen yet (or uninstalled): let the user pick one, then launch.
            AppPickerActivity.start(this, Prefs.ROLE_PHONE, REQ_PICK_PHONE)
            return
        }
        if (!AppRepository.launch(this, phone)) toastNotFound()
    }

    private fun openNav() = launchRole(Prefs.ROLE_NAV)

    private fun launchRole(role: String) {
        if (!AppRepository.launchFirstAvailable(this, prefs.candidatesFor(role))) {
            // Nothing installed for this role — help the user choose.
            AppPickerActivity.start(this, role, REQ_PICK_GENERIC)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE) ?: return
        when {
            requestCode == REQ_PICK_PHONE ->
                AppRepository.launch(this, pkg)  // pref already saved by the picker
            requestCode >= REQ_FAV_BASE &&
                requestCode < REQ_FAV_BASE + FavoriteDock.MAX_SLOTS -> {
                prefs.setFavorite(requestCode - REQ_FAV_BASE, pkg)
                bindFavorites()
            }
            requestCode >= REQ_MEDIA_PANEL_BASE &&
                requestCode < REQ_MEDIA_PANEL_BASE + Prefs.PANEL_SHORTCUT_COUNT -> {
                prefs.setCardShortcut(
                    Prefs.SLOT_MEDIA, requestCode - REQ_MEDIA_PANEL_BASE, pkg
                )
                bindCardShortcuts(Prefs.SLOT_MEDIA, binding.mediaShortcuts)
            }
            requestCode >= REQ_PANEL_BASE &&
                requestCode < REQ_PANEL_BASE + Prefs.PANEL_SHORTCUT_COUNT -> {
                prefs.setCardShortcut(
                    Prefs.SLOT_SECOND, requestCode - REQ_PANEL_BASE, pkg
                )
                bindCardShortcuts(Prefs.SLOT_SECOND, binding.panelShortcuts)
            }
        }
    }

    private fun toastNotFound() =
        Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show()

    // ---- Favorite-apps dock ------------------------------------------------

    private fun setupFavorites() = bindFavorites()

    /**
     * Builds the dock from the saved slot count and icon size, then fills it.
     *
     * Rebuilt rather than updated in place because both the number of slots and
     * their size are user settings, and this is where changes from the Customize
     * screen land on the way back to the dashboard.
     */
    private fun bindFavorites() {
        val dock = binding.favoritesDock
        val spec = FavoriteDock.specFor(this, prefs.favoriteSize, prefs.favoriteCount)
        FavoriteDock.applyBarHeight(binding.topBar, spec)

        dock.removeAllViews()
        for (index in 0 until prefs.favoriteCount) {
            val slot = FavoriteDock.slotView(this, spec)
            val pkg = prefs.getFavorite(index)

            if (pkg != null && AppRepository.isInstalled(this, pkg)) {
                slot.setImageDrawable(AppRepository.iconFor(this, pkg))
                // A real app icon keeps its own colours.
                slot.clearColorFilter()
                slot.setOnClickListener { AppRepository.launch(this, pkg) }
                slot.setOnLongClickListener { showFavoriteOptions(index); true }
            } else {
                slot.setImageResource(R.drawable.ic_add)
                // The empty-slot placeholder is chrome, so it follows the theme.
                slot.setColorFilter(accentColor(), android.graphics.PorterDuff.Mode.SRC_IN)
                slot.setOnClickListener { pickFavorite(index) }
                slot.setOnLongClickListener { false }
            }
            dock.addView(slot)
        }
    }

    private fun pickFavorite(index: Int) {
        AppPickerActivity.start(this, Prefs.ROLE_FAVORITE, REQ_FAV_BASE + index)
    }

    private fun showFavoriteOptions(index: Int) {
        val options = arrayOf(getString(R.string.fav_change), getString(R.string.fav_remove))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                if (which == 0) pickFavorite(index)
                else {
                    prefs.clearFavorite(index)
                    bindFavorites()
                }
            }
            .show()
    }

    // ---- Clock + weather ---------------------------------------------------

    private fun updateClock() {
        val now = Date()
        binding.bigClock.text = timeFormat.format(now)
        binding.bigDate.text = dateFormat.format(now)
        // The analog face has no timer of its own; it repaints from here.
        if (binding.analogClock.visibility == android.view.View.VISIBLE) {
            binding.analogClock.invalidate()
        }
    }

    private fun currentLocale(): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION") resources.configuration.locale
        }

    /** Refresh weather at most every 15 minutes (Open-Meteo is free but be polite). */
    private fun maybeRefreshWeather() {
        if (System.currentTimeMillis() - lastWeatherFetch >= WEATHER_INTERVAL_MS) {
            loadWeather()
        }
    }

    private fun loadWeather() {
        lastWeatherFetch = System.currentTimeMillis()
        WeatherRepository.load(this, prefs.useFahrenheit) { weather ->
            if (weather == null) {
                binding.weatherTemp.text = ""
                binding.weatherDesc.text = getString(R.string.weather_unavailable)
                binding.forecastRow.visibility = android.view.View.GONE
                return@load
            }
            binding.weatherIcon.setImageResource(weather.iconRes)
            binding.weatherTemp.text = weather.temp
            binding.weatherDesc.text = weather.description
            bindForecast(weather.forecast)
        }
    }

    private fun bindForecast(forecast: List<DailyForecast>) {
        if (forecast.isEmpty()) {
            binding.forecastRow.visibility = android.view.View.GONE
            return
        }
        val cells = listOf(
            Triple(binding.fc0Day, binding.fc0Icon, binding.fc0Temp),
            Triple(binding.fc1Day, binding.fc1Icon, binding.fc1Temp),
            Triple(binding.fc2Day, binding.fc2Icon, binding.fc2Temp)
        )
        cells.forEachIndexed { i, (dayView, iconView, tempView) ->
            val day = forecast.getOrNull(i)
            if (day == null) {
                dayView.text = ""
                iconView.visibility = android.view.View.INVISIBLE
                tempView.text = ""
            } else {
                iconView.visibility = android.view.View.VISIBLE
                dayView.text = formatWeekday(day.dateIso)
                iconView.setImageResource(day.iconRes)
                tempView.text = getString(R.string.forecast_high_low, day.high, day.low)
            }
        }
        binding.forecastRow.visibility = android.view.View.VISIBLE
    }

    private fun formatWeekday(dateIso: String): String = try {
        isoDate.parse(dateIso)?.let { dayFormat.format(it) } ?: dateIso
    } catch (e: Exception) {
        dateIso
    }

    @Suppress("DEPRECATION")
    private fun updateWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val onWifi = if (cm == null) {
            false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            // KitKat / Lollipop path.
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
        binding.wifiIcon.visibility = if (onWifi) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Setup isn't finished — bounce straight back to it.
        if (enforceSetup()) return
        // Re-apply in case "auto" theme crossed the day/night boundary while away.
        ThemeManager.apply(prefs)
        // This activity is singleTask, so onCreate won't re-run after the user
        // changes the colour scheme — repaint the accent pieces here.
        applyAccent()
        applyKeepScreenOn()
        handler.post(ticker)
        loadWeather()
        mediaMonitor?.start()
        // Widgets only receive updates while the host is listening.
        WidgetHost.attach(this)
        bindFavorites()
        // Settings changes land here: this is a singleTask HOME activity, so
        // onCreate does not run again when the user comes back from Settings.
        setupTabs()
        applyClockStyle()
        applyPanelLayout()
        setupPortraitDrawer()
        setupVolume()
        // Tabs and drawer tiles were just rebuilt, so they missed the tinting
        // BaseActivity did on the way in.
        tintIcons()
        maybeOfferUpdate()
    }

    /**
     * Sideloaded builds have no store to update them, so they look for a newer
     * release themselves — at most once a day, in the background, and never at
     * the cost of a slower start. Shown once per launch so a decline isn't
     * re-asked every time the dashboard comes back into view.
     */
    private fun maybeOfferUpdate() {
        // Coming back from the install-permission screen: carry on where we left off.
        UpdatePrompt.resumeIfReady(this)
        if (offeredUpdate || !prefs.setupDone) return
        UpdateChecker.check(this) { update ->
            if (!isFinishing && !offeredUpdate) {
                offeredUpdate = true
                UpdatePrompt.show(this, update)
            }
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(chronoTicker)
        WidgetHost.detach()
        mediaMonitor?.stop()
    }

    // As the HOME app, Back should stay on the dashboard rather than exit.
    override fun onBackPressed() {
        // no-op
    }

    companion object {
        private const val REQ_PICK_PHONE = 101
        private const val REQ_PICK_GENERIC = 102
        private const val REQ_LOCATION = 201
        private const val REQ_FAV_BASE = 300
        private const val REQ_PANEL_BASE = 400
        private const val REQ_MEDIA_PANEL_BASE = 420
        /** Portrait drawer: four across, as many rows as the space allows. */
        private const val DRAWER_COLUMNS = 4
        /** One tile: 56dp icon + label + padding. Rows are derived from this. */
        private const val DRAWER_CELL_DP = 96f
        /** A sanity cap, so a very tall screen doesn't build a hundred tiles. */
        private const val DRAWER_MAX_ROWS = 6
        private const val DRAWER_DOT_DP = 8f
        private const val DRAWER_DOT_TOUCH_DP = 32f
        /** Below this a fling is a mis-swipe, not a page turn. */
        private const val DRAWER_SWIPE_DP = 40f
        private const val WEATHER_INTERVAL_MS = 15 * 60 * 1000L
        /** ~30fps — smooth hundredths without burning CPU on a slow SoC. */
        private const val CHRONO_TICK_MS = 33L
    }
}
