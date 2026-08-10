package com.dashline.launcher

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dashline.launcher.databinding.ActivitySettingsBinding

/** Launcher settings: change which app each role uses. */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private companion object {
        const val REQ_DEFAULT_LAUNCHER = 10
        const val REQ_LOCATION = 11
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.header.pageTitle.setText(R.string.settings_title)
        bindPageNav(binding.header.navBack, binding.header.navHome)

        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.rowPhone.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_PHONE, 1)
        }
        binding.rowNav.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_NAV, 2)
        }
        binding.rowRadio.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_RADIO, 3)
        }
        binding.rowMedia.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_MEDIA, 5)
        }
        binding.rowAndroidAuto.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_ANDROID_AUTO, 6)
        }
        binding.rowCarPlay.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_CARPLAY, 7)
        }
        binding.rowLocationAccess.setOnClickListener {
            when {
                Permissions.hasLocation(this) -> Unit
                Permissions.locationPermanentlyDenied(this) -> Permissions.openAppSettings(this)
                else -> Permissions.requestLocation(this, REQ_LOCATION)
            }
        }
        binding.rowNotificationAccess.setOnClickListener {
            NotificationAccess.openSettings(this)
        }
        binding.rowTempUnit.setOnClickListener {
            prefs.useFahrenheit = !prefs.useFahrenheit
            refresh()
        }
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.rowPanelOrder.setOnClickListener { showPanelOrderDialog() }
        binding.rowPanelSplit.setOnClickListener { showPanelSplitDialog() }
        binding.rowSecondCard.setOnClickListener { showSecondCardDialog() }
        binding.rowClockStyle.setOnClickListener { showClockStyleDialog() }
        binding.rowChrono.setOnClickListener {
            prefs.chronoEnabled = !prefs.chronoEnabled
            refresh()
        }
        binding.rowTabs.setOnClickListener {
            startActivity(Intent(this, TabOrderActivity::class.java))
        }
        binding.rowGradient.setOnClickListener {
            startActivity(Intent(this, GradientPickerActivity::class.java))
        }
        binding.rowKeepOn.setOnClickListener {
            prefs.keepScreenOn = !prefs.keepScreenOn
            refresh()
        }
        binding.rowDefaultLauncher.setOnClickListener {
            DefaultLauncher.request(this, REQ_DEFAULT_LAUNCHER)
        }
        binding.rowResetHidden.setOnClickListener {
            prefs.clearHiddenApps()
            refresh()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()  // reflect notification-access / default-launcher changes on return
    }

    private fun showLanguageDialog() {
        val codes = listOf("") + LocaleManager.LANGUAGES.map { it.code }
        val labels = (listOf(getString(R.string.language_system)) +
            LocaleManager.LANGUAGES.map { it.name }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setItems(labels) { _, which ->
                prefs.language = codes[which]
                // Relaunch the launcher so every screen re-inflates in the new language.
                val intent = Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            }
            .show()
    }

    private fun showPanelOrderDialog() = pick(
        R.string.settings_panel_order,
        listOf(
            Prefs.PANEL_MEDIA_FIRST to R.string.panel_media_first,
            Prefs.PANEL_PHONE_FIRST to R.string.panel_phone_first
        )
    ) { prefs.panelOrder = it }

    private fun showPanelSplitDialog() = pick(
        R.string.settings_panel_split,
        listOf(
            Prefs.SPLIT_MEDIA_LARGE to R.string.split_media_large,
            Prefs.SPLIT_EQUAL to R.string.split_equal,
            Prefs.SPLIT_SECOND_LARGE to R.string.split_second_large
        )
    ) { prefs.panelSplit = it }

    private fun showSecondCardDialog() = pick(
        R.string.settings_second_card,
        listOf(
            Prefs.SECOND_PHONE to R.string.second_phone,
            Prefs.SECOND_SHORTCUTS to R.string.second_shortcuts
        )
    ) { prefs.secondCardMode = it }

    /** Small helper for the single-choice dialogs this screen is full of. */
    private fun pick(titleRes: Int, options: List<Pair<String, Int>>, onPick: (String) -> Unit) {
        val labels = options.map { getString(it.second) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(labels) { _, which ->
                onPick(options[which].first)
                refresh()
            }
            .show()
    }

    private fun showClockStyleDialog() {
        val styles = listOf(
            Prefs.CLOCK_DIGITAL to R.string.clock_digital,
            Prefs.CLOCK_ANALOG to R.string.clock_analog,
            Prefs.CLOCK_MINIMAL to R.string.clock_minimal
        )
        val labels = styles.map { getString(it.second) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clock_style)
            .setItems(labels) { _, which ->
                prefs.clockStyle = styles[which].first
                refresh()
            }
            .show()
    }

    private fun showThemeDialog() {
        val modes = listOf(
            Prefs.THEME_AUTO to R.string.theme_auto,
            Prefs.THEME_LIGHT to R.string.theme_light,
            Prefs.THEME_DARK to R.string.theme_dark,
            Prefs.THEME_SYSTEM to R.string.theme_system
        )
        val labels = modes.map { getString(it.second) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setItems(labels) { _, which ->
                prefs.themeMode = modes[which].first
                ThemeManager.apply(prefs)  // recreates activities as needed
                refresh()
            }
            .show()
    }

    private fun refresh() {
        binding.languageValue.text = LocaleManager.displayName(this, prefs.language)
        binding.phoneValue.text = label(prefs.phoneApp)
        binding.navValue.text = label(prefs.navApp)
        binding.radioValue.text = label(prefs.radioApp)
        binding.mediaValue.text = label(prefs.mediaApp)
        binding.androidAutoValue.text = label(prefs.androidAutoApp)
        binding.carPlayValue.text = label(prefs.carPlayApp)
        binding.locationAccessValue.text = getString(
            if (Permissions.hasLocation(this)) R.string.access_granted
            else R.string.access_needed
        )
        binding.notificationAccessValue.text = getString(
            if (NotificationAccess.isGranted(this)) R.string.access_enabled
            else R.string.access_disabled
        )
        binding.tempUnitValue.text = getString(
            if (prefs.useFahrenheit) R.string.unit_fahrenheit else R.string.unit_celsius
        )
        binding.themeValue.text = getString(themeLabel(prefs.themeMode))
        binding.tabsValue.text = getString(R.string.tabs_count, prefs.visibleTabs().size)
        binding.panelOrderValue.text = getString(
            if (prefs.panelOrder == Prefs.PANEL_PHONE_FIRST) R.string.panel_phone_first
            else R.string.panel_media_first
        )
        binding.panelSplitValue.text = getString(when (prefs.panelSplit) {
            Prefs.SPLIT_EQUAL -> R.string.split_equal
            Prefs.SPLIT_SECOND_LARGE -> R.string.split_second_large
            else -> R.string.split_media_large
        })
        binding.secondCardValue.text = getString(
            if (prefs.secondCardMode == Prefs.SECOND_SHORTCUTS) R.string.second_shortcuts
            else R.string.second_phone
        )
        binding.clockStyleValue.text = getString(when (prefs.clockStyle) {
            Prefs.CLOCK_ANALOG -> R.string.clock_analog
            Prefs.CLOCK_MINIMAL -> R.string.clock_minimal
            else -> R.string.clock_digital
        })
        binding.chronoValue.text = getString(if (prefs.chronoEnabled) R.string.on else R.string.off)

        val gradient = GradientThemes.current(this)
        binding.gradientValue.setText(gradient.nameRes)
        binding.gradientSwatch.background = GradientThemes.swatch(
            this, gradient, 4f * resources.displayMetrics.density
        )
        binding.keepOnValue.text = getString(if (prefs.keepScreenOn) R.string.on else R.string.off)
        binding.defaultLauncherValue.text = getString(
            if (DefaultLauncher.isDefault(this)) R.string.yes else R.string.set_now
        )
        binding.resetHiddenValue.text = getString(R.string.hidden_count, prefs.hiddenApps.size)
    }

    private fun themeLabel(mode: String): Int = when (mode) {
        Prefs.THEME_LIGHT -> R.string.theme_light
        Prefs.THEME_DARK -> R.string.theme_dark
        Prefs.THEME_SYSTEM -> R.string.theme_system
        else -> R.string.theme_auto
    }

    private fun label(pkg: String?): String =
        AppRepository.labelFor(this, pkg) ?: getString(R.string.not_set)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
