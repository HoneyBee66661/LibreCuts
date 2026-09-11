package com.tharunbirla.librecuts.services.perception

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Auto frame" perception, the half that needs no model.
 *
 * The user draws a box today; this is the machinery for *not* needing one. CapCut-class auto
 * framing is five stages — shot detection, salient content, tracking, a solved camera path and
 * the render. The render and the path solver already exist in this codebase
 * (`services/reframe`), so what is missing is the first two: where are the shots, and what is
 * moving.
 *
 * Motion saliency is the cheap tier: no detector, no model, no APK growth. It is honestly
 * limited (the subject must move, and two moving subjects need the priority layer), which is
 * exactly the frame the user shoots: a static camera and a moving subject.
 *
 * The one thing that must be right is **global motion compensation**. Without it a handheld
 * pan makes the whole frame "the subject" and the reframe chases the camera instead of the
 * subject — the #1 way a motion-based auto frame looks broken.
 *
 * Deliberately pure Kotlin: no Android, no ffmpeg, no bitmap, so `testDebugUnitTest` runs all
 * of this on the CI runner.
 */

/** Downscaled luma frame, values in 0..1. Row-major, `data[y * w + x]`. */
class LumaFrame(val data: FloatArray, val w: Int, val h: Int) {
    init {
        require(data.size == w * h) { "luma length ${data.size} does not match ${w}x$h" }
    }

    fun at(x: Int, y: Int): Float = data[y * w + x]
}

/** Normalised box, 0..1, relative to the frame the analysis ran on. */
data class Region(val left: Float, val top: Float, val width: Float, val height: Float) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/**
 * Estimates how much the *whole frame* moved between two samples.
 *
 * Uses a trimmed SAD: the worst [TRIM_FRACTION] of the compared cells are dropped before
 * summing, so a large moving subject does not drag the estimate toward itself (a plain SAD
 * latches onto whatever changed most, which is the subject, not the camera).
 */
object GlobalMotion {

    private const val TRIM_FRACTION = 0.25f

    data class Shift(val dx: Int, val dy: Int, val residual: Float)

    fun estimate(prev: LumaFrame, next: LumaFrame, maxShift: Int = 8): Shift {
        var best = Shift(0, 0, Float.MAX_VALUE)
        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                val residual = trimmedSad(prev, next, dx, dy)
                if (residual < best.residual) best = Shift(dx, dy, residual)
            }
        }
        return best
    }

    private fun trimmedSad(prev: LumaFrame, next: LumaFrame, dx: Int, dy: Int): Float {
        val x0 = max(0, -dx)
        val x1 = min(prev.w, next.w - dx)
        val y0 = max(0, -dy)
        val y1 = min(prev.h, next.h - dy)
        if (x1 - x0 < 3 || y1 - y0 < 3) return Float.MAX_VALUE

        val diffs = ArrayList<Float>((x1 - x0) * (y1 - y0))
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                diffs.add(abs(next.at(x + dx, y + dy) - prev.at(x, y)))
            }
        }
        if (diffs.isEmpty()) return Float.MAX_VALUE
        diffs.sort()
        val keep = max(1, (diffs.size * (1f - TRIM_FRACTION)).toInt())
        var sum = 0f
        for (i in 0 until keep) sum += diffs[i]
        return sum / keep
    }
}

/**
 * Finds the moving subject: what changed *after* the camera's own motion is removed.
 *
 * Returns the largest connected region of change above [threshold], or null when nothing
 * moves in a way the compensation cannot explain. A border band is excluded so the frame
 * edges exposed by a pan do not masquerade as a subject.
 */
object MotionSaliency {

    data class Blob(val region: Region, val energy: Float, val areaFraction: Float)

    fun detect(
        prev: LumaFrame,
        next: LumaFrame,
        maxShift: Int = 6,
        threshold: Float = 0.12f,
        minAreaFraction: Float = 0.0015f,
        borderBand: Int = 2,
        compensate: Boolean = true
    ): Blob? {
        val shift = if (compensate) GlobalMotion.estimate(prev, next, maxShift) else GlobalMotion.Shift(0, 0, 0f)
        val w = prev.w
        val h = prev.h
        val mask = BooleanArray(w * h)
        var lit = 0
        for (y in borderBand until h - borderBand) {
            for (x in borderBand until w - borderBand) {
                val sx = x + shift.dx
                val sy = y + shift.dy
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                if (abs(next.at(sx, sy) - prev.at(x, y)) >= threshold) {
                    mask[y * w + x] = true
                    lit++
                }
            }
        }
        if (lit == 0) return null

        val minCells = max(2, (w * h * minAreaFraction).toInt())
        val best = largestComponent(mask, w, h, minCells) ?: return null
        val (minX, minY, maxX, maxY) = best.bounds
        val boxW = (maxX - minX + 1)
        val boxH = (maxY - minY + 1)
        val areaFraction = boxW.toFloat() * boxH / (w * h).toFloat()
        return Blob(
            region = Region(
                left = minX.toFloat() / w,
                top = minY.toFloat() / h,
                width = boxW.toFloat() / w,
                height = boxH.toFloat() / h
            ),
            energy = best.energy,
            areaFraction = areaFraction
        )
    }

