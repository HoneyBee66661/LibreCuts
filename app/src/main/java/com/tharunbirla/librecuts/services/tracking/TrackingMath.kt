package com.tharunbirla.librecuts.services.tracking

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The tracking maths, with zero Android dependencies: frame buffers go in, a motion path
 * comes out. Keeping it pure means it can be reasoned about (and unit tested) without a
 * device, and the frame-source (MediaMetadataRetriever today, anything later) stays a
 * separate concern in [TemplateMatchTracker].
 *
 * Three things make this more than plain template matching:
 *  1. Velocity prediction — the search starts where the subject is *going*, not where it
 *     was, which is what survives fast pans where a box disappears in one sample.
 *  2. Multi-scale matching — a coarse pass scores the patch at 0.85x / 1.0x / 1.2x, so a
 *     subject that walks toward or away from the camera keeps matching.
 *  3. Sub-pixel refinement — a parabolic fit on the NCC peak removes the +-1 px
 *     quantisation, which at 1080p would otherwise show as visible micro-jitter.
 */
internal object TrackingMath {

    /** Grayscale frame buffer, luminance in 0..1. */
    class Gray(val data: FloatArray, val w: Int, val h: Int)

    /**
     * Template patch with precomputed statistics.
     *
     * The constructor is internal rather than public on purpose: [values] must stay
     * consistent with [mean]/[norm], so only this file creates or rebuilds templates.
     *
     * [stride] is the spacing in frame pixels between two neighbouring samples, so a
     * stride-2 template is a cheaper, coarser view of the same patch. [scale] resamples
     * the patch content (1.2 = subject appears 20% larger than when it was marked).
     */
    class Template internal constructor(
        val values: FloatArray,
        val w: Int,
        val h: Int,
        val stride: Int,
        val scale: Float,
        val mean: Float,
        val norm: Float
    ) {
        /** Sampled span of this template in frame pixels, inclusive of the last sample. */
        val extentW: Int get() = (w - 1) * stride + 1
        val extentH: Int get() = (h - 1) * stride + 1

        companion object {
            /** Below this many samples the statistics are noise; bail out instead. */
            const val MIN_SAMPLES = 6

            /**
             * Build a template for the patch at (x, y, w, h).
             *
             * @param stride spacing in frame pixels between template samples.
             * @param scale  content scale: 1.0 = as marked, >1 = subject grew on screen.
             */
            fun from(
                g: Gray,
                x: Int,
                y: Int,
                w: Int,
                h: Int,
                stride: Int,
                scale: Float
            ): Template {
                val step = stride.coerceAtLeast(1).toFloat()
                val safeScale = if (scale <= 0.05f) 1f else scale
                val extentW = max(2f, w * safeScale)
                val extentH = max(2f, h * safeScale)
                val tw = max(2, floor(extentW / step).toInt())
                val th = max(2, floor(extentH / step).toInt())

                val values = FloatArray(tw * th)
                var sum = 0f
                var i = 0
                for (ty in 0 until th) {
                    for (tx in 0 until tw) {
                        // Sample index (tx, ty) sits at frame offset (tx * step, ty * step);
                        // because the content is scaled by `safeScale`, that offset maps back
                        // to patch coordinate (offset / safeScale).
                        val v = sampleBilinear(
                            g,
                            x + tx * step / safeScale,
                            y + ty * step / safeScale
                        )
                        values[i++] = v
                        sum += v
                    }
                }

                val mean = sum / (tw * th).toFloat()
                var acc = 0f
                for (v in values) {
                    val d = v - mean
                    acc += d * d
                }
                return Template(values, tw, th, stride, safeScale, mean, sqrt(acc).coerceAtLeast(1e-5f))
            }
        }
    }

    /** Match position (top-left of the sampled span) plus its quality. */
    data class Match(
        val x: Int,
        val y: Int,
        val score: Float,
        /** Sub-pixel correction in frame pixels, in -0.5..0.5. */
        val dx: Float = 0f,
        val dy: Float = 0f
    )

    /** A reference template paired with the content scale it was sampled at. */
    data class ScaledTemplate(val scale: Float, val template: Template)

    /** Scales tried in the coarse pass. Wide enough to absorb a walking subject. */
    val DEFAULT_SCALES = floatArrayOf(0.85f, 1f, 1.2f)

