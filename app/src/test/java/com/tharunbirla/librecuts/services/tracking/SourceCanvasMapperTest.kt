package com.tharunbirla.librecuts.services.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the source ↔ canvas conversion. Pure Kotlin, so CI runs them.
 *
 * The case that matters is the *cropped* one: on a full-frame clip the two spaces coincide and
 * every implementation looks correct, which is exactly how the mixed-space bug survives.
 */
class SourceCanvasMapperTest {

    private val eps = 1e-4f

    private fun assertPair(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, eps)
        assertEquals(expected.second, actual.second, eps)
    }

    @Test
    fun anUncroppedClipIsTheIdentity() {
        assertPair(Pair(0.25f, 0.75f), SourceCanvasMapper.toCanvas(0.25f, 0.75f, SourceCanvasMapper.Crop.FULL))
        assertPair(Pair(0.25f, 0.75f), SourceCanvasMapper.toSource(0.25f, 0.75f, SourceCanvasMapper.Crop.FULL))
    }

    @Test
    fun aHalfCropRemapsBothDirections() {
        // Right half of the source is the whole canvas.
        val crop = SourceCanvasMapper.Crop(x = 0.5f, y = 0f, width = 0.5f, height = 1f)
        // Source centre 0.75 is the centre of that half → canvas 0.5.
        assertPair(Pair(0.5f, 0.5f), SourceCanvasMapper.toCanvas(0.75f, 0.5f, crop))
        // Canvas edge maps back to the source edge.
        assertPair(Pair(0.5f, 0f), SourceCanvasMapper.toSource(0f, 0f, crop))
        assertPair(Pair(1f, 1f), SourceCanvasMapper.toSource(1f, 1f, crop))
    }

    @Test
    fun theTwoDirectionsAreInverses() {
        val crop = SourceCanvasMapper.Crop(x = 0.2f, y = 0.1f, width = 0.6f, height = 0.8f)
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { v ->
            val source = SourceCanvasMapper.toSource(v, v, crop)
            assertPair(Pair(v, v), SourceCanvasMapper.toCanvas(source.first, source.second, crop))
        }
    }

    @Test
    fun canvasIsClampedButSourceIsNot() {
        val crop = SourceCanvasMapper.Crop(x = 0.5f, y = 0f, width = 0.5f, height = 1f)
        // A subject outside the crop pins to the edge instead of being stored out of range.
        assertPair(Pair(0f, 1f), SourceCanvasMapper.toCanvas(0.1f, 1.4f, crop))
        // The marker, however, may sit partially outside the visible region.
        assertPair(Pair(0.4f, -0.2f), SourceCanvasMapper.toSource(-0.2f, -0.2f, crop))
    }

    @Test
    fun aNullOrDefaultCarryingCropFallsBackToIdentity() {
        assertPair(
            Pair(0.3f, 0.4f),
            SourceCanvasMapper.toCanvas(0.3f, 0.4f, SourceCanvasMapper.cropOf(null, null, null, null))
        )
    }

    @Test
    fun aDegenerateCropCannotBeInvertedByZero() {
        // A crop saved as 0 width (corrupt or hand-edited) must not divide by zero.
        val crop = SourceCanvasMapper.cropOf(0f, 0f, 0f, 0f)
        assertEquals(SourceCanvasMapper.MIN_CROP, crop.width, eps)
        assertEquals(SourceCanvasMapper.MIN_CROP, crop.height, eps)
        val mapped = SourceCanvasMapper.toCanvas(1f, 1f, crop)
        assertTrue(mapped.first.isFinite() && mapped.second.isFinite())
    }
}
