package com.tharunbirla.librecuts.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.tharunbirla.librecuts.models.EditOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dependency-free object tracker.
 *
 * The user draws a box around a subject; this service samples the clip at a low frame
 * rate, follows the box with normalized cross-correlation (NCC) template matching, then
 * returns a smoothed + simplified motion path. The export pipeline turns that path into
 * an animatable crop, which is what keeps the subject centered in the final video.
 *
 * Deliberately self-contained (no OpenCV / ML Kit), and behind a narrow interface so a
 * stronger backend can replace [track] later without touching the model, the preview or
 * the render engine.
 *
 * Performance: frames are decoded at [Request.processingWidth] px and the search is
 * coarse-to-fine (stride-2 template first, then a +-2 px refinement), which keeps a whole
 * clip down to seconds on mid-range phones.
 */
object ObjectTrackingService {

    /** Box to track, relative to the display-oriented video frame (0..1). */
    data class Selection(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) {
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f

        /** Keeps the selection inside the frame regardless of what the UI passed. */
        fun sanitized(): Selection {
            val w = width.coerceIn(0.02f, 1f)
            val h = height.coerceIn(0.02f, 1f)
            val l = left.coerceIn(0f, 1f - w)
            val t = top.coerceIn(0f, 1f - h)
            return Selection(l, t, w, h)
        }
    }

    data class Request(
        val videoUri: Uri,
        /** Project-timeline time where tracking starts (usually the playhead). */
        val startTimeMs: Long,
        /** Project-timeline time where tracking ends. */
        val endTimeMs: Long,
        val selection: Selection,
        val sampleFps: Int = 6,
        val processingWidth: Int = 320,
        /** Local search radius per sample, as a multiple of the selection width. */
        val searchRadiusFactor: Float = 0.35f,
        /** NCC score below this counts as a miss (occlusion, cut, motion blur). */
        val minScore: Float = 0.35f
    )

    data class Result(
        val path: List<EditOperation.KeyframePoint>,
        val sampledFrames: Int,
        val trackedFrames: Int,
        val averageConfidence: Float
    ) {
        val isEmpty: Boolean get() = path.isEmpty()
    }

