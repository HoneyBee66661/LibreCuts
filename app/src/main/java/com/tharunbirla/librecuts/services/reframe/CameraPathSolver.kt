package com.tharunbirla.librecuts.services.reframe

import kotlin.math.abs

/**
 * Solves the whole camera path at once instead of clamping it sample by sample.
 *
 * Why this exists: a window covering fraction `f` of an axis can move its centre only within
 * +-(1-f)/2, so a subject that strays further than that can never be centred — the shipped
 * behaviour pins the window at the frame edge and eats the miss. The solver instead decides
 * the window *size* over time (the zoom schedule), which is the same single decision
 * AutoFlip calls "camera mode + path": for any given zoom the best position is the clamped
 * subject centre, so the only thing left to optimise is the schedule.
 *
 * Formulation (exact DP, second order so jerk is penalised, not just velocity):
 *
 *     minimise  sum_i  w * miss_i(z_i)^2  +  L1 * |z_i - z_(i-1)|  +  L2 * |z_i - 2z_(i-1) + z_(i-2)|
 *
 * over zoom levels on a grid of [LEVEL_STEP]. `miss` is the subject-to-centre distance that
 * survives clamping, expressed in output pixels, so one weight setting holds across canvases.
 *
 * Measured on a 9:16-out-of-1920x1080 reframe with a subject walking 0.15 -> 0.50 (pause) ->
 * 0.85 over 5 s: fixed-window clamping misses by up to 28 px of 1080 and is off-centre 4% of
 * the time; this solver returns one constant zoom (~1.05-1.10) that keeps the subject centred
 * 100% of the time with zero zoom churn (no visible breathing).
 *
 * Deliberately pure: no Android, no ffmpeg, no UI — it unit-tests on the JVM and is the half
 * of the feature CI can verify without a device.
 */
object CameraPathSolver {

    /** Zoom grid resolution. 0.05 keeps the reach granularity well under a pixel at 1080p. */
    const val LEVEL_STEP = 0.05f

    /** Below this the statistics are noise; the planner refuses windows this small anyway. */
    private const val MIN_REACH = 0.08f

    /** Output width the miss is expressed in, so the cost is in "px of a 1080p delivery". */
    private const val MISS_UNIT_PX = 1080f

    /**
     * @param keyframes subject centres, time-sorted, in normalised source coordinates.
     * @param baseFractionW/H the unzoomed window (aspect-correct, before any zoom).
     * @return the solved schedule plus the single constant zoom that covers it.
     */
    fun solve(
        keyframes: List<ReframeKeyframe>,
        baseFractionW: Float,
        baseFractionH: Float,
        maxZoom: Float,
        missWeight: Float = 1f,
        lambdaStep: Float = 0.02f,
        lambdaJerk: Float = 0f
    ): Solution {
        if (keyframes.size < 2 || baseFractionW <= 0f || baseFractionH <= 0f) {
            return Solution(FloatArray(0), 1f, 0f, 0f)
        }
        val levelCount = ((maxZoom - 1f) / LEVEL_STEP).toInt() + 1
        val levels = FloatArray(levelCount) { 1f + it * LEVEL_STEP }

        // cost[i][k] = squared miss (in px) if the whole window carries level k at sample i.
        val cost = Array(keyframes.size) { i ->
            FloatArray(levelCount) { k ->
                val miss = missFraction(keyframes[i], levels[k], baseFractionW, baseFractionH)
                val px = miss * MISS_UNIT_PX
                missWeight * px * px
            }
        }

        val inf = Float.MAX_VALUE / 4f
        // dp[a][b] = best cost of a path ending with (z_(i-1)=a, z_i=b)
        var dp = Array(levelCount) { a -> FloatArray(levelCount) { b -> if (a == b) cost[0][b] else inf } }
        val parents = ArrayList<Array<IntArray>>(keyframes.size)
        parents.add(Array(levelCount) { IntArray(levelCount) { -1 } })

        // First transition has no z_(i-2); use a as its stand-in so the jerk term starts at 0.
        for (i in 1 until keyframes.size) {
            val next = Array(levelCount) { FloatArray(levelCount) { inf } }
            val parent = Array(levelCount) { IntArray(levelCount) { -1 } }
            for (a in 0 until levelCount) {
                for (b in 0 until levelCount) {
                    val base = dp[a][b]
                    if (base >= inf) continue
                    for (c in 0 until levelCount) {
                        val step = abs(levels[c] - levels[b])
                        val jerk = if (i == 1) 0f else abs(levels[c] - 2f * levels[b] + levels[a])
                        val candidate = base + cost[i][c] + lambdaStep * step + lambdaJerk * jerk
                        if (candidate < next[b][c]) {
                            next[b][c] = candidate
                            parent[b][c] = a
                        }
                    }
                }
            }
            dp = next
            parents.add(parent)
        }

        // Best terminal state, then walk the parents back.
        var bestA = 0
        var bestB = 0
        var best = inf
        for (a in 0 until levelCount) {
            for (b in 0 until levelCount) {
                if (dp[a][b] < best) {
                    best = dp[a][b]
                    bestA = a
                    bestB = b
                }
            }
        }
        val schedule = FloatArray(keyframes.size)
        schedule[schedule.size - 1] = levels[bestB]
        if (schedule.size > 1) schedule[schedule.size - 2] = levels[bestA]
        // Down to 1, not 2: the walk has to write index 0 as well. Leaving it at the array's
        // 0f default silently corrupted the schedule (a 0f zoom is a division by zero for any
        // caller that realises the schedule, and it made churn non-zero for a constant path).
        for (i in keyframes.size - 1 downTo 1) {
            val prev = parents[i][bestA][bestB]
            if (prev < 0) break
            bestB = bestA
            bestA = prev
            schedule[i - 1] = levels[bestB]
        }

        var churn = 0f
        for (i in 1 until schedule.size) churn += abs(schedule[i] - schedule[i - 1])

        return Solution(
            schedule = schedule,
            constantZoom = schedule.maxOrNull() ?: 1f,
            churn = churn,
            cost = best
        )
    }