    /**
     * Build the coarse (cheap) reference templates for every scale.
     *
     * These are sampled from the *reference* frame — the last frame where the subject was
     * matched confidently — not from the frame being searched: a template built out of the
     * live frame would match itself perfectly and say nothing about where the subject moved.
     */
    fun coarseTemplates(
        refFrame: Gray,
        refX: Int,
        refY: Int,
        patchW: Int,
        patchH: Int,
        scales: FloatArray = DEFAULT_SCALES,
        stride: Int = COARSE_STRIDE
    ): List<ScaledTemplate> {
        val out = ArrayList<ScaledTemplate>(scales.size)
        for (s in scales) {
            val t = Template.from(refFrame, refX, refY, patchW, patchH, stride, s)
            if (t.values.size >= Template.MIN_SAMPLES) out.add(ScaledTemplate(s, t))
        }
        return out
    }

    /**
     * Locate the subject in [frame] by matching [coarse] first and [fine] afterwards.
     *
     * @param centerX predicted search centre (frame pixels) — see the velocity note in the
     *                class docs; falling back to the last known position is the caller's job.
     * @return the best match; a negative score means nothing usable was found.
     */
    fun search(
        frame: Gray,
        coarse: List<ScaledTemplate>,
        fine: Template,
        centerX: Int,
        centerY: Int,
        radius: Int
    ): Match {
        var best = Match(centerX, centerY, -2f)
        var bestScale = 1f

        // ── Coarse pass: cheap stride-2 match at every scale ──────────────────────
        for (entry in coarse) {
            val m = searchWith(frame, entry.template, centerX, centerY, radius)
            if (m.score > best.score) {
                best = m
                bestScale = entry.scale
            }
        }
        if (best.score < -1f) return Match(centerX, centerY, -1f)

        // A scaled template spans patchW * scale, so its top-left is not the patch's
        // top-left: convert through the centre, then back to the un-scaled patch origin.
        val coarseCenterX = best.x + fine.extentW * bestScale / 2f
        val coarseCenterY = best.y + fine.extentH * bestScale / 2f
        val originX = (coarseCenterX - fine.extentW / 2f).roundToInt()
        val originY = (coarseCenterY - fine.extentH / 2f).roundToInt()

        // ── Fine pass: full-resolution score, then its immediate neighbourhood ────
        var bestX = originX
        var bestY = originY
        var bestScore = scoreAt(frame, fine, originX, originY)
        for (dy in -REFINE_RADIUS..REFINE_RADIUS) {
            for (dx in -REFINE_RADIUS..REFINE_RADIUS) {
                if (dx == 0 && dy == 0) continue
                val x = originX + dx
                val y = originY + dy
                val s = scoreAt(frame, fine, x, y)
                if (s > bestScore) {
                    bestScore = s
                    bestX = x
                    bestY = y
                }
            }
        }
        if (bestScore < -1f) return Match(centerX, centerY, -1f)

        // ── Sub-pixel: parabolic fit through the peak and its two neighbours ──────
        val px = subPixel(scoreAt(frame, fine, bestX - 1, bestY), bestScore, scoreAt(frame, fine, bestX + 1, bestY))
        val py = subPixel(scoreAt(frame, fine, bestX, bestY - 1), bestScore, scoreAt(frame, fine, bestX, bestY + 1))
        return Match(bestX, bestY, bestScore, px, py)
    }

    /**
     * Parabolic sub-pixel peak: fits a parabola through (score at -1, peak, +1) and
     * returns the offset of its vertex, clamped to +-0.5 px. A flat or malformed
     * neighbourhood yields 0, i.e. the integer peak.
     */
    private fun subPixel(left: Float, center: Float, right: Float): Float {
        val denom = left - 2f * center + right
        if (denom >= -1e-6f) return 0f
        return (0.5f * (left - right) / denom).coerceIn(-0.5f, 0.5f)
    }

    /** Exhaustive NCC search for [template] around ([centerX], [centerY]). */
    private fun searchWith(
        frame: Gray,
        template: Template,
        centerX: Int,
        centerY: Int,
        radius: Int
    ): Match {
        var best = Match(centerX, centerY, -2f)
        val x0 = max(0, centerX - radius)
        val x1 = min(frame.w - template.extentW, centerX + radius)
        val y0 = max(0, centerY - radius)
        val y1 = min(frame.h - template.extentH, centerY + radius)
        if (x1 < x0 || y1 < y0) return best

        for (y in y0..y1) {
            for (x in x0..x1) {
                val score = scoreAt(frame, template, x, y)
                if (score > best.score) best = Match(x, y, score)
            }
        }
        return best
    }

