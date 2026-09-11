package com.tharunbirla.librecuts.services.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/**
 * The Android half of auto frame: turn a video into the analysis frames [AutoFramePlanner]
 * wants.
 *
 * Deliberately the thinnest possible glue. All the decisions (what is a shot, what moved, which
 * blob is the subject, what the path is) live in the pure objects next to this one, because
 * those can be tested on CI and this cannot — decode needs a device.
 *
 * Two things matter here and nowhere else:
 *  - **rotation is applied before analysis**, so a portrait clip is analysed in display
 *    orientation. Skipping this would analyse a sideways frame and produce a path that is
 *    correct in the wrong axes.
 *  - **analysis size is independent of delivery size.** 320 px is enough to find a moving
 *    subject and keeps the cost per sample tiny; the path is normalised, so it survives any
 *    later resize (the same convention as `TrackingResult`).
 */
class VideoFrameSampler private constructor(
    private val retriever: MediaMetadataRetriever,
    val durationMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    private val analysisWidth: Int,
    private val rotationDegrees: Int
) {

    /**
     * One analysis frame at [timeMs], or null when the frame could not be decoded (a corrupt
     * sample must not abort a whole analysis run — the planner treats a gap as "no
     * comparison", which is what its held/lost logic already handles).
     */
    fun sample(timeMs: Long): LumaFrame? {
        val bitmap = runCatching {
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null

        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val alreadyOriented = bitmap.width == displayWidth && bitmap.height == displayHeight
            oriented = if (rotationDegrees != 0 && !alreadyOriented) {
                Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true
                )
            } else {
                bitmap
            }

            val targetW = min(analysisWidth, oriented.width).coerceAtLeast(16)
            val targetH = max(1, (oriented.height.toFloat() * targetW / oriented.width).toInt())
            scaled = if (oriented.width != targetW || oriented.height != targetH) {
                Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
            } else {
                oriented
            }
            return toLuma(scaled)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { if (scaled != null && scaled !== bitmap && scaled !== oriented) scaled.recycle() }
            runCatching { if (oriented != null && oriented !== bitmap) oriented.recycle() }
            runCatching { bitmap.recycle() }
        }
    }

    /**
     * Colour frame at [width] px wide, rotation applied — for engines that need pixels rather
     * than luminance (face detection, colour-aware tracking).
     *
     * **The caller owns the bitmap and must recycle it**: the sampler cannot know when a
     * detector has finished with it. Everything it allocated on the way is recycled here.
     */
    fun sampleBitmap(timeMs: Long, width: Int = analysisWidth): Bitmap? {
        val bitmap = runCatching {
            retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null

        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        var handedOut: Bitmap? = null
        try {
            val alreadyOriented = bitmap.width == displayWidth && bitmap.height == displayHeight
            oriented = if (rotationDegrees != 0 && !alreadyOriented) {
                Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true
                )
            } else {
                bitmap
            }

            val targetW = min(width, oriented.width).coerceAtLeast(48)
            val targetH = max(1, (oriented.height.toFloat() * targetW / oriented.width).toInt())
            scaled = if (oriented.width != targetW || oriented.height != targetH) {
                Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
            } else {
                oriented
            }

            handedOut = scaled
            return scaled
        } catch (t: Throwable) {
            handedOut = null
            return null
        } finally {
            // Never recycle the bitmap we are handing out; recycle everything else.
            runCatching { if (scaled != null && scaled !== handedOut && scaled !== oriented && scaled !== bitmap) scaled.recycle() }
            runCatching { if (oriented != null && oriented !== handedOut && oriented !== bitmap) oriented.recycle() }
            runCatching { if (bitmap !== handedOut) bitmap.recycle() }
        }
    }

    /**
     * The sample grid for [startMs]..[endMs] at [fps], clamped to the clip.
     *
     * Shared by every engine so two engines never disagree about *when* they looked — a
     * comparison between them would otherwise measure the grids, not the algorithms.
     */
    fun sampleTimes(
        startMs: Long = 0L,
        endMs: Long = durationMs,
        fps: Int = ANALYSIS_FPS
    ): List<Long> {
        val from = startMs.coerceIn(0L, durationMs)
        val to = endMs.coerceIn(from, durationMs)
        val step = max(1L, 1000L / fps.coerceIn(1, 30))
        if (to <= from) return emptyList()
        val times = generateSequence(from) { it + step }.takeWhile { it <= to }.toList()
        return if (times.size < 2) emptyList() else times
    }

    /**
     * Samples the whole clip (or the [startMs]..[endMs] range) at [fps] into planner input.
     *
     * @param onProgress 0..1, called sparsely — decoding is the slow part and the UI needs it.
     */
    fun sampleRange(
        startMs: Long = 0L,
        endMs: Long = durationMs,
        fps: Int = ANALYSIS_FPS,
        onProgress: (Float) -> Unit = {}
    ): List<AutoFramePlanner.Sample> {
        val times = sampleTimes(startMs, endMs, fps)
        if (times.isEmpty()) return emptyList()

        val out = ArrayList<AutoFramePlanner.Sample>(times.size)
        times.forEachIndexed { i, t ->
            sample(t)?.let { out.add(AutoFramePlanner.Sample(t, it)) }
            if (i % 4 == 0 || i == times.size - 1) onProgress(i.toFloat() / (times.size - 1).toFloat())
        }
        return out
    }

    fun release() {
        runCatching { retriever.release() }
    }

    private fun toLuma(bitmap: Bitmap): LumaFrame {
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
        return LumaFrame(out, w, h)
    }

    companion object {
        /** Enough to see a moving subject, cheap enough per sample. */
        const val ANALYSIS_WIDTH = 320

        /** 10 fps: cuts and subject motion are both resolved at this rate. */
        const val ANALYSIS_FPS = 10

        /**
         * @return null when the file has no usable video metadata, so the caller can report
         *         "auto frame is not available for this clip" instead of reframing nonsense.
         */
        fun open(
            context: Context,
            uri: Uri,
            analysisWidth: Int = ANALYSIS_WIDTH
        ): VideoFrameSampler? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                val rawW = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val rawH = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (rawW <= 0 || rawH <= 0 || duration <= 0L) {
                    runCatching { retriever.release() }
                    return null
                }
                val rotated = rotation == 90 || rotation == 270
                VideoFrameSampler(
                    retriever = retriever,
                    durationMs = duration,
                    displayWidth = if (rotated) rawH else rawW,
                    displayHeight = if (rotated) rawW else rawH,
                    analysisWidth = analysisWidth,
                    rotationDegrees = rotation
                )
            } catch (t: Throwable) {
                runCatching { retriever.release() }
                null
            }
        }
    }
}
