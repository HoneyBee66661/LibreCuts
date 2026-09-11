package com.tharunbirla.librecuts.services.perception

import kotlin.math.abs

/**
 * Turns perception output into something the editor already understands: a track path.
 *
 * This is the adapter between the auto-frame half (shots + motion saliency + hysteresis) and
 * the timeline. It answers "auto frame" by producing the same thing the user's drawn box
 * produces — a list of timed, normalised subject centres — so everything downstream is the code
 * that already exists: `TrackObject`, the reframe planner (including the CLAMP/DP solver) and
 * the render paths. No new render path, no second source of truth.
 *
 * It is also the safety property that matters: when the analysis does **not** find a subject,
 * the result is an empty path. Nothing is fabricated. A camera that chases a guess is worse
 * than no auto frame at all.
 *
 * Pure Kotlin by design (no Android, no bitmap), so CI runs every rule here.
 */
object AutoFramePlanner {

    /**
     * @param cutThreshold frame-to-frame difference that counts as a shot change
     * @param minHold consecutive consistent samples before a subject is adopted
     * @param minConfidence below this a held region is reported as low confidence
     * @param deadZone samples closer than this collapse into one keyframe
     * @param simplifyEpsilon Douglas-Peucker tolerance, in relative frame units
     */
    data class Config(
        val cutThreshold: Float = 0.22f,
        val minHold: Int = 3,
        val minConfidence: Float = 0.05f,
        val deadZone: Float = 0.0015f,
        val simplifyEpsilon: Float = 0.004f
    )

    /** One analysis frame, in project-timeline time. */
    data class Sample(val timeMs: Long, val frame: LumaFrame)

    /** A decided subject centre. x/y are normalised against the analysed frame. */
    data class Keyframe(val timeMs: Long, val x: Float, val y: Float, val confidence: Float)

    data class Shot(val startTimeMs: Long, val endTimeMs: Long)

    /**
     * @param path empty when nothing was confidently moving — the caller must not reframe then
     * @param shots shot ranges in project time, so the camera can reset at each cut
     * @param analysed number of samples that produced a comparison (the first sample cannot)
     * @param targetSwitches how many times the subject actually changed
     */
    data class Result(
        val path: List<Keyframe>,
        val shots: List<Shot>,
        val analysed: Int,
        val targetSwitches: Int
    ) {
        val isEmpty: Boolean get() = path.size < 2
    }

    fun plan(samples: List<Sample>, config: Config = Config()): Result {
        if (samples.size < 2) return Result(emptyList(), emptyList(), 0, 0)
        val frames = samples.map { it.frame }

        // Shots first: the selector clears its target on a cut, so it must know where they are.
        val boundaryIndices = ShotDetector.boundaryIndices(frames, config.cutThreshold)
        val shotOf = IntArray(samples.size)
        var shotIndex = 0
        for (i in samples.indices) {
            if (shotIndex + 1 < boundaryIndices.size && i >= boundaryIndices[shotIndex + 1]) shotIndex++
            shotOf[i] = shotIndex
        }

        val selector = SubjectSelector(minHold = config.minHold)
        val raw = ArrayList<Keyframe>(samples.size)
        var analysed = 0
        for (i in 1 until samples.size) {
            val blob = MotionSaliency.detect(frames[i - 1], frames[i])
            val region = selector.accept(blob, shotOf[i])
            analysed++
            if (region == null) continue
            // A held region (subject lost, or not yet adopted) keeps position but loses
            // confidence, so a consumer can tell "seen" from "assumed".
            val confidence = if (blob != null && near(region, blob.region)) {
                blob.energy.coerceIn(0f, 1f)
            } else {
                0f
            }
            raw.add(Keyframe(samples[i].timeMs, region.centerX, region.centerY, confidence))
        }

        val shots = boundaryIndices.mapIndexed { i, start ->
            val endIdx = if (i + 1 < boundaryIndices.size) boundaryIndices[i + 1] - 1 else samples.size - 1
            Shot(samples[start].timeMs, samples[endIdx].timeMs)
        }

        return Result(
            path = simplify(thin(raw, config.deadZone), config.simplifyEpsilon),
            shots = shots,
            analysed = analysed,
            targetSwitches = selector.targetSwitches
        )
    }

    private fun near(a: Region, b: Region): Boolean =
        abs(a.centerX - b.centerX) <= 0.001f && abs(a.centerY - b.centerY) <= 0.001f

    /** Drop samples that barely moved, but always keep the last one so the path ends where it ends. */
    private fun thin(points: List<Keyframe>, deadZone: Float): List<Keyframe> {
        if (points.isEmpty()) return points
        val out = ArrayList<Keyframe>(points.size)
        var lastX = Float.NaN
        var lastY = Float.NaN
        for ((i, p) in points.withIndex()) {
            val moved = lastX.isNaN() || abs(p.x - lastX) > deadZone || abs(p.y - lastY) > deadZone
            if (moved || i == points.size - 1) {
                out.add(p)
                lastX = p.x
                lastY = p.y
            }
        }
        return out
    }

    /** Douglas-Peucker over (x, y); time is intentionally ignored, as in the tracker. */
    private fun simplify(points: List<Keyframe>, epsilon: Float): List<Keyframe> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        simplifySegment(points, 0, points.size - 1, epsilon, keep)
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun simplifySegment(
        points: List<Keyframe>,
        start: Int,
        end: Int,
        epsilon: Float,
        keep: BooleanArray
    ) {
        if (end <= start + 1) return
        val first = points[start]
        val last = points[end]
        var maxDist = -1f
        var index = -1
        for (i in start + 1 until end) {
            val d = perpendicularDistance(points[i], first, last)
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

    private fun perpendicularDistance(p: Keyframe, a: Keyframe, b: Keyframe): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) {
            val px = p.x - a.x
            val py = p.y - a.y
            return kotlin.math.sqrt(px * px + py * py)
        }
        val numerator = abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x)
        return numerator / length
    }
}
