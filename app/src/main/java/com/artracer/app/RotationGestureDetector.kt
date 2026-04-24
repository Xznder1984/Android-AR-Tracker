package com.artracer.app

import android.view.MotionEvent
import kotlin.math.atan2

/**
 * Two-finger rotation detector. Reports the delta angle (degrees) between the previous
 * and current vector formed by the two active pointers.
 *
 * Designed to coexist with [android.view.ScaleGestureDetector] — both can run on the same
 * MotionEvent stream without interfering, since neither consumes events.
 */
class RotationGestureDetector(private val listener: OnRotationGestureListener) {

    fun interface OnRotationGestureListener {
        /** Called when the rotation angle between the two active fingers changes. */
        fun onRotation(deltaDegrees: Float)
    }

    private var lastAngle: Float = 0f
    private var tracking: Boolean = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastAngle = angleBetween(event)
                    tracking = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && event.pointerCount >= 2) {
                    val current = angleBetween(event)
                    var delta = current - lastAngle
                    // Normalize wrap-around so a -179 -> +179 jump becomes +2°, not -358°.
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastAngle = current
                    if (delta != 0f) listener.onRotation(delta)
                }
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
            }
        }
        return true
    }

    private fun angleBetween(e: MotionEvent): Float {
        val dx = (e.getX(0) - e.getX(1)).toDouble()
        val dy = (e.getY(0) - e.getY(1)).toDouble()
        return Math.toDegrees(atan2(dy, dx)).toFloat()
    }
}
