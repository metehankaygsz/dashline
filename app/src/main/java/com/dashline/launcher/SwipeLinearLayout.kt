// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * A LinearLayout that turns a horizontal drag into a page change.
 *
 * A plain OnTouchListener can't do this: the app tiles inside are clickable, so
 * they consume the whole gesture and the container only ever sees the first
 * ACTION_DOWN. Intercepting is the only way to let a tap reach a tile while a
 * drag still belongs to the pager — and once we intercept, the child gets a
 * cancel, so a swipe can't launch an app by accident.
 */
class SwipeLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Called with -1 for a swipe to the previous page, +1 for the next. */
    var onSwipe: ((Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minSwipe = MIN_SWIPE_DP * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                // Horizontal and past the slop: this is a page turn, not a tap
                // or the parent's vertical scroll.
                if (abs(dx) > slop && abs(dx) > abs(event.y - downY)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                if (abs(dx) > minSwipe) onSwipe?.invoke(if (dx < 0) 1 else -1)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private companion object {
        /** Shorter than this is a mis-swipe, not a page turn. */
        const val MIN_SWIPE_DP = 40f
    }
}
