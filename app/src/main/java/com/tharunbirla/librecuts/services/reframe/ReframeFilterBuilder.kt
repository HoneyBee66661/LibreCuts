package com.tharunbirla.librecuts.services.reframe

import kotlin.math.max

/**
 * Turns a [ReframePlan] into ffmpeg filter code.
 *
 * The stage has exactly two parts and never changes the canvas size of the clip it runs
 * on, which is what keeps black bars impossible:
 *
 *  1. `crop` with a window whose width/height are fractions of the *current* frame and
 *     whose x/y are time expressions, so the window slides while the frame plays. Width
 *     and height are constant, so ffmpeg evaluates them once; x/y are re-evaluated per
 *     frame. Both are `clip()`ed to the frame, so the window can never leave the picture.
 *  2. `scale` onto the delivery canvas of the chosen preset (e.g. 1080x1920 for 9:16),
 *     plus `setsar=1` so players don't apply a stale sample aspect ratio.
 */
object ReframeFilterBuilder {

    /** Long side used for preview proxies: they exist to be fast, not faithful. */
    private const val PROXY_LONG_SIDE = 1280

    /**
     * @param outputWidth/outputHeight override the plan's canvas (used for small proxies).
     * @return the filter stage, or null when the plan needs no rendering at all.
     */
    fun build(plan: ReframePlan, outputWidth: Int = 0, outputHeight: Int = 0): String? {
        if (plan.isNoOp) return null
        val outW = if (outputWidth > 0) outputWidth else plan.outWidth
        val outH = if (outputHeight > 0) outputHeight else plan.outHeight

        val windowW = "trunc(iw*${FfmpegExprs.format(plan.windowWidthFraction)}/2)*2"
        val windowH = "trunc(ih*${FfmpegExprs.format(plan.windowHeightFraction)}/2)*2"

        val xExpr = "clip((${centreExpr(plan, useY = false)})*iw-($windowW)/2\\,0\\,iw-($windowW))"
        val yExpr = "clip((${centreExpr(plan, useY = true)})*ih-($windowH)/2\\,0\\,ih-($windowH))"

        return "crop=w=$windowW:h=$windowH:x='$xExpr':y='$yExpr'," +
                "scale=$outW:$outH:flags=bicubic,setsar=1"
    }

    /**
     * Standalone per-clip render (the timeline proxy).
     *
     * `-ss` before `-i` keeps the seek fast, and `-t` bounds the output to the clip. The
     * filter's time expressions are relative to the trimmed clip, so callers must shift the
     * plan's keyframes by the clip's start offset (see [ReframePlanner.plan]).
     */
    fun buildProxyCommand(
        sourcePath: String,
        filter: String,
        startMs: Long,
        durationMs: Long,
        outputPath: String
    ): String {
        val startSecs = String.format(java.util.Locale.US, "%.3f", startMs / 1000.0)
        val durSecs = String.format(java.util.Locale.US, "%.3f", durationMs / 1000.0)
        return "-y -ss $startSecs -t $durSecs -i \"$sourcePath\" -vf \"$filter\" " +
                "-c:v libx264 -preset ultrafast -crf 26 -pix_fmt yuv420p " +
                "-c:a aac -b:a 128k -movflags +faststart \"$outputPath\""
    }

    /** Downscaled canvas for a preview proxy, preserving the plan's aspect (even numbers). */
    fun proxyCanvas(plan: ReframePlan, longSide: Int = PROXY_LONG_SIDE): Pair<Int, Int> {
        if (plan.outWidth <= 0 || plan.outHeight <= 0) return Pair(0, 0)
        val longest = max(plan.outWidth, plan.outHeight).toFloat()
        val factor = if (longest > longSide) longSide / longest else 1f
        return Pair(
            even((plan.outWidth * factor).toInt()),
            even((plan.outHeight * factor).toInt())
        )
    }

    /** Window-centre expression for one axis: constant 0.5 when that axis is locked. */
    private fun centreExpr(plan: ReframePlan, useY: Boolean): String {
        val axis = if (useY) plan.windowHeightFraction else plan.windowWidthFraction
        if (axis >= 0.999f) return FfmpegExprs.format(0.5f)
        val values = plan.keyframes.map { k ->
            Pair(k.timeMs / 1000.0, if (useY) k.y else k.x)
        }
        return FfmpegExprs.piecewiseLinear(values, 0.5f, "t")
    }

    private fun even(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)
}
