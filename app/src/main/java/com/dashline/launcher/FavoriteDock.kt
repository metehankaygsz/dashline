package com.dashline.launcher

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView

/**
 * Sizing rules for the quick-launch dock in the top bar.
 *
 * Two things users asked for pull in opposite directions — bigger icons and more
 * of them — but only on paper: the dock is a weighted strip with most of the top
 * bar to itself, so the real limits are the bar's height (an icon can't be taller
 * than the bar) and, at high slot counts on a narrow unit, its width.
 *
 * So the chosen size is a *ceiling*, not a demand. [specFor] hands back the
 * largest size that actually fits the screen at the requested count, which means
 * no combination can produce a clipped or overflowing bar.
 */
object FavoriteDock {

    const val MIN_SLOTS = 3
    const val MAX_SLOTS = 10

    const val SIZE_SMALL = "small"
    const val SIZE_MEDIUM = "medium"
    const val SIZE_LARGE = "large"

    /**
     * @param box    the tappable square
     * @param margin gap either side of it
     * @param bar    top-bar height needed to hold it
     */
    data class Spec(val id: String, val box: Int, val margin: Int, val bar: Int) {
        /** Total horizontal space one slot occupies. */
        val slotWidth: Int get() = box + margin * 2
    }

    /** Smallest first, so the fit search can walk down from the user's choice. */
    val SIZES = listOf(
        Spec(SIZE_SMALL, box = 36, margin = 5, bar = 44),
        Spec(SIZE_MEDIUM, box = 44, margin = 6, bar = 52),
        Spec(SIZE_LARGE, box = 56, margin = 8, bar = 68)
    )

    fun specById(id: String): Spec = SIZES.firstOrNull { it.id == id } ?: SIZES[1]

    fun labelFor(id: String): Int = when (id) {
        SIZE_SMALL -> R.string.size_small
        SIZE_LARGE -> R.string.size_large
        else -> R.string.size_medium
    }

    /**
     * The largest size no bigger than [preferred] that fits [count] slots.
     *
     * Falls back to the smallest rather than overflowing; [maxSlots] keeps the
     * count itself inside what even the smallest size can show, so the fallback
     * is only ever a degradation in size, never a slot the user loses.
     */
    fun specFor(context: Context, preferred: String, count: Int): Spec {
        val available = dockWidthDp(context)
        val ceiling = SIZES.indexOfFirst { it.id == preferred }.takeIf { it >= 0 } ?: 1
        for (index in ceiling downTo 0) {
            val spec = SIZES[index]
            if (spec.slotWidth * count <= available) return spec
        }
        return SIZES[0]
    }

    /** How many slots fit at the smallest size — the cap the picker offers. */
    fun maxSlots(context: Context): Int =
        (dockWidthDp(context) / SIZES[0].slotWidth).coerceIn(MIN_SLOTS, MAX_SLOTS)

    /**
     * Space the dock can use: the screen minus the top bar's own furniture (home
     * button, Wi-Fi indicator and the bar's padding). Derived from the screen
     * rather than measured so sizing is settled before the first layout pass —
     * a measure-then-rebuild loop would flicker on a slow unit.
     */
    private fun dockWidthDp(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val screen = (metrics.widthPixels / metrics.density).toInt()
        return (screen - BAR_FURNITURE_DP).coerceAtLeast(SIZES[0].slotWidth * MIN_SLOTS)
    }

    /** Build one slot view sized to [spec]. Callers attach icon and listeners. */
    fun slotView(context: Context, spec: Spec): AppCompatImageView {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        return AppCompatImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(spec.box), px(spec.box)).also {
                it.leftMargin = px(spec.margin)
                it.rightMargin = px(spec.margin)
            }
            // Only enough padding to keep the ripple off the artwork: the icons
            // read as small mostly because the old 6dp inset ate a fifth of them.
            setPadding(px(2), px(2), px(2), px(2))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(touchFeedback(context))
            // App icons keep their own colours; see BaseActivity.tintIcons.
            tag = "noTint"
            contentDescription = context.getString(R.string.pick_favorite)
        }
    }

    /**
     * The borderless ripple only exists from API 21, and KitKat is still a
     * supported target, so fall back to the bounded one there.
     */
    private fun touchFeedback(context: Context): Int {
        val attr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.R.attr.selectableItemBackgroundBorderless
        } else {
            android.R.attr.selectableItemBackground
        }
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    /** Resize the top bar so a taller icon has somewhere to sit. */
    fun applyBarHeight(bar: android.view.View, spec: Spec) {
        val density = bar.resources.displayMetrics.density
        val height = (spec.bar * density).toInt()
        val params: ViewGroup.LayoutParams = bar.layoutParams ?: return
        if (params.height != height) {
            params.height = height
            bar.layoutParams = params
        }
    }

    /** Home button + Wi-Fi icon + the bar's horizontal padding, in dp. */
    private const val BAR_FURNITURE_DP = 92
}