    /**
     * Follow [Request.selection] through the clip.
     *
     * @param onProgress receives 0f..1f as sampling advances. Called from a background thread.
     */
    suspend fun track(
        context: Context,
        request: Request,
        onProgress: (Float) -> Unit = {}
    ): Result = withContext(Dispatchers.Default) {
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
            if (rawW <= 0 || rawH <= 0) {
                return@withContext Result(emptyList(), 0, 0, 0f)
            }

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
            if (times.size < 2) return@withContext Result(emptyList(), times.size, 0, 0f)

            val firstFrame = loadGrayFrame(
                retriever, times.first(), displayW, displayH, request.processingWidth, rotation
            ) ?: return@withContext Result(emptyList(), times.size, 0, 0f)

            val tw = max(8, (selection.width * firstFrame.w).toInt()).coerceAtMost(firstFrame.w)
            val th = max(8, (selection.height * firstFrame.h).toInt()).coerceAtMost(firstFrame.h)
            var tx = (selection.left * firstFrame.w).toInt().coerceIn(0, firstFrame.w - tw)
            var ty = (selection.top * firstFrame.h).toInt().coerceIn(0, firstFrame.h - th)

            var template = Template.from(firstFrame, tx, ty, tw, th, 1)
            val coarseTemplate = Template.from(firstFrame, tx, ty, tw, th, 2)
            val searchRadius = max(
                4,
                (selection.width * firstFrame.w * request.searchRadiusFactor).toInt()
            )

            val rawCenters = ArrayList<Pair<Float, Float>>(times.size)
            rawCenters.add(Pair((tx + tw / 2f) / firstFrame.w, (ty + th / 2f) / firstFrame.h))

            var tracked = 1
            var confidenceSum = 1f

            for (i in 1 until times.size) {
                coroutineContext.ensureActive()

                val frame = loadGrayFrame(
                    retriever, times[i], displayW, displayH, request.processingWidth, rotation
                )
                if (frame == null) {
                    rawCenters.add(rawCenters.last())
                    continue
                }

                var match = search(frame, coarseTemplate, template, tx, ty, searchRadius)
                if (match.score < request.minScore) {
                    // Subject moved faster than the local radius: retry with a wider net.
                    match = search(frame, coarseTemplate, template, tx, ty, searchRadius * 3)
                }

                if (match.score >= request.minScore) {
                    tx = match.x
                    ty = match.y
                    tracked++
                    confidenceSum += match.score
                    if (match.score > 0.80f) {
                        // Slowly adapt to gradual appearance change (lighting, angle).
                        val fresh = Template.from(frame, tx, ty, tw, th, 1)
                        template = blend(template, fresh, 0.15f)
                    }
                }
                // On a miss the last known position is kept, so the path stays continuous.
                rawCenters.add(Pair((tx + tw / 2f) / frame.w, (ty + th / 2f) / frame.h))

                if (i % 4 == 0 || i == times.size - 1) {
                    onProgress(i.toFloat() / (times.size - 1).toFloat())
                }
            }

            val halfW = selection.width / 2f
            val halfH = selection.height / 2f
            val clamped = rawCenters.map { c ->
                Pair(
                    c.first.coerceIn(halfW, 1f - halfW),
                    c.second.coerceIn(halfH, 1f - halfH)
                )
            }
            val smoothed = smooth(clamped)
            val keyframes = buildKeyframes(times, smoothed)

            Result(
                path = keyframes,
                sampledFrames = times.size,
                trackedFrames = tracked,
                averageConfidence = if (tracked > 0) confidenceSum / tracked else 0f
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result(emptyList(), 0, 0, 0f)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private class Gray(val data: FloatArray, val w: Int, val h: Int)

    /**
     * Template patch with precomputed statistics.
     * [stride] > 1 samples the patch coarsely (used for the first search pass).
     */
    private class Template(
        val values: FloatArray,
        val w: Int,
        val h: Int,
        val stride: Int,
        val mean: Float,
        val norm: Float
    ) {
        companion object {
            fun from(g: Gray, x: Int, y: Int, w: Int, h: Int, stride: Int): Template {
                val tw = max(1, w / stride)
                val th = max(1, h / stride)
                val values = FloatArray(tw * th)
                var sum = 0f
                var i = 0
                var ty = 0
                while (ty < th) {
                    var tx = 0
                    while (tx < tw) {
                        val v = g.data[(y + ty * stride) * g.w + (x + tx * stride)]
                        values[i++] = v
                        sum += v
                        tx++
                    }
                    ty++
                }
                val mean = sum / (tw * th).toFloat()
                var acc = 0f
                for (v in values) {
                    val d = v - mean
                    acc += d * d
                }
                return Template(values, tw, th, stride, mean, sqrt(acc).coerceAtLeast(1e-5f))
            }
        }
    }

    private data class Match(val x: Int, val y: Int, val score: Float)

    // ── frame loading ───────────────────────────────────────────────────────

    private fun loadGrayFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        displayW: Int,
        displayH: Int,
        processingWidth: Int,
        rotation: Int
    ): Gray? {
        val bitmap = runCatching {
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null

        try {
            // getFrameAtTime returns the stored orientation on some devices and the
            // display orientation on others — detect it instead of trusting either.
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
        } finally {
            bitmap.recycle()
        }
    }

    private fun toGray(bitmap: Bitmap): Gray {
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
        return Gray(out, w, h)
    }

    // ── matching ────────────────────────────────────────────────────────────

    /** Coarse-to-fine NCC search around the previous position. */
    private fun search(
        frame: Gray,
        coarse: Template,
        fine: Template,
        prevX: Int,
        prevY: Int,
        radius: Int
    ): Match {
        val coarseBest = searchWith(frame, coarse, prevX, prevY, radius)
        if (coarseBest.score < -1f) return Match(prevX, prevY, -1f)

        // Re-score the coarse winner with the full-resolution template, then check its
        // immediate neighbours so we don't give up a pixel of accuracy.
        var bestX = coarseBest.x
        var bestY = coarseBest.y
        var bestScore = scoreAt(frame, fine, bestX, bestY)
        for (dy in -2..2) {
            for (dx in -2..2) {
                if (dx == 0 && dy == 0) continue
                val x = coarseBest.x + dx
                val y = coarseBest.y + dy
                if (x < 0 || y < 0 || x + fine.w > frame.w || y + fine.h > frame.h) continue
                val s = scoreAt(frame, fine, x, y)
                if (s > bestScore) {
                    bestScore = s
                    bestX = x
                    bestY = y
                }
            }
        }
        return Match(bestX, bestY, bestScore)
    }

    private fun searchWith(
        frame: Gray,
        template: Template,
        centerX: Int,
        centerY: Int,
        radius: Int
    ): Match {
        var best = Match(centerX, centerY, -2f)
        val x0 = max(0, centerX - radius)
        val x1 = min(frame.w - template.w, centerX + radius)
        val y0 = max(0, centerY - radius)
        val y1 = min(frame.h - template.h, centerY + radius)
        if (x1 < x0 || y1 < y0) return best

        for (y in y0..y1) {
            for (x in x0..x1) {
                val score = scoreAt(frame, template, x, y)
                if (score > best.score) best = Match(x, y, score)
            }
        }
        return best
    }

    /**
     * Normalized cross-correlation of [template] against the patch at (x, y).
     *
     * Both the dot product and the candidate statistics are computed over the *sampled*
     * pixels only, so a stride-2 template stays statistically consistent. Returns -1f for
     * out-of-bounds or flat (texture-less) patches.
     */
    private fun scoreAt(frame: Gray, template: Template, x: Int, y: Int): Float {
        if (x < 0 || y < 0 || x + template.w > frame.w || y + template.h > frame.h) return -1f

        var dot = 0f
        var sumC = 0f
        var sumC2 = 0f
        var count = 0
        var ty = 0
        while (ty < template.h) {
            val srcRow = (y + ty * template.stride) * frame.w + x
            val tplRow = ty * template.w
            var tx = 0
            while (tx < template.w) {
                val c = frame.data[srcRow + tx * template.stride]
                dot += template.values[tplRow + tx] * c
                sumC += c
                sumC2 += c * c
                count++
                tx++
            }
            ty++
        }
        if (count == 0) return -1f

        val n = count.toFloat()
        val meanC = sumC / n
        val varC = sumC2 - meanC * sumC
        if (varC <= 1e-6f) return -1f

        val numerator = dot - template.mean * sumC
        val denominator = sqrt(varC) * template.norm.coerceAtLeast(1e-5f)
        return numerator / denominator
    }

    private fun blend(old: Template, fresh: Template, weight: Float): Template {
        if (old.values.size != fresh.values.size) return fresh
        val out = FloatArray(old.values.size)
        for (i in out.indices) {
            out[i] = old.values[i] * (1f - weight) + fresh.values[i] * weight
        }
        val mean = old.mean * (1f - weight) + fresh.mean * weight
        var acc = 0f
        for (v in out) {
            val d = v - mean
            acc += d * d
        }
        return Template(out, old.w, old.h, old.stride, mean, sqrt(acc).coerceAtLeast(1e-5f))
    }

    // ── path post-processing ────────────────────────────────────────────────

    /** Forward then backward exponential smoothing kills per-frame jitter. */
    private fun smooth(
        points: List<Pair<Float, Float>>,
        alpha: Float = 0.4f
    ): List<Pair<Float, Float>> {
        if (points.size < 3) return points
        val forward = ArrayList<Pair<Float, Float>>(points.size)
        var x = points.first().first
        var y = points.first().second
        for (p in points) {
            x += alpha * (p.first - x)
            y += alpha * (p.second - y)
            forward.add(Pair(x, y))
        }
        val backward = arrayOfNulls<Pair<Float, Float>>(points.size)
        x = forward.last().first
        y = forward.last().second
        for (i in forward.indices.reversed()) {
            x += alpha * (forward[i].first - x)
            y += alpha * (forward[i].second - y)
            backward[i] = Pair(x, y)
        }
        return backward.map { it ?: points.first() }
    }

    /**
     * Turn the dense per-sample path into sparse keyframes: samples that barely move
     * collapse into one point, then Douglas-Peucker trims the rest so the generated
     * ffmpeg expression stays small.
     */
    private fun buildKeyframes(
        times: List<Long>,
        centers: List<Pair<Float, Float>>
    ): List<EditOperation.KeyframePoint> {
        if (times.isEmpty() || centers.isEmpty()) return emptyList()
        val n = min(times.size, centers.size)

        val deduped = ArrayList<Pair<Long, Pair<Float, Float>>>(n)
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (i in 0 until n) {
            val c = centers[i]
            val moved = lastX.isNaN() ||
                    abs(c.first - lastX) > DEAD_ZONE ||
                    abs(c.second - lastY) > DEAD_ZONE
            if (moved || i == n - 1) {
                deduped.add(Pair(times[i], c))
                lastX = c.first
                lastY = c.second
            }
        }

        val simplified = douglasPeucker(deduped, EPSILON)
        return simplified.map { entry ->
            EditOperation.KeyframePoint(
                timeMs = entry.first,
                valueX = entry.second.first,
                valueY = entry.second.second
            )
        }
    }

    private fun douglasPeucker(
        points: List<Pair<Long, Pair<Float, Float>>>,
        epsilon: Float
    ): List<Pair<Long, Pair<Float, Float>>> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        simplifySegment(points, 0, points.size - 1, epsilon, keep)
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun simplifySegment(
        points: List<Pair<Long, Pair<Float, Float>>>,
        start: Int,
        end: Int,
        epsilon: Float,
        keep: BooleanArray
    ) {
        if (end <= start + 1) return
        val first = points[start].second
        val last = points[end].second
        var maxDist = -1f
        var index = -1
        for (i in start + 1 until end) {
            val d = perpendicularDistance(points[i].second, first, last)
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

    /** Perpendicular distance in relative units (time is deliberately ignored). */
    private fun perpendicularDistance(
        point: Pair<Float, Float>,
        lineStart: Pair<Float, Float>,
        lineEnd: Pair<Float, Float>
    ): Float {
        val dx = lineEnd.first - lineStart.first
        val dy = lineEnd.second - lineStart.second
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) {
            val px = point.first - lineStart.first
            val py = point.second - lineStart.second
            return sqrt(px * px + py * py)
        }
        val numerator = abs(
            dy * point.first - dx * point.second + lineEnd.first * lineStart.second -
                    lineEnd.second * lineStart.first
        )
        return numerator / length
    }

    private const val DEAD_ZONE = 0.0015f
    private const val EPSILON = 0.004f
}
