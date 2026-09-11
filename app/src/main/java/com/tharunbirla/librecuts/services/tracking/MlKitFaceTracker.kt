package com.tharunbirla.librecuts.services.tracking

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.tharunbirla.librecuts.services.perception.FaceSelector
import com.tharunbirla.librecuts.services.perception.VideoFrameSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * ML Kit Face Detection as a tracking backend — the "accurate" tier next to the built-in NCC
 * template matcher ("current").
 *
 * It implements the same [TrackingEngine] contract, so nothing downstream changes: the reframe
 * planner, the preview and the export still receive timed, normalised subject centres. The
 * difference is *robustness*, not speed — a detector holds through rotation, deformation and low
 * texture, where a correlator drifts or jumps to a similar neighbour. Frame decoding is still the
 * dominant cost, so this engine is not faster; it is (perceived) latency that improves, because
 * 18–24 ms/frame is real-time enough to run while the video plays.
 *
 * Division of labour, deliberately:
 *  - **deciding is pure Kotlin** ([FaceSelector]) and therefore unit-tested on CI — identity
 *    lock, the confirmation ladder, the no-teleport rule, re-acquisition, honest holding;
 *  - **this file only does Android work**: decode, detect, delegate.
 *
 * Faces are found by the detector, but *which* face is the subject comes from the box the user
 * drew, so this engine is only meaningful for a seeded run (`Start Tracking`).
 *
 * Face Detection is also the right API for a person: ML Kit's on-device object detector has no
 * "person" class at all, and its whole-body box is the wrong target for a face-locked reframe.
 */
class MlKitFaceTracker(
    private val config: Config = Config(),
    private val selectorConfig: FaceSelector.Config = FaceSelector.Config()
) : TrackingEngine {

    /**
     * @param processingWidth face detection needs >= 480 px of analysis width; below that small
     *        faces are missed outright, which shows up as a track that keeps dropping.
     * @param sampleFps a detector needs far fewer samples than a correlator (nothing to search).
     * @param minFaceSize smallest face to report, as a fraction of the frame's shorter side.
     * @param smoothingHalfWindow window of the centred moving average applied to the path.
     */
    data class Config(
        val processingWidth: Int = 480,
        val sampleFps: Int = 6,
        val minFaceSize: Float = 0.05f,
        val smoothingHalfWindow: Int = 2
    )

    override suspend fun track(
        context: Context,
        request: TrackingRequest,
        onProgress: (Float) -> Unit
    ): TrackingResult = withContext(Dispatchers.IO) {
        // Held explicitly: a cancellation check inside the per-sample lambda below has to name
        // the scope, because the lambda itself has no receiver.
        val scope = this
        val selection = request.selection.sanitized()

        // One decoder for every engine: rotation policy and the sample grid stay identical.
        val sampler = VideoFrameSampler.open(context, request.videoUri, config.processingWidth)
            ?: return@withContext empty(0, 0, 0)

        // Landmark, classification and contour modes stay OFF: they cost latency and buy nothing
        // for framing. Tracking ids are ON — identity is what stops the frame jumping between two
        // people, and it cannot be reconstructed afterwards from geometry alone.
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(config.minFaceSize)
                .enableTracking()
                .build()
        )

        try {
            val times = sampler.sampleTimes(request.startTimeMs, request.endTimeMs, config.sampleFps)
            if (times.size < 2) {
                return@withContext empty(sampler.displayWidth, sampler.displayHeight, times.size)
            }

            val selector = FaceSelector(
                seedX = selection.centerX,
                seedY = selection.centerY,
                config = selectorConfig,
                // Box-less run (auto frame): the engine picks the subject, and the insist-ladder
                // confirms it before the camera moves.
                preferProminentFirst = request.preferProminentSubject
            )
            val decisions = ArrayList<FaceSelector.Decision>(times.size)
            times.forEachIndexed { index, timeMs ->
                scope.ensureActive()
                val bitmap = sampler.sampleBitmap(timeMs, config.processingWidth)
                val decision = if (bitmap == null) {
                    // A frame that would not decode is a gap, not a failure: hold and move on.
                    selector.accept(emptyList())
                } else {
                    try {
                        // A detector error on one sample is the same kind of gap.
                        selector.accept(runCatching { detect(detector, bitmap) }.getOrDefault(emptyList()))
                    } finally {
                        runCatching { bitmap.recycle() }
                    }
                }
                decisions.add(decision)
                onProgress((index + 1).toFloat() / times.size.toFloat())
            }

            // No trace means the subject was never confirmed twice: return no path rather than a
            // fabricated static one.
            val trace = FaceSelector.trace(times, decisions)
                ?: return@withContext empty(sampler.displayWidth, sampler.displayHeight, times.size)

            // Margins come from the selection — except for a full-frame selection, which would
            // otherwise clamp every sample to the exact centre (auto frame has no box).
            val marginW = FaceSelector.clampMargin(selection.width)
            val marginH = FaceSelector.clampMargin(selection.height)
            val clamped = trace.centres().map { centre ->
                Pair(
                    centre.first.coerceIn(marginW, 1f - marginW),
                    centre.second.coerceIn(marginH, 1f - marginH)
                )
            }

            val bridged = TrackingMath.bridgeGaps(clamped, trace.confidenceFlags())
            val smoothed = TrackingMath.smooth(bridged, config.smoothingHalfWindow)
            val points = TrackingMath.buildTrackPoints(
                times = trace.times,
                centers = smoothed,
                scores = trace.scores(),
                deadZone = request.deadZone,
                epsilon = request.simplifyEpsilon
            )

            TrackingResult(
                points = points,
                sourceWidth = sampler.displayWidth,
                sourceHeight = sampler.displayHeight,
                sampledFrames = times.size,
                trackedFrames = trace.seenCount,
                averageConfidence = trace.scores().average().toFloat()
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Face tracking failed: ${t.message}")
            empty(sampler.displayWidth, sampler.displayHeight, 0)
        } finally {
            runCatching { detector.close() }
            sampler.release()
        }
    }

    /** Faces in [bitmap] as normalised frame coordinates (uniform scaling preserves fractions). */
    private fun detect(detector: FaceDetector, bitmap: Bitmap): List<FaceSelector.Face> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        val w = bitmap.width.toFloat().coerceAtLeast(1f)
        val h = bitmap.height.toFloat().coerceAtLeast(1f)
        return faces.mapNotNull { face ->
            val box = face.boundingBox
            if (box.width() <= 0 || box.height() <= 0) return@mapNotNull null
            FaceSelector.Face(
                x = (box.exactCenterX() / w).coerceIn(0f, 1f),
                y = (box.exactCenterY() / h).coerceIn(0f, 1f),
                area = ((box.width().toFloat() * box.height().toFloat()) / (w * h)).coerceIn(0f, 1f),
                trackId = face.trackingId
            )
        }
    }

    private fun empty(width: Int, height: Int, sampled: Int) = TrackingResult(
        points = emptyList(),
        sourceWidth = width,
        sourceHeight = height,
        sampledFrames = sampled,
        trackedFrames = 0,
        averageConfidence = 0f
    )

    companion object {
        private const val TAG = "MlKitFaceTracker"
    }
}
