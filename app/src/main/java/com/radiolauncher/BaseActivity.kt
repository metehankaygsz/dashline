package com.radiolauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Base for all activities:
 *  - applies the in-app language override to the context
 *  - runs fullscreen (system status + navigation bars hidden), since the launcher
 *    provides its own on-screen navigation
 *  - provides the shared Back / Home wiring for page headers
 */
abstract class BaseActivity : AppCompatActivity() {

    private var contentRoot: View? = null
    private var appliedAccent: Int? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        applyGradient()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide after dialogs, notifications or a swipe temporarily reveal the bars.
        if (hasFocus) enterImmersive()
    }

    /**
     * Immersive-sticky fullscreen. These flags are deprecated on API 30+ but still
     * work, and unlike WindowInsetsController they're available from API 19 — which
     * matters for the old head units this launcher targets.
     */
    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ---- UI colour gradient -------------------------------------------------

    /** The accent colour of the currently selected gradient. */
    protected fun accentColor(): Int = GradientThemes.accent(GradientThemes.current(this))

    /**
     * Every screen sets its content through this, so applying the gradient here
     * themes the whole app without each activity having to opt in.
     */
    override fun setContentView(view: View) {
        super.setContentView(view)
        contentRoot = view
        applyGradient()
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        contentRoot = findViewById(android.R.id.content)
        applyGradient()
    }

    /**
     * Re-applied on every resume, not just onCreate: HomeActivity is a singleTask
     * HOME activity that stays alive, so it would otherwise keep the gradient it
     * was launched with after the user picks a different one.
     */
    protected fun applyGradient() {
        val root = contentRoot ?: return
        val preset = GradientThemes.current(this)
        root.background = GradientThemes.background(this, preset)

        val accent = GradientThemes.accent(preset)
        // Re-tinting must match against whatever was applied last, not the stock
        // colour, or a second change would find nothing to recolour.
        val previous = appliedAccent ?: ContextCompat.getColor(this, R.color.accent)
        if (previous != accent) retintAccent(root, previous, accent)
        appliedAccent = accent
    }

    /**
     * Recolours anything drawn in the previous accent (section headers, etc.) to
     * the selected gradient's accent. Matching on colour means new views pick this
     * up automatically without needing ids.
     */
    private fun retintAccent(root: View, from: Int, to: Int) {
        forEachView(root) { v ->
            if (v is TextView && v.currentTextColor == from) v.setTextColor(to)
        }
    }

    private fun forEachView(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) forEachView(view.getChildAt(i), action)
        }
    }

    /** Wire a page header's Back / Home buttons. */
    protected fun bindPageNav(backButton: View, homeButton: View) {
        backButton.setOnClickListener { onBackPressed() }
        homeButton.setOnClickListener { goHome() }
    }

    /** Return to the launcher dashboard, clearing any pages stacked above it. */
    protected fun goHome() {
        startActivity(
            Intent(this, HomeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }
}
