package com.tharunbirla.librecuts.services.tracking

import android.content.Context
import android.net.Uri

/**
 * Everything a tracking backend needs. Carries no framing intent: the backend is free to
 * ignore how the result will be used.
 */
data class TrackingRequest(
    val videoUri: Uri,
    /** Project-timeline time where tracking starts (usually the playhead). */
    val startTimeMs: Long,
    /** Project-timeline time where tracking ends. */
    val endTimeMs: Long,
    val selection: TrackingSelection,
    val sampleFps: Int = 6,
    val processingWidth: Int = 320,
    /** Local search radius per sample, as a multiple of the selection width. */
    val searchRadiusFactor: Float = 0.35f,
    /** NCC score below this counts as a miss (occlusion, cut, motion blur). */
    val minScore: Float = 0.35f,
    /** Samples closer together than this (relative units) collapse into one keyframe. */
    val deadZone: Float = 0.0015f,
    /** Douglas-Peucker tolerance (relative units) used to thin the emitted path. */
    val simplifyEpsilon: Float = 0.004f
)

/**
 * A replaceable tracking backend.
 *
 * Everything downstream — reframe planner, preview, export — depends on this interface
 * and on [TrackingResult] alone, never on a concrete implementation. Swapping the
 * built-in NCC template matcher for optical flow or a learned tracker is therefore a
 * one-line change in [ObjectTrackingService.engine].
 */
interface TrackingEngine {
    suspend fun track(
        context: Context,
        request: TrackingRequest,
        onProgress: (Float) -> Unit = {}
    ): TrackingResult
}
