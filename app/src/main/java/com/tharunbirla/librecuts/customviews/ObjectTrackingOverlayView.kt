package com.tharunbirla.librecuts.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * Resizable marker for the object tracker.
 *
 * The user drags/resizes a single rectangle over the subject to follow; once tracking has
 * run, the recorded trajectory is drawn on top so the result can be judged before it is
 * applied. Modelled on [CropOverlayView] but kept intentionally simpler: one rectangle,
 * no aspect presets — this marks an object, it does not frame the output.
 */
class ObjectTrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC857")
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DA3FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC857")
        style = Paint.Style.FILL
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private var videoWidth = 0
    private var videoHeight = 0

    // Marker rectangle in fractions of the video rectangle.
    private var leftFrac = 0.3f
    private var topFrac = 0.3f
    private var rightFrac = 0.7f
    private var bottomFrac = 0.7f

    /** Tracked subject centres (relative coords), drawn as a trajectory. */
    private var trajectory: List<Pair<Float, Float>> = emptyList()

    private val handleRadius = 11f * resources.displayMetrics.density
    private val touchTarget = 32f * resources.displayMetrics.density
    private val minSizeFrac = 0.04f

    private enum class TouchState { NONE, LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM, CENTER }

    private var touchState = TouchState.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var initialLeft = 0.3f
    private var initialTop = 0.3f
    private var initialRight = 0.7f
    private var initialBottom = 0.7f

    var onMarkerChanged: ((x: Float, y: Float, w: Float, h: Float) -> Unit)? = null

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        invalidate()
    }

    fun setMarkerBounds(x: Float, y: Float, w: Float, h: Float) {
        val safeW = w.coerceIn(minSizeFrac, 1f)
        val safeH = h.coerceIn(minSizeFrac, 1f)
        leftFrac = x.coerceIn(0f, 1f - safeW)
        topFrac = y.coerceIn(0f, 1f - safeH)
        rightFrac = leftFrac + safeW
        bottomFrac = topFrac + safeH
        invalidate()
    }

    fun getMarkerBounds(): FloatArray =
        floatArrayOf(leftFrac, topFrac, rightFrac - leftFrac, bottomFrac - topFrac)

    /** Show the trajectory recorded by the tracker (relative 0..1 subject centres). */
    fun setTrajectory(points: List<Pair<Float, Float>>) {
        trajectory = points
        invalidate()
    }

    fun clearTrajectory() {
        trajectory = emptyList()
        invalidate()
    }

    private fun videoRect(): RectF {
        val rect = RectF()
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            return rect
        }
        val containerRatio = width.toFloat() / height
        val videoRatio = videoWidth.toFloat() / videoHeight
        if (videoRatio > containerRatio) {
            val h = width / videoRatio
            val top = (height - h) / 2f
            rect.set(0f, top, width.toFloat(), top + h)
        } else {
            val w = height * videoRatio
            val left = (width - w) / 2f
            rect.set(left, 0f, left + w, height.toFloat())
        }
        return rect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoWidth <= 0 || videoHeight <= 0) return

        val video = videoRect()
        val markerLeft = video.left + leftFrac * video.width()
        val markerTop = video.top + topFrac * video.height()
        val markerRight = video.left + rightFrac * video.width()
        val markerBottom = video.top + bottomFrac * video.height()

        // Dim everything outside the marker so the subject stands out.
        canvas.drawRect(video.left, video.top, video.right, markerTop, dimPaint)
        canvas.drawRect(video.left, markerTop, markerLeft, markerBottom, dimPaint)
        canvas.drawRect(markerRight, markerTop, video.right, markerBottom, dimPaint)
        canvas.drawRect(video.left, markerBottom, video.right, video.bottom, dimPaint)

        // Recorded trajectory (relative coords -> view coords).
        if (trajectory.size >= 2) {
            val path = Path()
            trajectory.forEachIndexed { index, point ->
                val px = video.left + point.first * video.width()
                val py = video.top + point.second * video.height()
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, pathPaint)
        }

        canvas.drawRect(markerLeft, markerTop, markerRight, markerBottom, borderPaint)

        // Centre crosshair marks the point that gets kept in the middle of the frame.
        val centerX = (markerLeft + markerRight) / 2f
        val centerY = (markerTop + markerBottom) / 2f
        val arm = 9f * resources.displayMetrics.density
        canvas.drawLine(centerX - arm, centerY, centerX + arm, centerY, crosshairPaint)
        canvas.drawLine(centerX, centerY - arm, centerX, centerY + arm, crosshairPaint)

        canvas.drawCircle(markerLeft, markerTop, handleRadius, handlePaint)
        canvas.drawCircle(markerRight, markerTop, handleRadius, handlePaint)
        canvas.drawCircle(markerLeft, markerBottom, handleRadius, handlePaint)
        canvas.drawCircle(markerRight, markerBottom, handleRadius, handlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val video = videoRect()
        val markerLeft = video.left + leftFrac * video.width()
        val markerTop = video.top + topFrac * video.height()
        val markerRight = video.left + rightFrac * video.width()
        val markerBottom = video.top + bottomFrac * video.height()

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchState = when {
                    distance(x, y, markerLeft, markerTop) < touchTarget -> TouchState.LEFT_TOP
                    distance(x, y, markerRight, markerTop) < touchTarget -> TouchState.RIGHT_TOP
                    distance(x, y, markerLeft, markerBottom) < touchTarget -> TouchState.LEFT_BOTTOM
                    distance(x, y, markerRight, markerBottom) < touchTarget -> TouchState.RIGHT_BOTTOM
                    x in markerLeft..markerRight && y in markerTop..markerBottom -> TouchState.CENTER
                    else -> TouchState.NONE
                }
                if (touchState != TouchState.NONE) {
                    dragStartX = x
                    dragStartY = y
                    initialLeft = leftFrac
                    initialTop = topFrac
                    initialRight = rightFrac
                    initialBottom = bottomFrac
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchState == TouchState.NONE) return false
                if (video.width() <= 0f || video.height() <= 0f) return false
                val dx = (x - dragStartX) / video.width()
                val dy = (y - dragStartY) / video.height()

                when (touchState) {
                    TouchState.LEFT_TOP -> {
                        leftFrac = (initialLeft + dx).coerceIn(0f, rightFrac - minSizeFrac)
                        topFrac = (initialTop + dy).coerceIn(0f, bottomFrac - minSizeFrac)
                    }
                    TouchState.RIGHT_TOP -> {
                        rightFrac = (initialRight + dx).coerceIn(leftFrac + minSizeFrac, 1f)
                        topFrac = (initialTop + dy).coerceIn(0f, bottomFrac - minSizeFrac)
                    }
                    TouchState.LEFT_BOTTOM -> {
                        leftFrac = (initialLeft + dx).coerceIn(0f, rightFrac - minSizeFrac)
                        bottomFrac = (initialBottom + dy).coerceIn(topFrac + minSizeFrac, 1f)
                    }
                    TouchState.RIGHT_BOTTOM -> {
                        rightFrac = (initialRight + dx).coerceIn(leftFrac + minSizeFrac, 1f)
                        bottomFrac = (initialBottom + dy).coerceIn(topFrac + minSizeFrac, 1f)
                    }
                    TouchState.CENTER -> {
                        val w = initialRight - initialLeft
                        val h = initialBottom - initialTop
                        leftFrac = (initialLeft + dx).coerceIn(0f, 1f - w)
                        topFrac = (initialTop + dy).coerceIn(0f, 1f - h)
                        rightFrac = leftFrac + w
                        bottomFrac = topFrac + h
                    }
                    TouchState.NONE -> {}
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchState != TouchState.NONE) {
                    touchState = TouchState.NONE
                    onMarkerChanged?.invoke(leftFrac, topFrac, rightFrac - leftFrac, bottomFrac - topFrac)
                    return true
                }
            }
        }
        return false
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
