package com.tharunbirla.librecuts.models

/**
 * Target canvas for a reframed clip.
 *
 * [ratio] is the exact aspect the reframe window must hold; the output resolution is the
 * delivery target of that platform. [ORIGINAL] keeps whatever the source already is,
 * which is the "just follow the subject, don't change my frame" case.
 */
enum class ReframeAspect(val ratio: Float, val outputWidth: Int, val outputHeight: Int) {
    /**
     * No output frame chosen — the default. The track is still stored on the timeline, but
     * the canvas is left exactly as it was, so nothing is rendered and nothing is upscaled.
     */
    NONE(0f, 0, 0),
    ORIGINAL(0f, 0, 0),
    TIKTOK_9_16(9f / 16f, 1080, 1920),
    YOUTUBE_16_9(16f / 9f, 1920, 1080),
    /** Instagram reel: same delivery shape as TikTok, listed separately for the UI. */
    IG_REEL_9_16(9f / 16f, 1080, 1920),
    /** Instagram feed portrait: 4:5. */
    IG_FEED_4_5(4f / 5f, 1080, 1350),
    /** Instagram square post: 1:1. */
    IG_SQUARE_1_1(1f, 1080, 1080);

    val isOriginal: Boolean get() = this == ORIGINAL

    /** True when the user has not picked an output frame: render nothing. */
    val isNone: Boolean get() = this == NONE
}

/**
 * How the reframe window is allowed to move.
 *
 * [PAN_ONLY] keeps the largest ratio-correct window the source allows, so on a landscape
 * source cropped to 9:16 the full height is kept and the frame slides left/right only —
 * no zoom, no vertical drift, which is what makes a TikTok reframe feel stable.
 *
 * [ZOOM_PAN] shrinks the window by a zoom factor so the frame can both zoom and pan,
 * which is how a 16:9 clip keeps a moving subject centred on a 16:9 canvas.
 */
enum class ReframeMode { PAN_ONLY, ZOOM_PAN }

/**
 * The framing decision, stored on a tracking operation.
 *
 * This is data only — the planning lives in
 * [com.tharunbirla.librecuts.services.reframe.ReframePlanner], so the tracker keeps
 * answering "where is the subject" while this answers "what should the frame do about it".
 */
data class ReframeSpec(
    val aspect: ReframeAspect = ReframeAspect.ORIGINAL,
    val mode: ReframeMode = ReframeMode.ZOOM_PAN,
    /** 0 = auto (smallest zoom that keeps every subject position reachable), else 1..[MAX_ZOOM]. */
    val zoom: Float = 0f
) {
    companion object {
        const val MAX_ZOOM = 3f

        /** Slide the frame sideways on a 9:16 canvas: full height kept, no zoom. */
        val TIKTOK = ReframeSpec(ReframeAspect.TIKTOK_9_16, ReframeMode.PAN_ONLY, 0f)

        /** Follow the subject on a 16:9 canvas with an auto zoom/pan combination. */
        val YOUTUBE = ReframeSpec(ReframeAspect.YOUTUBE_16_9, ReframeMode.ZOOM_PAN, 0f)

        /** Keep the source canvas, pan/zoom only as far as needed to follow the subject. */
        val ORIGINAL = ReframeSpec(ReframeAspect.ORIGINAL, ReframeMode.ZOOM_PAN, 0f)
    }

    /** True when the zoom should be derived from the path instead of a fixed value. */
    val isAutoZoom: Boolean get() = zoom <= 0.001f
}
