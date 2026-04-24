package com.artracer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight rule-of-thirds + 8x8 grid overlay. Pure Canvas; no allocations on draw.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(0x55, 0xFF, 0xFF, 0xFF)
        strokeWidth = dp(0.6f)
    }
    private val thickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(0xAA, 0xFF, 0xFF, 0xFF)
        strokeWidth = dp(1.1f)
    }

    init {
        // We never receive touch input; everything passes through to the OverlayView underneath.
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Fine 8x8 grid.
        val cols = 8
        val rows = 8
        for (i in 1 until cols) {
            val x = w * i / cols
            canvas.drawLine(x, 0f, x, h, thinPaint)
        }
        for (i in 1 until rows) {
            val y = h * i / rows
            canvas.drawLine(0f, y, w, y, thinPaint)
        }
        // Rule of thirds.
        val tx1 = w / 3f; val tx2 = 2f * w / 3f
        val ty1 = h / 3f; val ty2 = 2f * h / 3f
        canvas.drawLine(tx1, 0f, tx1, h, thickPaint)
        canvas.drawLine(tx2, 0f, tx2, h, thickPaint)
        canvas.drawLine(0f, ty1, w, ty1, thickPaint)
        canvas.drawLine(0f, ty2, w, ty2, thickPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
