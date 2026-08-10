package com.dashline.launcher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
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
            maybeRefreshWeather()
            handler.postDelayed(this, 1000)
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
        binding.mediaControls.visibility = android.view.View.VISIBLE
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
        setupTabs()
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
        bindFavorites()
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
        private const val WEATHER_INTERVAL_MS = 15 * 60 * 1000L
    }
}