    /**
     * Normalized cross-correlation of [template] against the patch at (x, y).
     *
     * Both the dot product and the candidate statistics are computed over the *sampled*
     * pixels only, so a strided or scaled template stays statistically consistent. Returns
     * -1f for out-of-bounds or flat (texture-less) patches — a blank wall carries no
     * position information and must not win a match.
     */
    fun scoreAt(frame: Gray, template: Template, x: Int, y: Int): Float {
        if (x < 0 || y < 0) return -1f
        if (x + template.extentW > frame.w || y + template.extentH > frame.h) return -1f

        var dot = 0f
        var sumC = 0f
        var sumC2 = 0f
        var count = 0
        var ty = 0
        while (ty < template.h) {
            val srcRow = (y + ty * template.stride) * frame.w + x
            val tplRow = ty * template.w
            var tx = 0
            while (tx < template.w) {
                val c = frame.data[srcRow + tx * template.stride]
                dot += template.values[tplRow + tx] * c
                sumC += c
                sumC2 += c * c
                count++
                tx++
            }
            ty++
        }
        if (count == 0) return -1f

        val n = count.toFloat()
        val meanC = sumC / n
        val varC = sumC2 - meanC * sumC
        if (varC <= 1e-6f) return -1f

        val numerator = dot - template.mean * sumC
        val denominator = sqrt(varC) * template.norm.coerceAtLeast(1e-5f)
        val score = numerator / denominator
        return if (score.isNaN()) -1f else score
    }

    /** Blend two same-shaped templates (gradual appearance adaptation). */
    fun blend(old: Template, fresh: Template, weight: Float): Template {
        if (old.values.size != fresh.values.size || old.stride != fresh.stride) return fresh
        val w = weight.coerceIn(0f, 1f)
        val out = FloatArray(old.values.size)
        for (i in out.indices) {
            out[i] = old.values[i] * (1f - w) + fresh.values[i] * w
        }
        val mean = old.mean * (1f - w) + fresh.mean * w
        var acc = 0f
        for (v in out) {
            val d = v - mean
            acc += d * d
        }
        return Template(out, old.w, old.h, old.stride, old.scale, mean, sqrt(acc).coerceAtLeast(1e-5f))
    }

    /**
     * Replace stretches where the subject was lost with a straight bridge between the
     * last and next confident samples.
     *
     * Holding the last position (the obvious alternative) freezes the box and then jumps
     * when tracking resumes; a linear bridge keeps the frame moving plausibly through a
     * passing occlusion. Leading and trailing losses clamp to the nearest confident
     * sample, since there is nothing to bridge to.
     */
    fun bridgeGaps(
        centers: List<Pair<Float, Float>>,
        confident: List<Boolean>
    ): List<Pair<Float, Float>> {
        if (centers.isEmpty()) return centers
        val out = MutableList(centers.size) { centers[it] }
        var i = 0
        while (i < centers.size) {
            if (confident[i]) {
                i++
                continue
            }
            var j = i
            while (j < centers.size && !confident[j]) j++
            val before = i - 1
            val after = if (j < centers.size) j else -1
            when {
                before >= 0 && after >= 0 -> {
                    val start = centers[before]
                    val end = centers[after]
                    val span = (after - before).toFloat()
                    for (k in i until j) {
                        val t = (k - before) / span
                        out[k] = Pair(
                            start.first + (end.first - start.first) * t,
                            start.second + (end.second - start.second) * t
                        )
                    }
                }
                before >= 0 -> for (k in i until j) out[k] = centers[before]
                after >= 0 -> for (k in i until j) out[k] = centers[after]
                else -> for (k in i until j) out[k] = centers[k]
            }
            i = j
        }
        return out
    }

