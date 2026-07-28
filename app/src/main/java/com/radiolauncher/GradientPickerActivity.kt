package com.radiolauncher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.radiolauncher.databinding.ActivityGradientPickerBinding
import com.radiolauncher.databinding.ItemGradientBinding

/**
 * Picker for the UI colour gradient. Rows are built from GradientThemes.PRESETS
 * and styled to match the rest of Settings (grouped card, hairline dividers).
 */
class GradientPickerActivity : BaseActivity() {

    private lateinit var binding: ActivityGradientPickerBinding
    private lateinit var prefs: Prefs
    private val rows = mutableListOf<ItemGradientBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGradientPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.header.pageTitle.setText(R.string.settings_gradient)
        bindPageNav(binding.header.navBack, binding.header.navHome)

        buildRows()
        refresh()
    }

    private fun buildRows() {
        val inflater = LayoutInflater.from(this)
        val radius = 8f * resources.displayMetrics.density

        GradientThemes.PRESETS.forEachIndexed { index, preset ->
            if (index > 0) binding.gradientList.addView(divider())

            val row = ItemGradientBinding.inflate(inflater, binding.gradientList, false)
            row.gradientName.setText(preset.nameRes)
            row.swatch.background = GradientThemes.swatch(this, preset, radius)
            row.root.setOnClickListener { select(preset) }
            binding.gradientList.addView(row.root)
            rows.add(row)
        }
    }

    /** Hairline matching @style/SettingsDivider, added between rows. */
    private fun divider(): View = View(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (1f * resources.displayMetrics.density).toInt()
        ).also { it.marginStart = (16f * resources.displayMetrics.density).toInt() }
        setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(this@GradientPickerActivity, R.color.divider)
        )
    }

    private fun select(preset: GradientPreset) {
        if (prefs.gradient == preset.id) return
        prefs.gradient = preset.id
        // Recreate so every view picks up the new gradient and accent immediately.
        recreate()
    }

    private fun refresh() {
        val selected = GradientThemes.current(this)
        val accent = GradientThemes.accent(selected)
        val radius = 12f * resources.displayMetrics.density

        binding.previewCard.background = GradientThemes.background(this, selected)
            .apply { cornerRadius = radius }
        binding.previewName.setText(selected.nameRes)

        rows.forEachIndexed { index, row ->
            val isSelected = GradientThemes.PRESETS[index].id == selected.id
            row.selectedTick.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            row.selectedTick.setColorFilter(accent)
        }
    }
}
