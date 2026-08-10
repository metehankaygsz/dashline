package com.dashline.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Minimal analog face: four cardinal ticks, two hands, an accent second hand.
 * No numerals, no bezel — it should read like the rest of the dashboard rather
 * than like a watch skin.
 *
 * Redrawn by the host's one-second ticker; it doesn't run its own timer.
 */
class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var handColor = Color.WHITE
    private var tickColor = Color.WHITE
    private var accent = Color.WHITE

    /** Called by the host so the clock follows the selected gradient. */
    fun setColors(hands: Int, ticks: Int, accentColor: Int) {
        handColor = hands
        tickColor = ticks
        accent = accentColor
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Always square, driven by whichever axis the parent constrains more.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            w > 0 && h > 0 -> min(w, h)
            w > 0 -> w
            h > 0 -> h
            else -> (160 * resources.displayMetrics.density).toInt()
        }
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)
        if (r <= 0f) return

        val unit = r / 100f
        tickPaint.color = tickColor
        tickPaint.alpha = 110
        tickPaint.strokeWidth = 2.5f * unit
        handPaint.color = handColor
        dotPaint.color = accent
        secondPaint.color = accent
        secondPaint.strokeWidth = 1.6f * unit

        // Cardinal ticks only — 12 would be busy at dashboard size.
        for (i in 0 until 4) {
            val angle = Math.toRadians(i * 90.0)
            val outer = r * 0.92f
            val inner = r * 0.80f
            canvas.drawLine(
                cx + (sin(angle) * outer).toFloat(), cy - (cos(angle) * outer).toFloat(),
                cx + (sin(angle) * inner).toFloat(), cy - (cos(angle) * inner).toFloat(),
                tickPaint
            )
        }

        val now = Calendar.getInstance()
        val sec = now.get(Calendar.SECOND).toFloat()
        val minute = now.get(Calendar.MINUTE) + sec / 60f
        val hour = (now.get(Calendar.HOUR) % 12) + minute / 60f

        // Hour
        handPaint.strokeWidth = 6f * unit
        drawHand(canvas, cx, cy, hour / 12f * 360.0, r * 0.50f, handPaint)
        // Minute
        handPaint.strokeWidth = 4f * unit
        drawHand(canvas, cx, cy, minute / 60f * 360.0, r * 0.72f, handPaint)
        // Second
        drawHand(canvas, cx, cy, sec / 60f * 360.0, r * 0.80f, secondPaint)

        canvas.drawCircle(cx, cy, 4f * unit, dotPaint)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, deg: Double, len: Float, p: Paint) {
        val a = Math.toRadians(deg)
        // Small tail past the centre reads better than a hand starting at the pivot.
        val tail = len * 0.14f
        canvas.drawLine(
            cx - (sin(a) * tail).toFloat(), cy + (cos(a) * tail).toFloat(),
            cx + (sin(a) * len).toFloat(), cy - (cos(a) * len).toFloat(),
            p
        )
    }
}
