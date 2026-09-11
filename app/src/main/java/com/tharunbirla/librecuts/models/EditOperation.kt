package com.tharunbirla.librecuts.models

import android.net.Uri
import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Sealed class representing all possible edit operations that can be applied to a video.
 * Each operation is immutable and stores only the necessary parameters to render the effect.
 * Operations are applied in order during the final export process.
 */
sealed class EditOperation : Serializable {
    
    /**
     * Trim operation: Cuts the video from startMs to endMs.
     * Uses FFmpeg's -ss and -to flags with copy codec for fast processing.
     */
    data class Trim(
        val startMs: Long,
        val endMs: Long,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(startMs >= 0) { "Start time cannot be negative" }
            require(endMs > startMs) { "End time must be greater than start time" }
        }
    }
    
    /**
     * Speed operation for the main video.
     */
    data class SpeedMain(
        val speed: Float,
        val proxyUri: Uri? = null,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()
    
    /**
     * Reverse operation for the main video.
     */
    data class ReverseMain(
        val isReversed: Boolean,
        val proxyUri: Uri? = null,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()
    
    /**
     * Mirror operation for the main video.
     */
    data class MirrorMain(
        val isMirrored: Boolean,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()
    
    /**
     * Crop operation: Crops video to a specified aspect ratio.
     * Supported aspects: "16:9", "9:16", "1:1"
     * Uses FFmpeg's crop filter with video re-encoding.
     */
    data class Crop(
        val aspectRatio: String,
        val xFraction: Float = 0f,
        val yFraction: Float = 0f,
        val wFraction: Float = 1f,
        val hFraction: Float = 1f,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(aspectRatio in listOf("16:9", "9:16", "1:1", "Custom")) { 
                "Unsupported aspect ratio: $aspectRatio" 
            }
        }
    }
    
    /**
     * Text overlay operation: Adds customizable text to the video at specified position and size.
     * Supports 7 predefined positions (Bottom Right, Top Right, Top Left, Bottom Left, Center Bottom, Center Top, Center Align)
     * Uses FFmpeg's drawtext filter.
     */
    data class KeyframePoint(
        val timeMs: Long,
        val valueX: Float,
        val valueY: Float = 0f,
        val interpolationType: String = "linear",
        /**
         * Tracker confidence for this sample (0 = the subject was lost and the position was
         * bridged between two confident samples, 1 = a clean match).
         *
         * Gson builds objects without running Kotlin defaults, so a project saved before
         * this field existed deserializes it as 0f; read it through
         * [effectiveConfidence] rather than directly.
         */
        val confidence: Float = 1f
    ) : Serializable {
        /** Confidence to act on: 0 means "unknown" for paths saved by older builds. */
        val effectiveConfidence: Float get() = if (confidence <= 0f) 1f else confidence
    }

    /**
     * Object tracking (DaVinci-Resolve-style tracker, simplified).
     *
     * The user marks the subject with a resizable rectangle; the tracker records that
     * subject's centre through the clip; on export the clip is panned (and, if needed,
     * zoomed) so the subject stays in the middle of the frame.
     *
     * This is NOT a crop: the output size never changes and the canvas is never cut.
     * The frame is transformed inside its existing bounds — the window is a full-canvas
     * view of the clip, so keeping the subject centred is pure pan + zoom.
     */
    data class TrackObject(
        /** Marker rectangle, relative to the display-oriented frame (0..1). */
        val patternLeft: Float,
        val patternTop: Float,
        val patternWidth: Float,
        val patternHeight: Float,
        /** Subject centre (relative 0..1) sampled over the clip's timeline. */
        val path: List<KeyframePoint>,
        /** 0 = auto (smallest zoom that keeps the canvas covered), else a fixed 1.0..3.0. */
        val zoom: Float = 0f,
        /**
         * Framing decision for this track (ratio, pan/zoom mode). Null on projects saved
         * before the reframe presets existed — read it through [reframeSpec].
         */
        val spec: ReframeSpec? = null,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {

        companion object {
            const val MAX_ZOOM = 3f
        }

        /**
         * The framing to apply, defaulting to the source canvas with an auto zoom/pan —
         * exactly the behaviour of the very first tracking builds.
         */
        fun reframeSpec(): ReframeSpec = spec ?: ReframeSpec.ORIGINAL

        /** Null-safe: projects saved before this op existed deserialize `path` as null. */
        fun hasPath(): Boolean {
            val p: List<KeyframePoint>? = path
            return p != null && p.size >= 2
        }

        /** Interpolated subject centre at [timeMs] (project timeline), relative coords. */
        fun centerAt(timeMs: Long): Pair<Float, Float> {
            val p: List<KeyframePoint>? = path
            if (p.isNullOrEmpty()) {
                return Pair(patternLeft + patternWidth / 2f, patternTop + patternHeight / 2f)
            }
            val sorted = p.sortedBy { it.timeMs }
            if (timeMs <= sorted.first().timeMs) return Pair(sorted.first().valueX, sorted.first().valueY)
            if (timeMs >= sorted.last().timeMs) return Pair(sorted.last().valueX, sorted.last().valueY)
            for (i in 0 until sorted.size - 1) {
                val k1 = sorted[i]
                val k2 = sorted[i + 1]
                if (timeMs >= k1.timeMs && timeMs <= k2.timeMs) {
                    val progress = (timeMs - k1.timeMs).toFloat() / (k2.timeMs - k1.timeMs).toFloat()
                    return Pair(
                        k1.valueX + progress * (k2.valueX - k1.valueX),
                        k1.valueY + progress * (k2.valueY - k1.valueY)
                    )
                }
            }
            return Pair(sorted.last().valueX, sorted.last().valueY)
        }

        /**
         * Smallest zoom at which a canvas-sized window can follow [path] without ever
         * leaving the scaled clip (no black bars, no cut canvas).
         *
         * A frame scaled by S can pan +-(S-1)/2 of its width, so a subject that strays
         * `d` (relative) from the centre needs S >= 1 / (1 - 2d). Each axis is clamped
         * independently by the render code, so the larger of the two demands wins.
         */
        fun autoZoom(): Float {
            if (!hasPath()) return 1f
            var dx = 0f
            var dy = 0f
            val p: List<KeyframePoint>? = path
            p?.forEach { k ->
                dx = maxOf(dx, kotlin.math.abs(k.valueX - 0.5f))
                dy = maxOf(dy, kotlin.math.abs(k.valueY - 0.5f))
            }
            val deviation = maxOf(dx, dy)
            if (deviation <= 0.001f) return 1f
            return (1f / (1f - 2f * deviation)).coerceIn(1f, MAX_ZOOM)
        }

        /** Zoom actually used at render time: the manual value, or the auto-derived one. */
        fun appliedZoom(): Float = if (zoom <= 0f) autoZoom() else zoom.coerceIn(1f, MAX_ZOOM)
    }

    data class AddText(
        val text: String,
        val fontSize: Int,
        val position: TextPosition,
        val relativeX: Float? = null,
        val relativeY: Float? = null,
        val color: String = "#FFFFFF",
        val startTimeMs: Long? = null,
        val endTimeMs: Long? = null,
        val id: String = System.nanoTime().toString(),
        val fontPath: String? = null,
        val opacity: Float = 1.0f,
        val borderThickness: Int = 0,
        val borderColor: String = "#000000",
        val textAlign: String = "center",
        val letterSpacing: Float = 0f,
        val lineSpacing: Float = 0f,
        val positionKeyframes: List<KeyframePoint> = emptyList(),
        val opacityKeyframes: List<KeyframePoint> = emptyList()
    ) : EditOperation() {
        init {
            require(text.isNotEmpty()) { "Text cannot be empty" }
            require(fontSize > 0) { "Font size must be positive" }
            relativeX?.let { require(it in 0f..1f) { "relativeX must be in 0.0..1.0" } }
            relativeY?.let { require(it in 0f..1f) { "relativeY must be in 0.0..1.0" } }
            require(opacity in 0f..1f) { "opacity must be in 0.0..1.0" }
        }

        /** True when this text was placed via drag-and-drop (WYSIWYG coordinates). */
        fun hasCustomPosition(): Boolean = relativeX != null && relativeY != null
    }
    
    enum class MaskShape { NONE, SPLIT, SHUTTER, ELLIPSE, RECTANGLE, HEART, STAR }
    
    data class MaskConfig(
        val shape: MaskShape = MaskShape.NONE,
        val relativeX: Float = 0.5f,
        val relativeY: Float = 0.5f,
        val relativeWidth: Float = 0.5f,
        val relativeHeight: Float = 0.5f,
        val rotationAngle: Float = 0f,
        val isInverted: Boolean = false,
        val feather: Float = 0f,
        val positionKeyframes: List<KeyframePoint> = emptyList(),
        val sizeKeyframes: List<KeyframePoint> = emptyList(),
        val rotationKeyframes: List<KeyframePoint> = emptyList(),
        val featherKeyframes: List<KeyframePoint> = emptyList()
    ) : Serializable {
        fun getInterpolatedPos(timeMs: Long): Pair<Float, Float> {
            if (positionKeyframes.isEmpty()) return Pair(relativeX, relativeY)
            val sorted = positionKeyframes.sortedBy { it.timeMs }
            if (timeMs <= sorted.first().timeMs) return Pair(sorted.first().valueX, sorted.first().valueY)
            if (timeMs >= sorted.last().timeMs) return Pair(sorted.last().valueX, sorted.last().valueY)
            for (i in 0 until sorted.size - 1) {
                val k1 = sorted[i]
                val k2 = sorted[i + 1]
                if (timeMs >= k1.timeMs && timeMs <= k2.timeMs) {
                    val progress = (timeMs - k1.timeMs).toFloat() / (k2.timeMs - k1.timeMs)
                    return Pair(
                        k1.valueX + progress * (k2.valueX - k1.valueX),
                        k1.valueY + progress * (k2.valueY - k1.valueY)
                    )
                }
            }
            return Pair(relativeX, relativeY)
        }

        fun getInterpolatedSize(timeMs: Long): Float {
            val defaultScale = ((relativeWidth + relativeHeight) / 2f * 200f).coerceIn(10f, 200f)
            if (sizeKeyframes.isEmpty()) return defaultScale
            val sorted = sizeKeyframes.sortedBy { it.timeMs }
            if (timeMs <= sorted.first().timeMs) return sorted.first().valueX
            if (timeMs >= sorted.last().timeMs) return sorted.last().valueX
            for (i in 0 until sorted.size - 1) {
                val k1 = sorted[i]
                val k2 = sorted[i + 1]
                if (timeMs >= k1.timeMs && timeMs <= k2.timeMs) {
                    val progress = (timeMs - k1.timeMs).toFloat() / (k2.timeMs - k1.timeMs)
                    return k1.valueX + progress * (k2.valueX - k1.valueX)
                }
            }
            return defaultScale
        }

        fun getInterpolatedRotation(timeMs: Long): Float {
            if (rotationKeyframes.isEmpty()) return rotationAngle
            val sorted = rotationKeyframes.sortedBy { it.timeMs }
            if (timeMs <= sorted.first().timeMs) return sorted.first().valueX
            if (timeMs >= sorted.last().timeMs) return sorted.last().valueX
            for (i in 0 until sorted.size - 1) {
                val k1 = sorted[i]
                val k2 = sorted[i + 1]
                if (timeMs >= k1.timeMs && timeMs <= k2.timeMs) {
                    val progress = (timeMs - k1.timeMs).toFloat() / (k2.timeMs - k1.timeMs)
                    return k1.valueX + progress * (k2.valueX - k1.valueX)
                }
            }
            return rotationAngle
        }

        fun getInterpolatedFeather(timeMs: Long): Float {
            if (featherKeyframes.isEmpty()) return feather
            val sorted = featherKeyframes.sortedBy { it.timeMs }
            if (timeMs <= sorted.first().timeMs) return sorted.first().valueX
            if (timeMs >= sorted.last().timeMs) return sorted.last().valueX
            for (i in 0 until sorted.size - 1) {
                val k1 = sorted[i]
                val k2 = sorted[i + 1]
                if (timeMs >= k1.timeMs && timeMs <= k2.timeMs) {
                    val progress = (timeMs - k1.timeMs).toFloat() / (k2.timeMs - k1.timeMs)
                    return k1.valueX + progress * (k2.valueX - k1.valueX)
                }
            }
            return feather
        }

        fun evaluatedAt(timeMs: Long): MaskConfig {
            if (positionKeyframes.isEmpty() && sizeKeyframes.isEmpty() && rotationKeyframes.isEmpty() && featherKeyframes.isEmpty()) {
                return this
            }
            val pos = getInterpolatedPos(timeMs)
            val sizePercent = getInterpolatedSize(timeMs)
            val relScale = sizePercent / 200f
            val rot = getInterpolatedRotation(timeMs)
            val feath = getInterpolatedFeather(timeMs)
            return copy(
                relativeX = pos.first,
                relativeY = pos.second,
                relativeWidth = relScale,
                relativeHeight = relScale,
                rotationAngle = rot,
                feather = feath
            )
        }
    }

    
    data class MergeItem(
        val uri: Uri,
        val durationMs: Long,
        val trimStartMs: Long = 0L,
        val trimEndMs: Long = durationMs,
        val speed: Float = 1.0f,
        val proxyUri: Uri? = null,
        val scrubProxyUri: Uri? = null,
        /**
         * Playback-only proxy of this clip with the reframe (pan/zoom) already baked in.
         *
         * It exists so applying a reframe is visible on the timeline immediately instead of
         * only after export. Export deliberately ignores it and re-applies the filter to the
         * original source, so the transform is never applied twice.
         */
        val reframeProxyUri: Uri? = null,
        val isReversed: Boolean = false,
        val isMirrored: Boolean = false,
        val maskConfig: MaskConfig = MaskConfig(),
        val isImage: Boolean = false
    ) : Serializable {
        val trimmedDurationMs: Long
            get() = ((trimEndMs - trimStartMs) / speed).toLong()
    }

    /**
     * Merge operation: Concatenates multiple videos with the current video.
     * Uses FFmpeg's concat demuxer for fast concatenation.
     */
    data class Merge(
        val items: List<MergeItem>,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(items.isNotEmpty()) { "Must provide at least one video to merge" }
        }

        // Backward compatibility property
        val videoUris: List<Uri> get() = items.map { it.uri }
    }

    /** Mask configuration for the main track video base (index 0) */
    data class MaskMain(
        val maskConfig: MaskConfig,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()
    
    /**
     * Mute audio operation: Removes or mutes the audio track.
     * Uses FFmpeg's -an flag.
     */
    data class MuteAudio(
        val id: String = System.nanoTime().toString()
    ) : EditOperation()


    /**
     * Transition operation: Adds a transition effect between two consecutive videos.
     * index: The index of the first video in the sequence. (0 = base video, 1 = merge item 0, etc.)
     */
    data class Transition(
        val index: Int,
        @SerializedName("transitionType") val type: String,
        val durationMs: Long = 1000L,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()

    /**
     * Mute clip operation: Mutes/unmutes a specific clip index.
     */
    data class MuteClip(
        val index: Int,
        val isMuted: Boolean,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()

    /**
     * Color filter (LUT) operation: Applies a preset color grade filter to a specific clip index.
     */
    data class ColorFilter(
        val index: Int,
        val filterName: String,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()

    
    /**
     * Add background audio operation: Overlays an audio file over the video.
     * If removeOriginalAudio is true, the original audio is removed.
     * Uses FFmpeg's -i and audio filters.
     */
    data class AddBackgroundAudio(
        val audioUri: Uri,
        val removeOriginalAudio: Boolean = false,
        val volume: Float = 1.0f,
        val internalStartMs: Long = 0L,
        val internalEndMs: Long = -1L,
        val startTimeMs: Long? = null,
        val endTimeMs: Long? = null,
        val originalDurationMs: Long = 0L,
        val extractedFromSegmentIndex: Int? = null,
        val beats: List<Long> = emptyList(),
        val ducking: Boolean = false,
        val fadeInDurationMs: Long = 0L,
        val fadeOutDurationMs: Long = 0L,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(volume in 0f..2f) { "Volume must be in 0.0..2.0" }
            require(internalStartMs >= 0) { "Internal start time cannot be negative" }
        }
    }

    /**
     * Image overlay operation: Adds an image to the video at specified position, size, and rotation angle.
     * Uses FFmpeg's overlay filter.
     */
    data class AddImageOverlay(
        val imageUri: Uri,
        val relativeX: Float,
        val relativeY: Float,
        val relativeWidth: Float,
        val relativeHeight: Float,
        val rotationAngle: Float,
        val startTimeMs: Long? = null,
        val endTimeMs: Long? = null,
        val id: String = System.nanoTime().toString(),
        val fileDurationMs: Long? = null,
        val isLooping: Boolean = true,
        val chromaKeyColor: String? = null,
        val chromaKeySimilarity: Float = 0.1f,
        val opacity: Float = 1.0f,
        val isMirrored: Boolean = false,
        val positionKeyframes: List<KeyframePoint> = emptyList(),
        val opacityKeyframes: List<KeyframePoint> = emptyList(),
        val speedKeyframes: List<KeyframePoint> = emptyList(),
        val maskConfig: MaskConfig = MaskConfig()
    ) : EditOperation()

    data class AddSubtitles(
        val subtitlesUri: Uri,
        val srtContent: String,
        val fileName: String,
        val cues: List<SubtitleCue>,
        val color: String = "#FFFFFF",
        val backgroundColor: String = "none",
        val fontSize: Int = 22,
        val position: TextPosition = TextPosition.BOTTOM_CENTER,
        val relativeX: Float? = null,
        val relativeY: Float? = null,
        val fontPath: String? = null,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        fun hasCustomPosition(): Boolean = relativeX != null && relativeY != null
    }

    /**
     * Adjust operation: Adjusts video properties for a specific clip index.
     * All properties range from -100 to 100, default is 0.
     */
    data class Adjust(
        val index: Int,
        val brightness: Int = 0,
        val contrast: Int = 0,
        val warmth: Int = 0,
        val shadow: Int = 0,
        val highlights: Int = 0,
        val saturation: Int = 0,
        val exposure: Int = 0,
        val sharpen: Int = 0,
        val vignette: Int = 0,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        fun isDefault(): Boolean {
            return brightness == 0 &&
                   contrast == 0 &&
                   warmth == 0 &&
                   shadow == 0 &&
                   highlights == 0 &&
                   saturation == 0 &&
                   exposure == 0 &&
                   sharpen == 0 &&
                   vignette == 0
        }
    }

    /**
     * Canvas background operation: Sets the padding style for clips that do not match the primary aspect ratio.
     */
    data class CanvasBackground(
        @SerializedName("backgroundType") val type: BackgroundType = BackgroundType.COLOR,
        val colorHex: String = "#000000",
        val imageUri: Uri? = null,
        val blurRadius: Int = 20,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        enum class BackgroundType { COLOR, IMAGE, BLUR }
    }
}

data class SubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
) : java.io.Serializable


/**
 * Enum for text positioning options in video overlays.
 * Maps to FFmpeg drawtext position parameters.
 */
enum class TextPosition(val ffmpegParam: String) : Serializable {
    BOTTOM_RIGHT("x=w-tw:y=h-th"),
    TOP_RIGHT("x=w-tw:y=0"),
    TOP_LEFT("x=0:y=0"),
    BOTTOM_LEFT("x=0:y=h-th"),
    CENTER_BOTTOM("x=(w-text_w)/2:y=h-th"),
    CENTER_TOP("x=(w-text_w)/2:y=0"),
    CENTER("x=(w-text_w)/2:y=(h-text_h)/2"),
    TOP_CENTER("x=(w-text_w)/2:y=0"),
    CENTER_LEFT("x=0:y=(h-text_h)/2"),
    CENTER_RIGHT("x=w-tw:y=(h-text_h)/2"),
    BOTTOM_CENTER("x=(w-text_w)/2:y=h-th");
    
    companion object {
        fun fromLabel(label: String): TextPosition = when (label) {
            "Bottom Right" -> BOTTOM_RIGHT
            "Top Right" -> TOP_RIGHT
            "Top Left" -> TOP_LEFT
            "Bottom Left" -> BOTTOM_LEFT
            "Center Bottom" -> CENTER_BOTTOM
            "Center Top" -> CENTER_TOP
            "Center Align" -> CENTER
            "Top Center" -> TOP_CENTER
            "Center Left" -> CENTER_LEFT
            "Center Right" -> CENTER_RIGHT
            "Bottom Center" -> BOTTOM_CENTER
            else -> CENTER
        }
        
        fun labels() = listOf(
            "Top Left",
            "Top Center",
            "Top Right",
            "Center Left",
            "Center Align",
            "Center Right",
            "Bottom Left",
            "Bottom Center",
            "Bottom Right"
        )
    }
}

val EditOperation.id: String
    get() = when (this) {
        is EditOperation.Trim -> id
        is EditOperation.SpeedMain -> id
        is EditOperation.ReverseMain -> id
        is EditOperation.MirrorMain -> id
        is EditOperation.MaskMain -> id
        is EditOperation.Crop -> id
        is EditOperation.TrackObject -> id
        is EditOperation.AddText -> id
        is EditOperation.Merge -> id
        is EditOperation.MuteAudio -> id
        is EditOperation.Transition -> id
        is EditOperation.MuteClip -> id
        is EditOperation.ColorFilter -> id
        is EditOperation.AddBackgroundAudio -> id
        is EditOperation.AddImageOverlay -> id
        is EditOperation.AddSubtitles -> id
        is EditOperation.Adjust -> id
        is EditOperation.CanvasBackground -> id
    }
