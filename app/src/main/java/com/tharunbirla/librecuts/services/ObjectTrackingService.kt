package com.tharunbirla.librecuts.services

import android.content.Context
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.services.tracking.TemplateMatchTracker
import com.tharunbirla.librecuts.services.tracking.TrackingEngine
import com.tharunbirla.librecuts.services.tracking.TrackingRequest
import com.tharunbirla.librecuts.services.tracking.TrackingResult

/**
 * Entry point the UI talks to for tracking.
 *
 * Deliberately thin: it forwards to a [TrackingEngine] and translates the result into the
 * keyframe model the project stores. All the tracking *algorithm* lives behind the engine
 * interface (`services/tracking`), and all the *framing* logic lives in
 * `services/reframe` — this object owns neither, so replacing either one leaves this file
 * untouched.
 *
 * Callers build a `TrackingRequest` with a `TrackingSelection` (see `services/tracking`);
 * Kotlin does not allow type aliases nested inside an object, so there are deliberately no
 * legacy shorthands here.
 */
object ObjectTrackingService {

    /**
     * Tracking output in the shape the project model stores.
     *
     * [sourceWidth]/[sourceHeight] describe the display-oriented frame; the reframe planner
     * needs them to decide how large a window it may crop.
     */
    class Result(
        val path: List<EditOperation.KeyframePoint>,
        val sampledFrames: Int,
        val trackedFrames: Int,
        val averageConfidence: Float,
        val sourceWidth: Int = 0,
        val sourceHeight: Int = 0
    ) {
        val isEmpty: Boolean get() = path.size < 2

        /** Fraction of samples where the subject was actually found (0..1). */
        val trackedRatio: Float
            get() = if (sampledFrames > 0) trackedFrames.toFloat() / sampledFrames else 0f

        companion object {
            fun from(result: TrackingResult): Result = Result(
                path = result.points.map {
                    EditOperation.KeyframePoint(it.timeMs, it.x, it.y, confidence = it.confidence)
                },
                sampledFrames = result.sampledFrames,
                trackedFrames = result.trackedFrames,
                averageConfidence = result.averageConfidence,
                sourceWidth = result.sourceWidth,
                sourceHeight = result.sourceHeight
            )

            fun empty(): Result = Result(emptyList(), 0, 0, 0f)
        }
    }

    /**
     * Active tracking backend. Assign a different engine to change the algorithm without
     * touching the UI, the model or the render pipeline.
     */
    @Volatile
    var engine: TrackingEngine = TemplateMatchTracker()

    /**
     * Follow [TrackingRequest.selection] through the clip.
     *
     * @param onProgress receives 0f..1f as sampling advances; called from a background thread.
     */
    suspend fun track(
        context: Context,
        request: TrackingRequest,
        onProgress: (Float) -> Unit = {}
    ): Result {
        if (request.selection.width <= 0f || request.selection.height <= 0f) {
            return Result.empty()
        }
        val result = try {
            engine.track(context, request, onProgress)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.w("ObjectTrackingService", "Tracking engine failed: ${e.message}")
            return Result.empty()
        }
        return Result.from(result)
    }
}
