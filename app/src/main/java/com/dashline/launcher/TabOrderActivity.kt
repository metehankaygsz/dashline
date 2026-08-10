package com.dashline.launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import com.dashline.launcher.databinding.ActivityTabOrderBinding
import com.dashline.launcher.databinding.ItemTabEditBinding

/**
 * Reorder and show/hide the bottom tabs.
 *
 * Uses up/down buttons rather than drag-and-drop: precise dragging on a screen
 * in a moving car is awkward, and buttons work with gloves on.
 */
class TabOrderActivity : BaseActivity() {

    private lateinit var binding: ActivityTabOrderBinding
    private lateinit var prefs: Prefs
    private lateinit var order: MutableList<TabAction>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTabOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.header.pageTitle.setText(R.string.settings_tabs)
        bindPageNav(binding.header.navBack, binding.header.navHome)

        order = prefs.tabOrder.toMutableList()

        binding.resetTabs.setOnClickListener {
            order = TabAction.DEFAULT_ORDER.toMutableList()
            prefs.tabOrder = order
            prefs.hiddenTabs = emptySet()
            render()
        }

        render()
    }

    private fun render() {
        val list = binding.tabList
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val divider = ContextCompat.getColor(this, R.color.divider)

        order.forEachIndexed { index, tab ->
            if (index > 0) list.addView(hairline(divider))

            val row = ItemTabEditBinding.inflate(inflater, list, false)
            row.tabEditIcon.setImageResource(tab.iconRes)
            row.tabEditLabel.setText(tab.labelRes)

            val visible = prefs.isTabVisible(tab)
            row.tabEditVisible.setImageResource(
                if (visible) R.drawable.ic_visible else R.drawable.ic_hidden
            )
            // Hidden tabs are dimmed so the state is obvious at a glance.
            row.tabEditLabel.alpha = if (visible) 1f else 0.45f
            row.tabEditIcon.alpha = if (visible) 1f else 0.45f

            if (tab.canHide) {
                row.tabEditVisible.visibility = View.VISIBLE
                row.tabEditVisible.setOnClickListener {
                    prefs.setTabVisible(tab, !visible)
                    render()
                }
            } else {
                // Apps and Settings are always shown — no toggle to press.
                row.tabEditVisible.visibility = View.INVISIBLE
                row.tabEditVisible.setOnClickListener(null)
            }

            row.tabEditUp.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
            row.tabEditDown.visibility =
                if (index == order.lastIndex) View.INVISIBLE else View.VISIBLE

            row.tabEditUp.setOnClickListener { move(index, index - 1) }
            row.tabEditDown.setOnClickListener { move(index, index + 1) }

            list.addView(row.root)
        }
    }

    private fun move(from: Int, to: Int) {
        if (to !in order.indices) return
        order.add(to, order.removeAt(from))
        prefs.tabOrder = order
        render()
    }

    /** Matches @style/SettingsDivider, added between rows. */
    private fun hairline(color: Int): View = View(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (1f * resources.displayMetrics.density).toInt()
        ).also { it.marginStart = (16f * resources.displayMetrics.density).toInt() }
        setBackgroundColor(color)
    }
}
