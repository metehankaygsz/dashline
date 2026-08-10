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
    private var appWidgetHost: android.appwidget.AppWidgetHost? = null

    private lateinit var favSlots: List<AppCompatImageView>

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

        applySecondCardMode()
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

    /** Phone shortcut, a row of app shortcuts, or a hosted app widget. */
    private fun applySecondCardMode() {
        val mode = prefs.secondCardMode
        val shortcuts = mode == Prefs.SECOND_SHORTCUTS
        val widget = mode == Prefs.SECOND_WIDGET

        binding.phoneDefault.visibility =
            if (shortcuts || widget) android.view.View.GONE else android.view.View.VISIBLE
        binding.panelShortcuts.visibility =
            if (shortcuts) android.view.View.VISIBLE else android.view.View.GONE
        binding.panelWidget.visibility =
            if (widget) android.view.View.VISIBLE else android.view.View.GONE

        // Only dial when the card is actually the phone card.
        binding.phoneCard.setOnClickListener(
            if (shortcuts || widget) null else android.view.View.OnClickListener { openPhone() }
        )
        binding.phoneCard.isClickable = !shortcuts && !widget

        when {
            shortcuts -> bindPanelShortcuts()
            widget -> bindPanelWidget()
        }
    }

    /**
     * Renders the bound app widget. Anything missing — never picked, uninstalled,
     * or permission revoked — falls back to the phone card rather than an empty
     * hole in the dashboard.
     */
    private fun bindPanelWidget() {
        val container = binding.panelWidget
        container.removeAllViews()

        val id = prefs.panelWidgetId
        if (id == WidgetHost.INVALID_ID) {
            fallbackToPhoneCard()
            return
        }
        val view = WidgetHost.createView(this, widgetHost(), id)
        if (view == null) {
            // Provider gone. Drop the stale id so we don't retry every resume.
            prefs.panelWidgetId = WidgetHost.INVALID_ID
            prefs.secondCardMode = Prefs.SECOND_PHONE
            fallbackToPhoneCard()
            return
        }
        container.addView(
            view,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Tell the widget its real size once the card has been measured.
        container.post {
            val d = resources.displayMetrics.density
            if (container.width > 0 && container.height > 0) {
                WidgetHost.resize(
                    view,
                    (container.width / d).toInt(),
                    (container.height / d).toInt()
                )
            }
        }
    }

    private fun isLandscapeNow(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun fallbackToPhoneCard() {
        binding.panelWidget.visibility = android.view.View.GONE
        binding.phoneDefault.visibility = android.view.View.VISIBLE
        binding.phoneCard.setOnClickListener { openPhone() }
        binding.phoneCard.isClickable = true
    }

    private fun widgetHost(): android.appwidget.AppWidgetHost {
        var h = appWidgetHost
        if (h == null) {
            h = WidgetHost.host(this)
            appWidgetHost = h
        }
        return h
    }

    private fun bindPanelShortcuts() {
        val row = binding.panelShortcuts
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val inflater = layoutInflater

        for (index in 0 until Prefs.PANEL_SHORTCUT_COUNT) {
            val pkg = prefs.getPanelShortcut(index)
            val item = com.dashline.launcher.databinding.ItemAppBinding
                .inflate(inflater, row, false)

            if (pkg != null && AppRepository.isInstalled(this, pkg)) {
                item.appIcon.setImageDrawable(AppRepository.iconFor(this, pkg))
                item.appLabel.text = AppRepository.labelFor(this, pkg)
                item.root.setOnClickListener { AppRepository.launch(this, pkg) }
                item.root.setOnLongClickListener { pickPanelShortcut(index); true }
            } else {
                item.appIcon.setImageResource(R.drawable.ic_add)
                item.appLabel.text = ""
                item.root.setOnClickListener { pickPanelShortcut(index) }
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

    private fun pickPanelShortcut(index: Int) {
        AppPickerActivity.start(this, Prefs.ROLE_FAVORITE, REQ_PANEL_BASE + index)
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
            requestCode >= REQ_FAV_BASE && requestCode < REQ_FAV_BASE + Prefs.FAVORITE_COUNT -> {
                prefs.setFavorite(requestCode - REQ_FAV_BASE, pkg)
                bindFavorites()
            }
            requestCode >= REQ_PANEL_BASE &&
                requestCode < REQ_PANEL_BASE + Prefs.PANEL_SHORTCUT_COUNT -> {
                prefs.setPanelShortcut(requestCode - REQ_PANEL_BASE, pkg)
                bindPanelShortcuts()
            }
        }
    }

    private fun toastNotFound() =
        Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show()

    // ---- Favorite-apps dock ------------------------------------------------

    private fun setupFavorites() {
        favSlots = listOf(
            binding.favSlot0, binding.favSlot1, binding.favSlot2,
            binding.favSlot3, binding.favSlot4
        )
        bindFavorites()
    }

    private fun bindFavorites() {
        favSlots.forEachIndexed { index, slot ->
            val pkg = prefs.getFavorite(index)
            if (pkg != null && AppRepository.isInstalled(this, pkg)) {
                slot.setImageDrawable(AppRepository.iconFor(this, pkg))
                slot.setOnClickListener { AppRepository.launch(this, pkg) }
                slot.setOnLongClickListener { showFavoriteOptions(index); true }
            } else {
                slot.setImageResource(R.drawable.ic_add)
                slot.setOnClickListener { pickFavorite(index) }
                slot.setOnLongClickListener { false }
            }
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
        // A hosted widget only receives updates while the host is listening.
        if (prefs.secondCardMode == Prefs.SECOND_WIDGET) {
            runCatching { widgetHost().startListening() }
        }
        bindFavorites()
        // Settings changes land here: this is a singleTask HOME activity, so
        // onCreate does not run again when the user comes back from Settings.
        setupTabs()
        applyClockStyle()
        applyPanelLayout()
        setupVolume()
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
        runCatching { appWidgetHost?.stopListening() }
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
        private const val WEATHER_INTERVAL_MS = 15 * 60 * 1000L
        /** ~30fps — smooth hundredths without burning CPU on a slow SoC. */
        private const val CHRONO_TICK_MS = 33L
    }
}
