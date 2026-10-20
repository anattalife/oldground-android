package com.brandonthomas.oldground.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Two maps stacked for Swipe. The lower map is the live one you pan and zoom. The upper
 * map (drawn into a TextureView so it can be clipped) shows the other layer and is cut
 * off at the divider. The upper map's frame never takes touches, so every pan and pinch
 * goes to the lower map, which the upper one follows frame by frame.
 */
@SuppressLint("ViewConstructor")
class SwipeFrame(context: Context, lower: View, upper: View) : FrameLayout(context) {
    private val clip = object : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent?) = false
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent?) = false
    }

    init {
        (lower.parent as? ViewGroup)?.removeView(lower)
        (upper.parent as? ViewGroup)?.removeView(upper)
        addView(lower, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clip.addView(upper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(clip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private var dividerPx = -1

    /** Draw the upper layer right of the divider instead of left (when the map itself is the left side). */
    var upperOnRight = false
        set(v) { field = v; applyClip() }

    /** Shows the upper map left of this x position (pixels). */
    fun setDivider(px: Int) {
        dividerPx = px
        applyClip()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyClip()
    }

    private fun applyClip() {
        val x = if (dividerPx < 0) width / 2 else dividerPx
        val cx = x.coerceIn(0, width)
        clip.clipBounds = if (upperOnRight) Rect(cx, 0, width, height) else Rect(0, 0, cx, height)
    }
}
