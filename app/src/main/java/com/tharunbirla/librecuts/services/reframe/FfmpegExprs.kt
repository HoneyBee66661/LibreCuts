package com.tharunbirla.librecuts.services.reframe

import java.util.Locale

/**
 * ffmpeg expression helpers for the reframe module.
 *
 * Kept separate from the ones inside `VideoEditingViewModel` on purpose: those drive text,
 * masks and overlays and are exercised by the rest of the app, so the reframe path gets its
 * own small implementation rather than sharing a helper that a framing change could break.
 */
object FfmpegExprs {

    /**
     * Piecewise-linear interpolation of a time series as a single ffmpeg expression.
     *
     * ffmpeg evaluates the expression per frame, so the nested `if(lt(...))` chain below
     * becomes a "pick the segment that contains t, then interpolate inside it" lookup.
     * Values are formatted with [Locale.US] because a comma decimal separator (id_ID, de_DE)
     * would be read by ffmpeg as an argument separator and break the whole filtergraph.
     */
    fun piecewiseLinear(
        values: List<Pair<Double, Float>>,
        defaultValue: Float,
        timeVar: String = "t"
    ): String {
        if (values.isEmpty()) return format(defaultValue)
        val sorted = values.sortedBy { it.first }
        if (sorted.size == 1) return format(sorted[0].second)

        // Start from the last value: every branch that fails evaluates to it.
        var expr = format(sorted.last().second)
        for (i in sorted.size - 2 downTo 0) {
            val t1 = sorted[i].first
            val t2 = sorted[i + 1].first
            val v1 = sorted[i].second
            val v2 = sorted[i + 1].second
            val span = t2 - t1
            val segment = if (span > 0.0) {
                "${format(v1)}+(${format(v2 - v1)})*($timeVar-${format(t1)})/${format(span)}"
            } else {
                format(v1)
            }
            expr = "if(lt($timeVar\\,$t2)\\,$segment\\,$expr)"
        }

        val firstTime = format(sorted.first().first)
        val firstValue = format(sorted.first().second)
        return "if(lt($timeVar\\,$firstTime)\\,$firstValue\\,$expr)"
    }

    /**
     * Locale-independent number formatting without scientific notation (ffmpeg cannot
     * parse `1.0E-4`).
     */
    fun format(value: Float): String = String.format(Locale.US, "%.6f", value)

    /** Same, for time values expressed in seconds. */
    fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
}
