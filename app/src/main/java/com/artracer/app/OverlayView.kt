package com.artracer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Image overlay surface. Owns the reference image and its current transform
 * (translation, scale, rotation, horizontal flip, opacity). Applies user gestures —
 * one-finger drag, pinch-to-zoom, two-finger rotate — when not locked.
 *
 * The transform is intentionally maintained as discrete fields rather than a single
 * Matrix because we re-derive the matrix on every draw (cheap) and need to read the
 * fields back independently for things like "reset", "flip", and persistence.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    fun interface OnTransformChangedListener {
        fun onTransformChanged()
    }

    private var bitmap: Bitmap? = null

    // Transform state (image-center based for stable rotation/scale).
    private var translationXPx: Float = 0f
    private var translationYPx: Float = 0f
    private var scaleFactor: Float = 1f
    private var rotationDegrees: Float = 0f
    private var flippedHorizontally: Boolean = false
    private var opacity: Float = 0.6f // 0f..1f

    private var locked: Boolean = false

    private val drawMatrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
    }

    private var listener: OnTransformChangedListener? = null

    fun setOnTransformChangedListener(l: OnTransformChangedListener?) {
        listener = l
    }

    // ---- Public state API ------------------------------------------------------------------

    fun setBitmap(newBitmap: Bitmap?, resetTransform: Boolean = true) {
        bitmap = newBitmap
        if (resetTransform) resetTransformInternal()
        invalidate()
        listener?.onTransformChanged()
    }

    fun hasBitmap(): Boolean = bitmap != null

    fun setOpacity(value: Float) {
        opacity = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setLocked(value: Boolean) {
        locked = value
    }

    fun isLocked(): Boolean = locked

    fun flipHorizontally() {
        flippedHorizontally = !flippedHorizontally
        invalidate()
        listener?.onTransformChanged()
    }

    fun resetTransform() {
        resetTransformInternal()
        invalidate()
        listener?.onTransformChanged()
    }

    private fun resetTransformInternal() {
        translationXPx = 0f
        translationYPx = 0f
        scaleFactor = computeFitScale()
        rotationDegrees = 0f
        flippedHorizontally = false
    }

    /** Choose an initial scale that fits the image inside ~80% of the smaller view edge. */
    private fun computeFitScale(): Float {
        val bm = bitmap ?: return 1f
        val vw = width.takeIf { it > 0 } ?: return 1f
        val vh = height.takeIf { it > 0 } ?: return 1f
        val target = 0.8f * min(vw, vh)
        val longest = max(bm.width, bm.height).toFloat()
        return if (longest <= 0f) 1f else target / longest
    }

    // ---- Gesture handling ------------------------------------------------------------------

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (locked || bitmap == null) return false
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0f) {
                    scaleFactor = (scaleFactor * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                    invalidate()
                    listener?.onTransformChanged()
                }
                return true
            }
        }
    ).apply {
        // Prevents pinch from being interpreted as a quick-scale (single-finger double-tap drag).
        isQuickScaleEnabled = false
    }

    private val rotationDetector = RotationGestureDetector { delta ->
        if (locked || bitmap == null) return@RotationGestureDetector
        rotationDegrees = (rotationDegrees + delta) % 360f
        invalidate()
        listener?.onTransformChanged()
    }

    private val dragDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Only single-finger drags translate the image; multitouch scroll is handled
                // by the scale + rotation detectors instead.
                if (locked || bitmap == null) return false
                if (e2.pointerCount > 1) return false
                translationXPx -= distanceX
                translationYPx -= distanceY
                invalidate()
                listener?.onTransformChanged()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked || bitmap == null) return false
                resetTransform()
                return true
            }
        }
    )

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        if (locked) return false

        // Order matters: scale + rotation first so they always see multitouch events,
        // then the drag detector for the single-finger path.
        scaleDetector.onTouchEvent(event)
        rotationDetector.onTouchEvent(event)
        dragDetector.onTouchEvent(event)
        return true
    }

    // ---- Drawing ---------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // First valid size: re-fit the bitmap so it lands centered.
        if (bitmap != null && oldw == 0 && oldh == 0) {
            scaleFactor = computeFitScale()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = bitmap ?: return

        val cx = width / 2f + translationXPx
        val cy = height / 2f + translationYPx
        val signX = if (flippedHorizontally) -scaleFactor else scaleFactor

        drawMatrix.reset()
        // Move bitmap-center to origin first, then scale, rotate, then translate to target.
        drawMatrix.postTranslate(-bm.width / 2f, -bm.height / 2f)
        drawMatrix.postScale(signX, scaleFactor)
        drawMatrix.postRotate(rotationDegrees)
        drawMatrix.postTranslate(cx, cy)

        paint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bm, drawMatrix, paint)
    }

    /**
     * Draws the overlay on an arbitrary canvas (used by screenshot capture so the saved
     * frame matches what the user sees on-screen).
     */
    fun drawOnto(canvas: Canvas, targetWidth: Int, targetHeight: Int) {
        val bm = bitmap ?: return
        val sx = targetWidth.toFloat() / width.toFloat()
        val sy = targetHeight.toFloat() / height.toFloat()

        val cx = (width / 2f + translationXPx) * sx
        val cy = (height / 2f + translationYPx) * sy
        val signX = if (flippedHorizontally) -scaleFactor else scaleFactor

        val m = Matrix()
        m.postTranslate(-bm.width / 2f, -bm.height / 2f)
        m.postScale(signX * sx, scaleFactor * sy)
        m.postRotate(rotationDegrees)
        m.postTranslate(cx, cy)

        val p = Paint(paint)
        p.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bm, m, p)
    }

    companion object {
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 12f
    }
}
