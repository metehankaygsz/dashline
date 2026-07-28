package com.radiolauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.radiolauncher.databinding.ActivityAppPickerBinding

/**
 * Full-screen app chooser for a given "role" (phone / nav / radio / bluetooth).
 * Saves the selection to Prefs and returns the chosen package to the caller.
 */
class AppPickerActivity : BaseActivity() {

    private lateinit var binding: ActivityAppPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val role = intent.getStringExtra(EXTRA_ROLE) ?: Prefs.ROLE_PHONE
        binding.header.pageTitle.text = titleForRole(role)
        bindPageNav(binding.header.navBack, binding.header.navHome)

        val prefs = Prefs(this)
        val apps = AppRepository.loadLaunchableApps(this)
        val adapter = AppAdapter(apps, onClick = { app ->
            prefs.setForRole(role, app.packageName)
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra(EXTRA_PACKAGE, app.packageName)
                putExtra(EXTRA_ROLE, role)
            })
            finish()
        })

        binding.pickerGrid.layoutManager = GridLayoutManager(this, 5)
        binding.pickerGrid.adapter = adapter
        binding.pickerGrid.setHasFixedSize(true)
    }

    private fun titleForRole(role: String): String = when (role) {
        Prefs.ROLE_PHONE -> getString(R.string.settings_phone_app)
        Prefs.ROLE_NAV -> getString(R.string.settings_nav_app)
        Prefs.ROLE_RADIO -> getString(R.string.settings_radio_app)
        Prefs.ROLE_FAVORITE -> getString(R.string.pick_favorite)
        else -> getString(R.string.pick_an_app)
    }

    companion object {
        const val EXTRA_ROLE = "extra_role"
        const val EXTRA_PACKAGE = "extra_package"

        fun start(activity: Activity, role: String, requestCode: Int) {
            val intent = Intent(activity, AppPickerActivity::class.java)
                .putExtra(EXTRA_ROLE, role)
            activity.startActivityForResult(intent, requestCode)
        }
    }
}
