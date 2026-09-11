package com.tharunbirla.librecuts.services.tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The built-in tracker: dependency-free normalized cross-correlation template matching.
 *
 * Responsibilities are deliberately narrow — it decodes frames, drives [TrackingMath] and
 * returns where the subject went. It never touches crops, aspect ratios or ffmpeg; that is
 * [com.tharunbirla.librecuts.services.reframe.ReframePlanner]'s job, which is what lets
 * either half be swapped without disturbing the other.
 *
 * Performance: frames are decoded at [TrackingRequest.processingWidth] px and matched
 * coarse-to-fine, which keeps a whole clip down to seconds on a mid-range phone.
 */
class TemplateMatchTracker(
    private val config: Config = Config()
) : TrackingEngine {

    data class Config(
        /** NCC above which the appearance model may drift toward the new look. */
        val adaptThreshold: Float = 0.75f,
        /** Weight of the fresh patch when adapting (0 = never, 1 = forget instantly). */
        val adaptWeight: Float = 0.15f,
        /** Width of the centred moving average, in samples. */
        val smoothingHalfWindow: Int = 2,
        /** Carry-over of the previous velocity into the next search prediction (0..1). */
        val velocityDamping: Float = 0.6f
    )

    override suspend fun track(
        context: Context,
        request: TrackingRequest,
        onProgress: (Float) -> Unit
    ): TrackingResult = withContext(Dispatchers.Default) {
        val selection = request.selection.sanitized()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, request.videoUri)

            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val rawW = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawH = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val clipDurationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (rawW <= 0 || rawH <= 0) return@withContext emptyResult()

            val rotated = rotation == 90 || rotation == 270
            val displayW = if (rotated) rawH else rawW
            val displayH = if (rotated) rawW else rawH

            val endMs = when {
                clipDurationMs > request.startTimeMs + 1 &&
                        request.endTimeMs in (request.startTimeMs + 1)..clipDurationMs ->
                    request.endTimeMs
                clipDurationMs > request.startTimeMs + 1 -> clipDurationMs
                else -> request.endTimeMs
            }
            val stepMs = max(1L, 1000L / request.sampleFps.coerceIn(1, 30))
            val times = generateSequence(request.startTimeMs) { it + stepMs }
                .takeWhile { it <= endMs }
                .toList()
            if (times.size < 2) {
                return@withContext emptyResult(displayW, displayH, times.size)
            }

            val firstFrame = loadGrayFrame(
                retriever, times.first(), displayW, displayH, request.processingWidth, rotation
            ) ?: return@withContext emptyResult(displayW, displayH, times.size)

            // Patch size and start position, in processing-resolution pixels.
            val tw = max(8, (selection.width * firstFrame.w).toInt()).coerceAtMost(firstFrame.w)
            val th = max(8, (selection.height * firstFrame.h).toInt()).coerceAtMost(firstFrame.h)
            var originX = (selection.left * firstFrame.w).toInt().coerceIn(0, firstFrame.w - tw)
            var originY = (selection.top * firstFrame.h).toInt().coerceIn(0, firstFrame.h - th)

            // Appearance model: the fine template is the authority, the coarse ones only
            // localise. Both start from the frame the user marked the subject on.
            var fine = TrackingMath.Template.from(firstFrame, originX, originY, tw, th, 1, 1f)
            var coarse = TrackingMath.coarseTemplates(firstFrame, originX, originY, tw, th)

            val searchRadius = max(
                4,
                (selection.width * firstFrame.w * request.searchRadiusFactor).toInt()
            )

            val centers = ArrayList<Pair<Float, Float>>(times.size)
            val scores = ArrayList<Float>(times.size)
            val confidentFlags = ArrayList<Boolean>(times.size)

            var lastX = originX
            var lastY = originY
            var velocityX = 0f
            var velocityY = 0f
            var tracked = 1
            var confidenceSum = 1f

            centers.add(relativeCentre(lastX, lastY, tw, th, firstFrame))
            scores.add(1f)
            confidentFlags.add(true)

            for (i in 1 until times.size) {
                coroutineContext.ensureActive()

                val frame = loadGrayFrame(
                    retriever, times[i], displayW, displayH, request.processingWidth, rotation
                )
                if (frame == null) {
                    centers.add(centers.last())
                    scores.add(0f)
                    confidentFlags.add(false)
                    continue
                }

                // Velocity prediction: aim the search where the subject is going. On a fast
                // pan this is the difference between staying locked and losing the box.
                val predictedX = lastX + velocityX.roundToInt()
                val predictedY = lastY + velocityY.roundToInt()

                var match = TrackingMath.search(frame, coarse, fine, predictedX, predictedY, searchRadius)
                if (match.score < request.minScore) {
                    // Missed at the predicted spot: retry from the last known position with
                    // a wider net before giving up on this sample.
                    match = TrackingMath.search(frame, coarse, fine, lastX, lastY, searchRadius * 3)
                }

                if (match.score >= request.minScore) {
                    val matchX = match.x
                    val matchY = match.y
                    if (tracked > 0) {
                        velocityX = config.velocityDamping * velocityX +
                                (1f - config.velocityDamping) * (matchX - lastX)
                        velocityY = config.velocityDamping * velocityY +
                                (1f - config.velocityDamping) * (matchY - lastY)
                    }
                    lastX = matchX
                    lastY = matchY
                    tracked++
                    confidenceSum += match.score

                    if (match.score > config.adaptThreshold) {
                        // Gradual appearance change (lighting, angle, scale): drift the
                        // reference toward the current look, and re-derive the coarse
                        // templates from the same fresh patch so they stay in sync.
                        val fresh = TrackingMath.Template.from(frame, matchX, matchY, tw, th, 1, 1f)
                        fine = TrackingMath.blend(fine, fresh, config.adaptWeight)
                        coarse = TrackingMath.coarseTemplates(frame, matchX, matchY, tw, th)
                    }

                    // Sub-pixel peak keeps the centre from snapping to whole pixels.
                    val centreX = matchX + match.dx + tw / 2f
                    val centreY = matchY + match.dy + th / 2f
                    centers.add(Pair(centreX / frame.w, centreY / frame.h))
                    scores.add(match.score)
                    confidentFlags.add(true)
                } else {
                    // Nothing usable: record the hold position as *unconfident* and let the
                    // gap bridge in TrackingMath.bridgeGaps keep the frame moving smoothly.
                    centers.add(relativeCentre(lastX, lastY, tw, th, frame))
                    scores.add(0f)
                    confidentFlags.add(false)
                    velocityX = 0f
                    velocityY = 0f
                }

                if (i % 4 == 0 || i == times.size - 1) {
                    onProgress(i.toFloat() / (times.size - 1).toFloat())
                }
            }

            // Keep the box inside the frame, bridge occlusions, then smooth. Order matters:
            // clamping first stops a bogus sample from dragging the average off-frame, and
            // bridging before smoothing keeps the linear ramp linear.
            val halfW = selection.width / 2f
            val halfH = selection.height / 2f
            val clamped = centers.map { c ->
                Pair(
                    c.first.coerceIn(halfW, 1f - halfW),
                    c.second.coerceIn(halfH, 1f - halfH)
                )
            }
            val bridged = TrackingMath.bridgeGaps(clamped, confidentFlags)
            val smoothed = TrackingMath.smooth(bridged, config.smoothingHalfWindow)
            val points = TrackingMath.buildTrackPoints(
                times = times,
                centers = smoothed,
                scores = scores,
                deadZone = request.deadZone,
                epsilon = request.simplifyEpsilon
            )

            TrackingResult(
                points = points,
                sourceWidth = displayW,
                sourceHeight = displayH,
                sampledFrames = times.size,
                trackedFrames = tracked,
                averageConfidence = if (tracked > 0) confidenceSum / tracked else 0f
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            emptyResult()
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ── frame decoding ─────────────────────────────────────────────────────────

    private fun relativeCentre(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        frame: TrackingMath.Gray
    ): Pair<Float, Float> = Pair((x + w / 2f) / frame.w, (y + h / 2f) / frame.h)

    private fun emptyResult(
        width: Int = 0,
        height: Int = 0,
        sampled: Int = 0
    ) = TrackingResult(
        points = emptyList(),
        sourceWidth = width,
        sourceHeight = height,
        sampledFrames = sampled,
        trackedFrames = 0,
        averageConfidence = 0f
    )

    private fun loadGrayFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        displayW: Int,
        displayH: Int,
        processingWidth: Int,
        rotation: Int
    ): TrackingMath.Gray? {
        val bitmap = runCatching {
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null

        try {
            // getFrameAtTime returns the stored orientation on some devices and the display
            // orientation on others — detect it instead of trusting either.
            val alreadyOriented = bitmap.width == displayW && bitmap.height == displayH
            val oriented = if (rotation != 0 && !alreadyOriented) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            val targetW = min(processingWidth, oriented.width).coerceAtLeast(48)
            val targetH = max(1, (oriented.height.toFloat() * targetW / oriented.width).toInt())
            val scaled = if (oriented.width != targetW || oriented.height != targetH) {
                Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
            } else {
                oriented
            }

            val gray = toGray(scaled)
            if (scaled !== oriented && scaled !== bitmap) scaled.recycle()
            if (oriented !== bitmap) oriented.recycle()
            return gray
        } catch (e: Throwable) {
            return null
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun toGray(bitmap: Bitmap): TrackingMath.Gray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return TrackingMath.Gray(out, w, h)
    }
}
