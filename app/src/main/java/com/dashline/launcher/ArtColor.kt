package com.dashline.launcher

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Picks a representative colour from album art so the media card can take on the
 * mood of what's playing.
 *
 * Deliberately dependency-free — androidx.palette would work but pulls in another
 * library for something this small, and these units are memory-tight. Samples a
 * coarse grid, buckets by hue, and prefers colours with enough saturation to read
 * as a colour rather than grey.
 */
object ArtColor {

    private const val SAMPLE_STEP = 8      // sample every Nth pixel each axis
    private const val MIN_SATURATION = 0.20f
    private const val MIN_VALUE = 0.15f
    private const val MAX_VALUE = 0.95f

    /** Dominant reasonably-saturated colour, or null if the art is greyscale. */
    fun dominant(bitmap: Bitmap): Int? {
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        } catch (e: Exception) {
            return null
        }

        // 12 hue buckets, weighted by saturation so vivid pixels count for more.
        val weights = FloatArray(12)
        val sumR = FloatArray(12)
        val sumG = FloatArray(12)
        val sumB = FloatArray(12)
        val hsv = FloatArray(3)

        var x = 0
        while (x < scaled.width) {
            var y = 0
            while (y < scaled.height) {
                val c = scaled.getPixel(x, y)
                if (Color.alpha(c) >= 128) {
                    Color.colorToHSV(c, hsv)
                    val (h, s, v) = hsv
                    if (s >= MIN_SATURATION && v in MIN_VALUE..MAX_VALUE) {
                        val bucket = ((h / 30f).toInt()).coerceIn(0, 11)
                        val w = s * v
                        weights[bucket] += w
                        sumR[bucket] += Color.red(c) * w
                        sumG[bucket] += Color.green(c) * w
                        sumB[bucket] += Color.blue(c) * w
                    }
                }
                y += SAMPLE_STEP
            }
            x += SAMPLE_STEP
        }
        if (scaled != bitmap) scaled.recycle()

        val best = weights.indices.maxByOrNull { weights[it] } ?: return null
        if (weights[best] <= 0f) return null   // greyscale artwork

        return Color.rgb(
            (sumR[best] / weights[best]).toInt().coerceIn(0, 255),
            (sumG[best] / weights[best]).toInt().coerceIn(0, 255),
            (sumB[best] / weights[best]).toInt().coerceIn(0, 255)
        )
    }

    /**
     * Forces [color] into a range that keeps white text readable on top, so a
     * pale or neon album cover can't wash the card out.
     */
    fun asCardColor(color: Int, night: Boolean): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.75f)
        hsv[2] = if (night) hsv[2].coerceIn(0.28f, 0.46f) else hsv[2].coerceIn(0.62f, 0.82f)
        return Color.HSVToColor(hsv)
    }
}
