package com.tharunbirla.librecuts.services.perception

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Which face to follow, frame by frame — the decision layer of the ML Kit engine.
 *
 * A detector answers "where are the faces in this frame"; it does **not** answer "which one am I
 * following". That question is where the frame-to-frame stability comes from, so it lives here,
 * in pure Kotlin, with the policy written down and unit-tested:
 *
 *  1. **identity beats distance** — once a face is locked by its ML Kit `trackingId`, that id
 *     wins wherever it moves. Nearest-wins alone swaps on a shoulder brush.
 *  2. **a confirmation ladder** — a *different* face must appear `minHold` samples in a row
 *     before it is adopted. A single frame where a bystander's face is nearer must never move
 *     the camera.
 *  3. **no teleporting** — a face that appears further than `jumpTolerance` from where the
 *     subject was is treated as a different subject, however confident it is.
 *  4. **re-acquisition is allowed, late** — after `reacquireAfter` missed samples the search
 *     reopens from the user's box, so a subject who walks out and back is found again instead of
 *     being pinned to a stale position forever.
 *  5. **holding is a first-class answer** — a lost subject keeps the last position at
 *     confidence 0, so a consumer can tell "seen" from "assumed" (the same contract the
 *     motion-based selector uses).
 *
 * Pure Kotlin: no Android, no ML Kit types, so CI runs every rule above.
 */
