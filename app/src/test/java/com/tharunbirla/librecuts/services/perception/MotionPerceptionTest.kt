package com.tharunbirla.librecuts.services.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the model-free half of auto frame. Pure Kotlin, so CI (`testDebugUnitTest`)
 * executes them on the runner — no device, no footage.
 *
 * The test that matters most is [globalPanAloneIsNotMotion]: it runs the same frames twice,
 * once with compensation on and once off, and asserts opposite answers. That is the check that
 * fails if compensation is broken, and broken compensation is how motion-based auto framing
 * ends up chasing the camera instead of the subject.
 */
class MotionPerceptionTest {

    private val w = 32
    private val h = 18

    /** Deterministic texture: a plain fill has no gradient, so a shifted frame would diff to 0. */
    private fun texture(seed: Long = 7L): LumaFrame {
        val data = FloatArray(w * h)
        var s = seed
        for (i in data.indices) {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            val r = ((s ushr 33).toInt() and 0x7FFFFFF) / 0x7FFFFFF.toFloat()
            data[i] = 0.25f + 0.5f * r
        }
        return LumaFrame(data, w, h)
    }

    /** A wholesale different frame — what a hard cut looks like. */
    private fun flat(value: Float): LumaFrame = LumaFrame(FloatArray(w * h) { value }, w, h)

    private fun shifted(src: LumaFrame, dx: Int, dy: Int, fill: Float = 0f): LumaFrame {
        val data = FloatArray(src.w * src.h) { fill }
        for (y in 0 until src.h) {
            for (x in 0 until src.w) {
                val sx = x - dx
                val sy = y - dy
                if (sx in 0 until src.w && sy in 0 until src.h) data[y * src.w + x] = src.at(sx, sy)
            }
        }
        return LumaFrame(data, src.w, src.h)
    }

    private fun withPatch(src: LumaFrame, left: Int, top: Int, size: Int, value: Float): LumaFrame {
        val data = src.data.copyOf()
        for (y in top until minOf(top + size, src.h)) {
            for (x in left until minOf(left + size, src.w)) data[y * src.w + x] = value
        }
        return LumaFrame(data, src.w, src.h)
    }

    @Test
    fun frameRejectsMismatchedBuffer() {
        var threw = false
        try {
            LumaFrame(FloatArray(5), 4, 4)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a wrong-sized buffer must fail loudly, not read past the end", threw)
    }

    @Test
    fun globalPanAloneIsNotMotion() {
        val prev = texture()
        val next = shifted(prev, dx = 2, dy = 0)

        val estimated = GlobalMotion.estimate(prev, next)
        assertEquals("the pan must be measured, not guessed", 2, estimated.dx)
        assertEquals(0, estimated.dy)

        assertNull(
            "with compensation, a pure camera pan leaves no subject",
            MotionSaliency.detect(prev, next, compensate = true)
        )
        assertNotNull(
            "without compensation the same frames look like a huge subject — this is the " +
                    "failure compensation exists to prevent",
            MotionSaliency.detect(prev, next, compensate = false)
        )
    }

    @Test
    fun movingSubjectIsFoundAndLocated() {
        val prev = texture()
        val next = withPatch(prev, left = 14, top = 7, size = 4, value = 0.98f)

        val blob = MotionSaliency.detect(prev, next)
        assertNotNull("a bright moving patch must be detected", blob)
        val region = blob!!.region
        assertEquals("blob centre x should be the patch centre", 0.5f, region.centerX, 0.06f)
        assertEquals("blob centre y should be the patch centre", 0.5f, region.centerY, 0.06f)
        assertTrue("the blob must sit inside the injected patch", region.width in 0.05f..0.30f)
        assertTrue("solid patch means a dense blob", blob.energy > 0.5f)
    }

    @Test
    fun staticFramesProduceNoSubject() {
        val a = texture()
        val b = LumaFrame(a.data.copyOf(), w, h)
        assertNull("nothing moved, nothing to follow", MotionSaliency.detect(a, b))
    }

    @Test
    fun shotCutsAreSeparated_fromSmoothMotion() {
        val a = texture(seed = 1L)
        val b = flat(0.95f)
        val frames = listOf(a, LumaFrame(a.data.copyOf(), w, h), b, LumaFrame(b.data.copyOf(), w, h), a)

        val bounds = ShotDetector.boundaryIndices(frames)
        assertEquals("cuts are where the frame changes wholesale", listOf(0, 2, 4), bounds)

        val shots = ShotDetector.shots(frames)
        assertEquals(3, shots.size)
        assertEquals(0, shots[0].startIndex)
        assertEquals(1, shots[0].endIndex)
        assertEquals(2, shots[1].startIndex)
        assertEquals(4, shots[2].startIndex)

        // A gentle gradient between two samples must NOT read as a cut.
        val near = LumaFrame(FloatArray(w * h) { i -> a.data[i] + 0.05f }, w, h)
        assertEquals(listOf(0), ShotDetector.boundaryIndices(listOf(a, near)))
    }

    @Test
    fun hysteresisIgnoresASingleBlip_andAdoptsAPersistentSubject() {
        val selector = SubjectSelector(minHold = 3)
        val here = MotionSaliency.Blob(Region(0.40f, 0.40f, 0.10f, 0.10f), 0.9f, 0.01f)
        val there = MotionSaliency.Blob(Region(0.70f, 0.40f, 0.10f, 0.10f), 0.9f, 0.01f)

        assertNull("one sample is not evidence", selector.accept(here, shot = 0))
        assertNull(selector.accept(here, shot = 0))
        assertNotNull("three consecutive samples lock on", selector.accept(here, shot = 0))
        assertEquals(1, selector.targetSwitches)

        // A lone blip elsewhere must not steal the frame...
        val held = selector.accept(there, shot = 0)
        assertEquals("the target must not move on a single sample", 0.45f, held!!.centerX, 0.01f)
        assertEquals(1, selector.targetSwitches)

        // ...but a persistent one must.
        selector.accept(there, shot = 0)
        val switched = selector.accept(there, shot = 0)
        assertEquals("a persistent subject is adopted", 0.75f, switched!!.centerX, 0.01f)
        assertEquals(2, selector.targetSwitches)

        // Losing the subject holds the last confirmed region instead of snapping away.
        val lost = selector.accept(null, shot = 0)
        assertEquals(0.75f, lost!!.centerX, 0.01f)

        // A shot change clears the target: the next subject is unrelated to the old one.
        assertNull("a new shot starts with no target", selector.accept(here, shot = 1))
    }
}
