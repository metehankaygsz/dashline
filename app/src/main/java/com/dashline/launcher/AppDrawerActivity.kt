package com.dashline.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.dashline.launcher.databinding.ActivityAppDrawerBinding

/** Grid of every launchable app, with search, a frequently-used strip, and hiding. */
class AppDrawerActivity : BaseActivity() {

    private lateinit var binding: ActivityAppDrawerBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppAdapter
    private lateinit var frequentAdapter: AppAdapter

    private var allApps: List<AppInfo> = emptyList()

    // Refresh the list when apps are installed/removed while the drawer is open.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDrawerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.header.pageTitle.setText(R.string.all_apps)
        bindPageNav(binding.header.navBack, binding.header.navHome)

        adapter = AppAdapter(emptyList(), onClick = { launch(it) }, onLongClick = { confirmHide(it) })
        binding.appGrid.layoutManager = GridLayoutManager(this, 5)
        binding.appGrid.adapter = adapter
        binding.appGrid.setHasFixedSize(true)

        frequentAdapter = AppAdapter(emptyList(), onClick = { launch(it) }, itemWidthDp = 88)
        binding.frequentGrid.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.frequentGrid.adapter = frequentAdapter

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        reload()
    }

    private fun reload() {
        allApps = AppRepository.loadLaunchableApps(this, prefs.hiddenApps)

        val byPackage = allApps.associateBy { it.packageName }
        val frequent = Usage.topApps(this, FREQUENT_COUNT).mapNotNull { byPackage[it] }
        frequentAdapter.update(frequent)

        applyFilter(binding.searchBox.text?.toString().orEmpty())
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allApps
        else allApps.filter { it.label.lowercase().contains(q) }
        adapter.update(filtered)

        // Frequent strip only makes sense when not searching.
        val showFrequent = q.isEmpty() && frequentAdapter.itemCount > 0
        binding.frequentHeader.visibility = if (showFrequent) View.VISIBLE else View.GONE
        binding.frequentGrid.visibility = if (showFrequent) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun launch(app: AppInfo) {
        if (!AppRepository.launch(this, app.packageName)) {
            Toast.makeText(
                this, getString(R.string.couldnt_launch, app.label), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmHide(app: AppInfo) {
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf(getString(R.string.hide_app))) { _, _ ->
                prefs.hideApp(app.packageName)
                reload()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        reload()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: IllegalArgumentException) {
            // wasn't registered; ignore
        }
    }

    private companion object {
        const val FREQUENT_COUNT = 6
    }
}