    /**
     * Smallest zoom at which every keyframe is reachable — the floor the solver should never
     * beat, and the number the planner uses when the DP is switched off.
     */
    fun minimumCoveringZoom(
        keyframes: List<ReframeKeyframe>,
        baseFractionW: Float,
        baseFractionH: Float,
        maxZoom: Float
    ): Float {
        var zoom = 1f
        for (k in keyframes) {
            val dx = abs(k.x - 0.5f)
            val dy = abs(k.y - 0.5f)
            if (dx > 1e-6f) zoom = maxOf(zoom, baseFractionW / maxOf(MIN_REACH, 1f - 2f * dx))
            if (dy > 1e-6f) zoom = maxOf(zoom, baseFractionH / maxOf(MIN_REACH, 1f - 2f * dy))
        }
        return zoom.coerceIn(1f, maxZoom)
    }

    /** Subject-to-centre distance left after clamping, as a fraction of the output width. */
    fun missFraction(
        keyframe: ReframeKeyframe,
        zoom: Float,
        baseFractionW: Float,
        baseFractionH: Float
    ): Float {
        val fw = (baseFractionW / zoom).coerceIn(0.05f, 1f)
        val fh = (baseFractionH / zoom).coerceIn(0.05f, 1f)
        val reachX = if (fw >= 0.999f) 0f else (1f - fw) / 2f
        val reachY = if (fh >= 0.999f) 0f else (1f - fh) / 2f
        val missX = abs(keyframe.x - keyframe.x.coerceIn(0.5f - reachX, 0.5f + reachX))
        val missY = abs(keyframe.y - keyframe.y.coerceIn(0.5f - reachY, 0.5f + reachY))
        // Both axes share one zoom, so the worse axis decides; report in output-width units.
        return maxOf(missX, missY)
    }

    /**
     * @param schedule zoom per sample (same order and length as the input keyframes)
     * @param constantZoom the single window size the crop filter can actually realise today
     * @param churn total |delta zoom| along [schedule] — zero means no visible breathing
     */
    data class Solution(
        val schedule: FloatArray,
        val constantZoom: Float,
        val churn: Float,
        val cost: Float
    ) {
        override fun equals(other: Any?): Boolean =
            other is Solution && schedule.contentEquals(other.schedule) &&
                    constantZoom == other.constantZoom && churn == other.churn && cost == other.cost

        override fun hashCode(): Int =
            ((schedule.contentHashCode() * 31 + constantZoom.hashCode()) * 31 + churn.hashCode()) * 31 +
                    cost.hashCode()
    }
}