class FaceSelector(
    /** Normalised centre of the box the user drew: "which face do I care about". */
    private val seedX: Float,
    private val seedY: Float,
    private val config: Config = Config(),
    /**
     * Auto frame has no box to point at, so the first lock takes the *most prominent* face
     * instead of the one nearest the frame centre — the subject is normally the bigger, closer
     * face, and the nearest-to-centre rule would pick whoever happens to stand in the middle.
     *
     * Only the *first* lock is affected: once a face is locked, identity and the insist-ladder
     * decide as usual. The ladder still applies to that first lock (a face must be detected
     * `minHold` samples in a row before it becomes the subject), because with no box there is no
     * user intent to trust.
     */
    private val preferProminentFirst: Boolean = false
) {

    /**
     * @param minArea faces smaller than this fraction of the frame are ignored outright (a
     *        distant bystander cannot steal the track from the subject in the box)
     * @param minHold consecutive consistent samples before a *new* face is adopted
     * @param jumpTolerance relative distance beyond which a face is a different subject
     * @param reacquireAfter missed samples after which the search reopens from the seed box
     * @param samePlace how close two samples must be to count as "the same place"
     */
    data class Config(
        val minArea: Float = 0.002f,
        val minHold: Int = 3,
        val jumpTolerance: Float = 0.15f,
        val reacquireAfter: Int = 5,
        val samePlace: Float = 0.06f
    )

    /** One detected face, normalised against the analysed (display-oriented) frame. */
    data class Face(val x: Float, val y: Float, val area: Float, val trackId: Int? = null)

    /**
     * The frame's decision. [confidence] is 0 when the subject was not seen this sample, in
     * which case [x]/[y] hold the last known position.
     */
    data class Decision(
        val x: Float,
        val y: Float,
        val confidence: Float,
        val trackId: Int?,
        val seen: Boolean
    )

    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lockedTrackId: Int? = null
    private var pendingX = 0f
    private var pendingY = 0f
    private var pendingTrackId: Int? = null
    private var pendingCount = 0
    private var missed = 0

    /** How many times the followed face actually changed. */
    var switches = 0
        private set

    /** How many samples showed a confirmed face. */
    var seenCount = 0
        private set

    fun accept(faces: List<Face>): Decision {
        val candidates = faces.filter { it.area >= config.minArea }

        // 1. The locked identity wins outright. Identity survives a crowd; distance does not.
        val locked = lockedTrackId
        if (locked != null) {
            candidates.firstOrNull { it.trackId == locked }?.let { return adopt(it, switched = false) }
        }

        if (candidates.isEmpty()) {
            missed++
            pendingCount = 0
            return hold()
        }

        // 2. Reference point: where the subject was — unless the track has been broken long
        //    enough that re-opening from the user's box is the only way back.
        val reopen = lastX.isNaN() || missed >= config.reacquireAfter
        val refX = if (reopen) seedX else lastX
        val refY = if (reopen) seedY else lastY
        val best = if (preferProminentFirst && lastX.isNaN()) {
            // No box: prominence decides who the subject is, not proximity to the centre.
            candidates.maxByOrNull { it.area } ?: return hold()
        } else {
            candidates.minByOrNull { distance(it, refX, refY) } ?: return hold()
        }
        val d = distance(best, refX, refY)

        // 3. First lock on a face near the box is the user's own choice: adopt immediately.
        //    Without a box there is no such choice, so the ladder has to confirm it instead.
        if (lastX.isNaN() && !preferProminentFirst && d <= config.jumpTolerance) {
            return adopt(best, switched = false)
        }

        // 4. A face far from where the subject was is a different subject until it insists.
        val far = !reopen && d > config.jumpTolerance
        if (far) {
            missed++
            pendingCount = 0
            return hold()
        }

        // 5. Confirmation ladder: same face (by id) or same place, minHold samples running.
        val sameAsPending = pendingCount > 0 && (
                (best.trackId != null && best.trackId == pendingTrackId) ||
                        distance(best, pendingX, pendingY) <= config.samePlace
                )
        pendingCount = if (sameAsPending) pendingCount + 1 else 1
        pendingX = best.x
        pendingY = best.y
        pendingTrackId = best.trackId

        if (pendingCount >= config.minHold) {
            val switched = !lastX.isNaN() &&
                    ((locked != null && best.trackId != locked) || d > config.jumpTolerance)
            return adopt(best, switched = switched)
        }
        return hold()
    }

    private fun adopt(face: Face, switched: Boolean): Decision {
        if (switched) switches++
        lastX = face.x
        lastY = face.y
        lockedTrackId = face.trackId
        missed = 0
        pendingCount = 0
        seenCount++
        return Decision(face.x, face.y, confidenceFor(face), face.trackId, seen = true)
    }

    private fun hold(): Decision = Decision(
        x = if (lastX.isNaN()) seedX else lastX,
        y = if (lastY.isNaN()) seedY else lastY,
        confidence = 0f,
        trackId = lockedTrackId,
        seen = false
    )

    /**
     * Confidence proxy. ML Kit publishes no per-face score, so prominence stands in for it: a
     * face that fills a fifth of the frame reads as a full-confidence detection. It is a proxy
     * and is used as one — ordering samples, never gating them.
     */
    private fun confidenceFor(face: Face): Float =
        (face.area / CONFIDENT_AREA).coerceIn(0f, 1f)

    private fun distance(a: Face, x: Float, y: Float): Float {
        if (x.isNaN() || y.isNaN()) return Float.MAX_VALUE
        val dx = a.x - x
        val dy = a.y - y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val CONFIDENT_AREA = 0.2f

        /**
         * Margin that keeps a tracked centre inside the frame.
         *
         * The correlator clamps to half the marked box — the patch it has to keep inside the
         * frame. Faces have no patch, so the selection stands in for it, **except** when the
         * selection is the whole frame (auto frame has no box, so the frame *is* the selection):
         * clamping to half of that would pin every sample to the exact centre and produce a path
         * that never pans. That case gets a small margin instead, because the subject may travel
         * almost the entire frame.
         */
        fun clampMargin(
            selectionSize: Float,
            fullFrameThreshold: Float = 0.98f,
            minMargin: Float = 0.02f
        ): Float = if (selectionSize >= fullFrameThreshold) {
            minMargin
        } else {
            (selectionSize / 2f).coerceIn(0f, 0.5f)
        }

        /**
         * Drop the samples before the first confirmed sighting.
         *
         * Two reasons, both about honesty: a path that starts with held positions *pretends* the
         * subject was found earlier than it was, and pulling the first frames to the user's box
         * would make the camera start on a guess. Returns null when the subject was never
         * confirmed twice — the caller must then return no path at all rather than a fabricated
         * static one.
         */
        fun trace(times: List<Long>, decisions: List<Decision>): Trace? {
            val n = minOf(times.size, decisions.size)
            if (n < 2) return null
            val confirmed = (0 until n).count { decisions[it].seen }
            if (confirmed < 2) return null
            val first = (0 until n).first { decisions[it].seen }
            if (n - first < 2) return null
            return Trace(times.subList(first, n), decisions.subList(first, n))
        }

        /** A path fragment that actually starts where the subject was first seen. */
        data class Trace(val times: List<Long>, val decisions: List<Decision>) {
            val seenCount: Int get() = decisions.count { it.seen }

            /** Held samples sit between sightings and are bridged, not trusted. */
            fun confidenceFlags(): List<Boolean> = decisions.map { it.seen }

            fun centres(): List<Pair<Float, Float>> = decisions.map { Pair(it.x, it.y) }

            fun scores(): List<Float> = decisions.map { it.confidence }

            /** Nothing moved by more than [deadZone] — the frame would be a still image. */
            fun isStatic(deadZone: Float = 0.0015f): Boolean =
                centres().zipWithNext().all { (a, b) ->
                    abs(a.first - b.first) <= deadZone && abs(a.second - b.second) <= deadZone
                }
        }
    }
}