    private class Component(val bounds: IntArray, val energy: Float)

    /** Iterative flood fill (no recursion: a 1080p mask would blow the stack). */
    private fun largestComponent(mask: BooleanArray, w: Int, h: Int, minCells: Int): Component? {
        val seen = BooleanArray(mask.size)
        var best: Component? = null
        val stack = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            stack.clear()
            stack.addLast(start)
            seen[start] = true
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var cells = 0
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val x = idx % w
                val y = idx / w
                cells++
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                if (x > 0) push(stack, seen, mask, idx - 1)
                if (x < w - 1) push(stack, seen, mask, idx + 1)
                if (y > 0) push(stack, seen, mask, idx - w)
                if (y < h - 1) push(stack, seen, mask, idx + w)
            }
            if (cells < minCells) continue
            val energy = cells.toFloat() / max(1, (maxX - minX + 1) * (maxY - minY + 1)).toFloat()
            if (best == null || cells > best!!.bounds[4]) {
                best = Component(intArrayOf(minX, minY, maxX, maxY, cells), energy)
            }
        }
        return best
    }

    private fun push(stack: ArrayDeque<Int>, seen: BooleanArray, mask: BooleanArray, idx: Int) {
        if (!seen[idx] && mask[idx]) {
            seen[idx] = true
            stack.addLast(idx)
        }
    }
}

/** Shot boundaries from frame-to-frame difference: a hard cut changes everything at once. */
object ShotDetector {

    data class Shot(val startIndex: Int, val endIndex: Int)

    /**
     * @return indices where a new shot starts (always contains 0).
     */
    fun boundaryIndices(frames: List<LumaFrame>, threshold: Float = 0.22f): List<Int> {
        if (frames.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        out.add(0)
        for (i in 1 until frames.size) {
            if (meanAbsDiff(frames[i - 1], frames[i]) >= threshold) out.add(i)
        }
        return out
    }

    fun shots(frames: List<LumaFrame>, threshold: Float = 0.22f): List<Shot> {
        val bounds = boundaryIndices(frames, threshold)
        if (bounds.isEmpty()) return emptyList()
        return bounds.mapIndexed { i, start ->
            Shot(start, if (i + 1 < bounds.size) bounds[i + 1] - 1 else frames.size - 1)
        }
    }

    fun meanAbsDiff(a: LumaFrame, b: LumaFrame): Float {
        if (a.w != b.w || a.h != b.h) return 1f
        var sum = 0f
        for (i in a.data.indices) sum += abs(a.data[i] - b.data[i])
        return sum / a.data.size
    }
}

/**
 * Decides which blob is *the* subject, with hysteresis.
 *
 * A single noisy sample must never move the frame: a candidate has to persist for [minHold]
 * consecutive samples near the same place before it is adopted, and a lost subject keeps the
 * last confirmed region (the camera path then holds instead of snapping away). A shot change
 * clears everything, because the subject of the new shot is unrelated to the old one.
 */
class SubjectSelector(private val minHold: Int = 3, private val switchTolerance: Float = 0.25f) {

    private var shotIndex = -1
    private var pending: Region? = null
    private var pendingCount = 0
    private var confirmed: Region? = null
    private var switches = 0

    /** Number of times the target actually changed — the "is it twitchy" metric. */
    val targetSwitches: Int get() = switches

    fun accept(blob: MotionSaliency.Blob?, shot: Int): Region? {
        if (shot != shotIndex) {
            shotIndex = shot
            pending = null
            pendingCount = 0
            confirmed = null
        }
        val region = blob?.region
        if (region == null) {
            // Nothing moving: hold the previous target rather than jumping.
            pending = null
            pendingCount = 0
            return confirmed
        }
        val candidate = pending
        if (candidate != null && near(candidate, region)) {
            pendingCount++
            pending = region
        } else {
            pending = region
            pendingCount = 1
        }
        if (pendingCount >= minHold) {
            if (confirmed == null || !near(confirmed!!, region)) switches++
            confirmed = region
        }
        return confirmed
    }

    private fun near(a: Region, b: Region): Boolean =
        abs(a.centerX - b.centerX) <= switchTolerance && abs(a.centerY - b.centerY) <= switchTolerance
}