    /**
     * Centred moving-average smoothing.
     *
     * Tracking runs offline over the whole clip, so a symmetric window is available —
     * that matters because an EMA trails the subject during sustained motion (measured
     * ~5px at 320px width, i.e. ~17px at 1080p, exactly when the subject starts moving).
     * A centred window smooths the same jitter with no lag on constant-velocity motion.
     */
    fun smooth(points: List<Pair<Float, Float>>, halfWindow: Int = 2): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        if (halfWindow <= 0) return points
        val last = points.size - 1
        return points.indices.map { i ->
            var sumX = 0f
            var sumY = 0f
            var n = 0
            for (j in (i - halfWindow)..(i + halfWindow)) {
                val k = j.coerceIn(0, last)
                sumX += points[k].first
                sumY += points[k].second
                n++
            }
            Pair(sumX / n, sumY / n)
        }
    }

    /**
     * Turn the dense per-sample path into sparse keyframes: samples that barely move
     * collapse into one point, then Douglas-Peucker trims the rest so the generated
     * ffmpeg expression stays small.
     */
    fun buildTrackPoints(
        times: List<Long>,
        centers: List<Pair<Float, Float>>,
        scores: List<Float>,
        deadZone: Float,
        epsilon: Float
    ): List<TrackPoint> {
        if (times.isEmpty() || centers.isEmpty()) return emptyList()
        val n = min(times.size, centers.size)

        val deduped = ArrayList<Triple<Long, Pair<Float, Float>, Float>>(n)
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (i in 0 until n) {
            val c = centers[i]
            val moved = lastX.isNaN() ||
                    abs(c.first - lastX) > deadZone ||
                    abs(c.second - lastY) > deadZone
            if (moved || i == n - 1) {
                val score = if (i < scores.size) scores[i] else 0f
                deduped.add(Triple(times[i], c, score))
                lastX = c.first
                lastY = c.second
            }
        }

        val simplified = douglasPeucker(deduped, epsilon)
        return simplified.map { entry ->
            TrackPoint(
                timeMs = entry.first,
                x = entry.second.first,
                y = entry.second.second,
                confidence = entry.third
            )
        }
    }

    private fun douglasPeucker(
        points: List<Triple<Long, Pair<Float, Float>, Float>>,
        epsilon: Float
    ): List<Triple<Long, Pair<Float, Float>, Float>> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        simplifySegment(points, 0, points.size - 1, epsilon, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun simplifySegment(
        points: List<Triple<Long, Pair<Float, Float>, Float>>,
        start: Int,
        end: Int,
        epsilon: Float,
        keep: BooleanArray
    ) {
        if (end <= start + 1) return
        val first = points[start].second
        val last = points[end].second
        var maxDist = -1f
        var index = -1
        for (i in start + 1 until end) {
            val d = perpendicularDistance(points[i].second, first, last)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        if (maxDist > epsilon && index > 0) {
            keep[index] = true
            simplifySegment(points, start, index, epsilon, keep)
            simplifySegment(points, index, end, epsilon, keep)
        }
    }

    /** Perpendicular distance in relative units (time is deliberately ignored). */
    private fun perpendicularDistance(
        point: Pair<Float, Float>,
        lineStart: Pair<Float, Float>,
        lineEnd: Pair<Float, Float>
    ): Float {
        val dx = lineEnd.first - lineStart.first
        val dy = lineEnd.second - lineStart.second
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) {
            val px = point.first - lineStart.first
            val py = point.second - lineStart.second
            return sqrt(px * px + py * py)
        }
        val numerator = abs(
            dy * point.first - dx * point.second + lineEnd.first * lineStart.second -
                    lineEnd.second * lineStart.first
        )
        return numerator / length
    }

    /** Bilinear luminance sample; clamps at the frame border. */
    private fun sampleBilinear(g: Gray, fx: Float, fy: Float): Float {
        val x0 = floor(fx).toInt().coerceIn(0, g.w - 1)
        val y0 = floor(fy).toInt().coerceIn(0, g.h - 1)
        val x1 = min(x0 + 1, g.w - 1)
        val y1 = min(y0 + 1, g.h - 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val a = g.data[y0 * g.w + x0]
        val b = g.data[y0 * g.w + x1]
        val c = g.data[y1 * g.w + x0]
        val d = g.data[y1 * g.w + x1]
        val top = a + (b - a) * tx
        val bottom = c + (d - c) * tx
        return top + (bottom - top) * ty
    }

    /** Stride used for the cheap first pass. */
    private const val COARSE_STRIDE = 2

    /** +-px neighbourhood rescored at full resolution after the coarse pass. */
    private const val REFINE_RADIUS = 3
}
