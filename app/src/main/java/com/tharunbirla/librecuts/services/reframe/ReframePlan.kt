package com.tharunbirla.librecuts.services.reframe

import com.tharunbirla.librecuts.models.ReframeAspect
import com.tharunbirla.librecuts.models.ReframeMode
import java.util.Locale

/** Window centre at a point in time, in source-relative coordinates (0..1). */
data class ReframeKeyframe(val timeMs: Long, val x: Float, val y: Float)

/**
 * A resolution-independent description of how the frame must move.
 *
 * Everything here is a fraction of the source frame, so the same plan drives a 480p
 * preview proxy and a 1080p export without recomputation. The plan carries no ffmpeg
 * syntax — [ReframeFilterBuilder] turns it into filter code.
 */
data class ReframePlan(
    /** Fraction of the frame width the crop window covers (1.0 = full width). */
    val windowWidthFraction: Float,
    /** Fraction of the frame height the crop window covers (1.0 = full height). */
    val windowHeightFraction: Float,
    /** Window centre over time, sorted by time, already clamped to the reachable range. */
    val keyframes: List<ReframeKeyframe>,
    /** Effective zoom (1.0 = window untouched). */
    val zoom: Float,
    /** Delivery canvas the window is scaled onto. */
    val outWidth: Int,
    val outHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val mode: ReframeMode,
    val aspect: ReframeAspect
) {
    /**
     * True when the window already covers the whole frame, so there is nothing to move
     * and the reframe must not be rendered at all (rendering it would only cost a
     * generation loss).
     */
    val isNoOp: Boolean
        get() = keyframes.size < 2 ||
                (windowWidthFraction >= 0.999f && windowHeightFraction >= 0.999f)

    /** True when at least one axis has room to travel. */
    val canPan: Boolean get() = !isNoOp

    /** Human-readable summary used by the tracking status bar. */
    fun describe(): String {
        if (isNoOp) return "no movement needed"
        val axis = when {
            windowWidthFraction >= 0.999f -> "vertical pan"
            windowHeightFraction >= 0.999f -> "horizontal pan"
            else -> "pan"
        }
        return String.format(
            Locale.US,
            "%s %.0f%% window, zoom x%.2f",
            axis,
            windowWidthFraction * 100f,
            zoom
        )
    }
}
