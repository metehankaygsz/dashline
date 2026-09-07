// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * A selectable colour scheme for the whole UI: a two-stop background gradient
 * (separate dark / light variants) plus the accent used for highlights.
 */
data class GradientPreset(
    val id: String,
    val nameRes: Int,
    val darkStart: String,
    val darkEnd: String,
    val lightStart: String,
    val lightEnd: String,
    val accentHex: String
)

object GradientThemes {

    /**
     * First entry is the default (the original SYNC-style navy).
     *
     * The light stops are deliberately pastel rather than near-white: a dash in
     * daylight is already glare, and a white screen adds to it. They carry the
     * same hue as their dark counterparts, a few shades up, so switching modes
     * changes the brightness of a theme rather than its identity.
     */
    val PRESETS = listOf(
        GradientPreset(
            "midnight", R.string.gradient_midnight,
            darkStart = "#061726", darkEnd = "#0D3A5E",
            lightStart = "#C9DAEC", lightEnd = "#9DBAD8",
            accentHex = "#3B9EE5"
        ),
        GradientPreset(
            "ocean", R.string.gradient_ocean,
            darkStart = "#041E26", darkEnd = "#0B4552",
            lightStart = "#C4E2E8", lightEnd = "#93C6D2",
            accentHex = "#1FB6CC"
        ),
        GradientPreset(
            "sunset", R.string.gradient_sunset,
            darkStart = "#2B0F1C", darkEnd = "#5A2233",
            lightStart = "#F4D6C4", lightEnd = "#E5AC90",
            accentHex = "#F07A3C"
        ),
        GradientPreset(
            "forest", R.string.gradient_forest,
            darkStart = "#08201A", darkEnd = "#134534",
            lightStart = "#CBE6D4", lightEnd = "#9BCBAE",
            accentHex = "#31A56B"
        ),
        GradientPreset(
            "violet", R.string.gradient_violet,
            darkStart = "#170F2E", darkEnd = "#341E5C",
            lightStart = "#DAD0F1", lightEnd = "#B6A5E1",
            accentHex = "#8B6DF0"
        ),
        GradientPreset(
            "crimson", R.string.gradient_crimson,
            darkStart = "#280B12", darkEnd = "#521825",
            lightStart = "#F2CFD6", lightEnd = "#E2A5B3",
            accentHex = "#E4455E"
        ),
        GradientPreset(
            "graphite", R.string.gradient_graphite,
            darkStart = "#101215", darkEnd = "#282D34",
            lightStart = "#D8DCE3", lightEnd = "#B3BAC5",
            accentHex = "#7C8794"
        )
    )

    /** Look up a preset by id, falling back to the first (default) one. */
    fun byId(id: String): GradientPreset =
        PRESETS.firstOrNull { it.id == id } ?: PRESETS[0]

    fun current(context: Context): GradientPreset = byId(Prefs(context).gradient)

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** The full-screen background for a preset, in the current day/night mode. */
    fun background(context: Context, preset: GradientPreset): GradientDrawable {
        val night = isNight(context)
        val start = Color.parseColor(if (night) preset.darkStart else preset.lightStart)
        val end = Color.parseColor(if (night) preset.darkEnd else preset.lightEnd)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)
        )
    }

    fun accent(preset: GradientPreset): Int = Color.parseColor(preset.accentHex)

    /** Small preview swatch used in the picker. */
    fun swatch(context: Context, preset: GradientPreset, radiusPx: Float): GradientDrawable =
        background(context, preset).apply { cornerRadius = radiusPx }

    /** Solid rounded rectangle in [color] — used for accent-tinted cards. */
    fun roundedRect(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    /** Mix [color] toward black by [amount] (0..1). */
    fun darken(color: Int, amount: Float): Int {
        val f = 1f - amount
        return Color.rgb(
            (Color.red(color) * f).toInt(),
            (Color.green(color) * f).toInt(),
            (Color.blue(color) * f).toInt()
        )
    }

    /** [color] at [alpha] (0..255). */
    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
