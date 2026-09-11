package com.tharunbirla.librecuts.services.reframe

import com.tharunbirla.librecuts.models.ReframeAspect
import com.tharunbirla.librecuts.models.ReframeMode
import com.tharunbirla.librecuts.models.ReframeSpec
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns "where the subject is" into "how the frame must move".
 *
 * Pure arithmetic on fractions — no Android, no ffmpeg, no UI — so the framing rules are
 * testable in isolation and the tracker that produced the path stays replaceable.
 *
 * The central idea: a ratio-correct window is laid over the source, and the window centre
 * follows the subject. How large that window is *is* the framing decision:
 *
 *  - Landscape source, 9:16 target ([ReframeMode.PAN_ONLY]) — the window is the tallest
 *    9:16 strip that fits, i.e. full height and ~32% width. Height is therefore locked by
 *    construction and the frame can only slide sideways, which is exactly a TikTok
 *    reframe. No zoom is ever needed, so nothing gets softer.
 *  - Same-ratio target ([ReframeMode.ZOOM_PAN]) — the window starts as the whole frame and
 *    shrinks by the zoom factor. Zoom 1.0 means "cannot move at all", which is why a
 *    moving subject on a 16:9 canvas needs a zoom > 1 to have anywhere to travel.
 */
object ReframePlanner {

    /** A window narrower/shorter than this would be all artefacts; refuse to go further. */
    private const val MIN_WINDOW_FRACTION = 0.05f

    /** Tolerance below which a fraction counts as "fills the frame". */
    private const val FULL_FRAME = 0.999f

    /**
     * @param sourceWidth/sourceHeight dimensions of the frame the filter will actually see
     *        (i.e. after any earlier crop stage, not necessarily the raw file).
     * @param keyframes subject centres in source-relative coordinates; times are shifted by
     *        [timeOffsetMs] so a per-clip proxy render can subtract its own start offset.
     */
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        spec: ReframeSpec,
        keyframes: List<ReframeKeyframe>,
        timeOffsetMs: Long = 0L
    ): ReframePlan {
        val srcW = sourceWidth.coerceAtLeast(2)
        val srcH = sourceHeight.coerceAtLeast(2)
        val canvas = outputCanvas(spec, srcW, srcH)

        if (keyframes.size < 2) {
            return noOp(srcW, srcH, spec, canvas)
        }

        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val targetAspect = if (spec.aspect.isOriginal) srcAspect else spec.aspect.ratio

        // Largest ratio-correct window that sits inside the frame untouched.
        val baseFractionW: Float
        val baseFractionH: Float
        if (srcAspect >= targetAspect) {
            // Source is wider than the target: height is the limited dimension, so the
            // window keeps full height and is a narrow vertical strip.
            baseFractionH = 1f
            baseFractionW = targetAspect / srcAspect
        } else {
            // Source is taller: full width is kept and the window is a horizontal band.
            baseFractionW = 1f
            baseFractionH = srcAspect / targetAspect
        }

        val zoom = when {
            // Sliding a locked-height window needs no zoom: the "no zoom" rule is what
            // keeps the TikTok path sharp.
            spec.mode == ReframeMode.PAN_ONLY -> 1f
            spec.isAutoZoom -> autoZoom(keyframes, baseFractionW, baseFractionH)
            else -> spec.zoom.coerceIn(1f, ReframeSpec.MAX_ZOOM)
        }

        val windowFractionW = (baseFractionW / zoom).coerceIn(MIN_WINDOW_FRACTION, 1f)
        val windowFractionH = (baseFractionH / zoom).coerceIn(MIN_WINDOW_FRACTION, 1f)

        val xRange = centreRange(windowFractionW)
        val yRange = centreRange(windowFractionH)

        val mapped = keyframes
            .sortedBy { it.timeMs }
            .map { k ->
                ReframeKeyframe(
                    timeMs = k.timeMs + timeOffsetMs,
                    x = k.x.coerceIn(xRange.first, xRange.second),
                    y = k.y.coerceIn(yRange.first, yRange.second)
                )
            }

        return ReframePlan(
            windowWidthFraction = windowFractionW,
            windowHeightFraction = windowFractionH,
            keyframes = mapped,
            zoom = zoom,
            outWidth = canvas.first,
            outHeight = canvas.second,
            sourceWidth = srcW,
            sourceHeight = srcH,
            mode = if (spec.mode == ReframeMode.PAN_ONLY) ReframeMode.PAN_ONLY else ReframeMode.ZOOM_PAN,
            aspect = spec.aspect
        )
    }

    /**
     * Smallest zoom at which every recorded subject position is reachable.
     *
     * A window covering fraction `f` of an axis can travel that axis by +-(1-f)/2, so a
     * subject straying `d` from the centre needs f / (1 - 2d) <= 1 — the same bound the
     * window-size derivation implies. Both axes are checked and the larger demand wins
     * because a single scalar zoom shrinks both.
     */
    fun autoZoom(
        keyframes: List<ReframeKeyframe>,
        baseFractionW: Float,
        baseFractionH: Float
    ): Float {
        var zoom = 1f
        for (k in keyframes) {
            val dx = abs(k.x - 0.5f)
            val dy = abs(k.y - 0.5f)
            zoom = max(zoom, baseFractionW / max(0.08f, 1f - 2f * dx))
            zoom = max(zoom, baseFractionH / max(0.08f, 1f - 2f * dy))
        }
        return zoom.coerceIn(1f, ReframeSpec.MAX_ZOOM)
    }

    /**
     * Delivery canvas. For a preset aspect this is the platform resolution; for
     * [ReframeAspect.ORIGINAL] it mirrors the source, so an untouched frame keeps its size.
     */
    private fun outputCanvas(spec: ReframeSpec, srcW: Int, srcH: Int): Pair<Int, Int> {
        if (spec.aspect.isOriginal) {
            return Pair(even(srcW), even(srcH))
        }
        return Pair(even(spec.aspect.outputWidth), even(spec.aspect.outputHeight))
    }

    /** Reachable range for the window centre on one axis (0.5/0.5 = locked axis). */
    private fun centreRange(fraction: Float): Pair<Float, Float> {
        if (fraction >= FULL_FRAME) return Pair(0.5f, 0.5f)
        return Pair(fraction / 2f, 1f - fraction / 2f)
    }

    /** Even values keep 4:2:0 chroma sampling happy. */
    private fun even(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)

    private fun noOp(
        srcW: Int,
        srcH: Int,
        spec: ReframeSpec,
        canvas: Pair<Int, Int>
    ): ReframePlan = ReframePlan(
        windowWidthFraction = 1f,
        windowHeightFraction = 1f,
        keyframes = emptyList(),
        zoom = 1f,
        outWidth = canvas.first,
        outHeight = canvas.second,
        sourceWidth = srcW,
        sourceHeight = srcH,
        mode = spec.mode,
        aspect = spec.aspect
    )
}
