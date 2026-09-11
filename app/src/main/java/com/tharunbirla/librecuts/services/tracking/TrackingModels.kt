package com.tharunbirla.librecuts.services.tracking

/**
 * Output of a tracking run.
 *
 * The tracker answers one question only — "where is the subject?" — and knows nothing
 * about aspect ratios, crops or framing. That separation is deliberate: the framing
 * decision lives in `services/reframe`, so either half can be replaced on its own.
 *
 * Coordinates are display-oriented (rotation already applied) and relative to the
 * source frame, so they survive any later resize of the working resolution.
 */
data class TrackPoint(
    val timeMs: Long,
    /** Subject centre, 0..1 across the display-oriented frame. */
    val x: Float,
    val y: Float,
    /**
     * NCC score of the sample that produced this point: 1 = perfect match, 0 = the
     * bridge/interpolation value used when the subject was lost (occlusion, cut, blur).
     */
    val confidence: Float = 1f
) {
    /**
     * Confidence to *use*, treating 0 as "unknown".
     *
     * Gson builds objects without running Kotlin's default arguments, so a path saved by
     * an older build deserializes [confidence] as 0f instead of 1f. Unknown must not be
     * read as "untrustworthy", which is why 0 maps back to full trust here.
     */
    val effectiveConfidence: Float get() = if (confidence <= 0f) 1f else confidence
}

/** Result of a tracking run over a clip. */
data class TrackingResult(
    val points: List<TrackPoint>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sampledFrames: Int,
    val trackedFrames: Int,
    val averageConfidence: Float
) {
    /** A path of fewer than two points cannot drive a moving frame. */
    val isEmpty: Boolean get() = points.size < 2
}

/** Box to track, relative to the display-oriented frame (0..1). */
data class TrackingSelection(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    /** Keeps a selection inside the frame regardless of what the UI handed over. */
    fun sanitized(): TrackingSelection {
        val w = width.coerceIn(0.02f, 1f)
        val h = height.coerceIn(0.02f, 1f)
        val l = left.coerceIn(0f, 1f - w)
        val t = top.coerceIn(0f, 1f - h)
        return TrackingSelection(l, t, w, h)
    }
}
