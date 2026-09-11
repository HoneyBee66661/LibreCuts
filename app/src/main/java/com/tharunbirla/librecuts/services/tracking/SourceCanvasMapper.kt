package com.tharunbirla.librecuts.services.tracking

/**
 * The one place where a normalised coordinate crosses between the editor's two spaces.
 *
 * Two spaces exist and they are easy to confuse:
 *
 *  - **source space** — relative to the clip as decoded, display-oriented. This is what the
 *    tracker and the perception layer read and what they return. Rotation is already applied,
 *    so a portrait clip is described in the axes the user sees.
 *  - **canvas space** — relative to the *visible* region once the crop op has been applied.
 *    This is what the user draws on (the marker) and what the timeline stores.
 *
 * `canvas = (source - crop) / cropSize`, and the inverse `source = crop + canvas * cropSize`.
 * On an uncropped clip the two are the same and a mistake here is invisible; as soon as a crop
 * exists the axes silently disagree, which is why both directions live in one tested object
 * instead of being open-coded at each call site.
 *
 * Pure Kotlin: no Android, no models, so CI runs every rule below.
 */
object SourceCanvasMapper {

    /** The visible region of the source, in source-normalised units. */
    data class Crop(val x: Float, val y: Float, val width: Float, val height: Float) {
        companion object {
            /** No crop: source and canvas are the same space. */
            val FULL = Crop(0f, 0f, 1f, 1f)
        }
    }

    /** A crop smaller than this cannot be inverted usefully; clamp rather than divide by ~0. */
    const val MIN_CROP = 0.01f

    /**
     * Build a [Crop] from the fractions of a crop operation, tolerating nulls (an operation
     * deserialized from an older project can carry nulls where the constructor had defaults).
     */
    fun cropOf(x: Float?, y: Float?, width: Float?, height: Float?): Crop = Crop(
        x = x ?: 0f,
        y = y ?: 0f,
        width = (width ?: 1f).coerceAtLeast(MIN_CROP),
        height = (height ?: 1f).coerceAtLeast(MIN_CROP)
    )

    /**
     * Canvas (what the user draws on) → source (what the tracker reads).
     *
     * Not clamped: the marker may legitimately sit partially outside the crop.
     */
    fun toSource(canvasX: Float, canvasY: Float, crop: Crop): Pair<Float, Float> =
        Pair(crop.x + canvasX * crop.width, crop.y + canvasY * crop.height)

    /**
     * Source (what the tracker and perception return) → canvas.
     *
     * Clamped into 0..1: a track that leaves the cropped window would otherwise be stored as a
     * coordinate the timeline cannot express, and the pan would snap on the first frame.
     */
    fun toCanvas(sourceX: Float, sourceY: Float, crop: Crop): Pair<Float, Float> = Pair(
        ((sourceX - crop.x) / crop.width).coerceIn(0f, 1f),
        ((sourceY - crop.y) / crop.height).coerceIn(0f, 1f)
    )
}
