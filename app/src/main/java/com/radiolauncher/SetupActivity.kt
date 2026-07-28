package com.radiolauncher

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.radiolauncher.databinding.ActivitySetupBinding

/**
 * First-run setup. The user picks their Phone app (required by product spec) and
 * can confirm/change the Navigation app (defaults to Google Maps).
 */
class SetupActivity : BaseActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.chooseLanguageRow.setOnClickListener { showLanguageDialog() }
        binding.choosePhoneRow.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_PHONE, REQ_PHONE)
        }
        binding.chooseNavRow.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_NAV, REQ_NAV)
        }
        binding.chooseMediaRow.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_MEDIA, REQ_MEDIA)
        }
        binding.locationRow.setOnClickListener { grantLocation() }
        binding.mediaAccessRow.setOnClickListener {
            if (!NotificationAccess.isGranted(this)) NotificationAccess.openSettings(this)
        }
        binding.doneButton.setOnClickListener {
            prefs.setupDone = true
            // Offer to become the default launcher as the last onboarding step.
            if (!DefaultLauncher.isDefault(this)) {
                DefaultLauncher.request(this, REQ_DEFAULT)
            } else {
                goHome()
            }
        }

        // Ask for location up front — one dialog, rather than surprising the user
        // on the dashboard later. The rest are opt-in taps below.
        if (!Permissions.hasLocation(this)) {
            prefs.askedLocation = true
            Permissions.requestLocation(this, REQ_LOCATION)
        }

        refresh()
    }

    // goHome() is inherited from BaseActivity.

    private fun grantLocation() {
        when {
            Permissions.hasLocation(this) -> Unit
            // Permanently denied: the dialog won't show again, so open app settings.
            Permissions.locationPermanentlyDenied(this) -> Permissions.openAppSettings(this)
            else -> Permissions.requestLocation(this, REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()  // pick up access granted in system settings while we were away
    }

    private fun showLanguageDialog() {
        val codes = listOf("") + LocaleManager.LANGUAGES.map { it.code }
        val labels = (listOf(getString(R.string.language_system)) +
            LocaleManager.LANGUAGES.map { it.name }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_choose_language)
            .setItems(labels) { _, which ->
                prefs.language = codes[which]
                recreate()  // re-inflate the setup screen in the chosen language
            }
            .show()
    }

    private fun refresh() {
        binding.languageValue.text = LocaleManager.displayName(this, prefs.language)
        binding.phoneValue.text =
            AppRepository.labelFor(this, prefs.phoneApp) ?: getString(R.string.not_set)
        binding.navValue.text =
            AppRepository.labelFor(this, prefs.navApp) ?: getString(R.string.not_set)
        binding.mediaValue.text =
            AppRepository.labelFor(this, prefs.mediaApp) ?: getString(R.string.not_set)
        binding.locationValue.text = accessLabel(Permissions.hasLocation(this))
        binding.mediaAccessValue.text = accessLabel(NotificationAccess.isGranted(this))
    }

    private fun accessLabel(granted: Boolean): String =
        getString(if (granted) R.string.access_granted else R.string.access_needed)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DEFAULT) goHome() else refresh()
    }

    // Don't let Back skip setup silently on a car screen; require the Done button.
    override fun onBackPressed() { /* no-op */ }

    companion object {
        private const val REQ_PHONE = 1
        private const val REQ_NAV = 2
        private const val REQ_MEDIA = 3
        private const val REQ_DEFAULT = 4
        private const val REQ_LOCATION = 5
    }
}
