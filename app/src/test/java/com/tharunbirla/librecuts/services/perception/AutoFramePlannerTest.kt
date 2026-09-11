package com.tharunbirla.librecuts.services.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the perception → timeline adapter. Pure Kotlin, so CI runs them.
 *
 * The assertions are about *safety* as much as behaviour: an auto frame that invents a path
 * when nothing moved is worse than no auto frame, so the empty-path cases are first-class.
 */
class AutoFramePlannerTest {

    private val w = 32
    private val h = 18

    private fun texture(seed: Long = 7L): LumaFrame {
        val data = FloatArray(w * h)
        var s = seed
        for (i in data.indices) {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            data[i] = 0.25f + 0.5f * (((s ushr 33).toInt() and 0x7FFFFFF) / 0x7FFFFFF.toFloat())
        }
        return LumaFrame(data, w, h)
    }

    private fun flat(v: Float) = LumaFrame(FloatArray(w * h) { v }, w, h)

    private fun withPatch(src: LumaFrame, left: Int, top: Int, size: Int = 4, value: Float = 0.98f): LumaFrame {
        val data = src.data.copyOf()
        for (y in top until minOf(top + size, src.h)) {
            for (x in left until minOf(left + size, src.w)) data[y * src.w + x] = value
        }
        return LumaFrame(data, src.w, src.h)
    }

    private fun shifted(src: LumaFrame, dx: Int): LumaFrame {
        val data = FloatArray(src.w * src.h)
        for (y in 0 until src.h) {
            for (x in 0 until src.w) {
                val sx = x - dx
                if (sx in 0 until src.w) data[y * src.w + x] = src.at(sx, y)
            }
        }
        return LumaFrame(data, src.w, src.h)
    }

    private fun samples(frames: List<LumaFrame>, stepMs: Long = 100L) =
        frames.mapIndexed { i, f -> AutoFramePlanner.Sample(i * stepMs, f) }

    @Test
    fun aMovingSubjectBecomesAPath() {
        val base = texture()
        val frames = (0 until 9).map { i -> withPatch(base, left = 4 + 2 * i, top = 7) }

        val result = AutoFramePlanner.plan(samples(frames))

        assertTrue("a moving subject must produce a usable path", result.path.size >= 2)
        assertTrue("the path must march in the direction of travel",
            result.path.last().x > result.path.first().x)
        assertTrue("detected keyframes carry confidence",
            result.path.any { it.confidence > 0.1f })
        assertEquals("one shot, no cuts", 1, result.shots.size)
        assertEquals("the first sample has nothing to compare against", 8, result.analysed)
    }

    @Test
    fun aStaticSceneProducesNoPath() {
        val frames = (0 until 6).map { texture() }

        val result = AutoFramePlanner.plan(samples(frames))

        assertTrue("nothing moved: the planner must not invent a subject", result.isEmpty)
        assertEquals(0, result.path.size)
    }

    @Test
    fun aCameraPanIsNotASubject() {
        // The whole frame slides; without global-motion compensation this reads as one huge
        // moving subject and the auto frame would chase the camera.
        val base = texture()
        val frames = (0 until 7).map { i -> shifted(base, dx = 2 * i) }

        val result = AutoFramePlanner.plan(samples(frames))

        assertTrue("a pure pan must not become a subject", result.isEmpty)
    }

    @Test
    fun aCutStartsANewShotAndTheOldSubjectIsDropped() {
        val left = texture(seed = 1L)
        val right = texture(seed = 2L)
        val frames = (0 until 4).map { i -> withPatch(left, left = 4 + i, top = 7) } +
                listOf(flat(0.95f)) +
                (0 until 4).map { i -> withPatch(right, left = 22 - i, top = 7) }

        val result = AutoFramePlanner.plan(samples(frames))

        assertEquals("the cut must split the clip", 2, result.shots.size)
        assertTrue("the first shot ends before the cut",
            result.shots[0].endTimeMs <= result.shots[1].startTimeMs)
        assertTrue("both shots contribute to the path", result.path.size >= 2)
        assertTrue("the target really changed across the cut", result.targetSwitches >= 1)
    }

    @Test
    fun aSingleBlipCannotStealThePath() {
        val base = texture()
        val frames = (0 until 5).map { withPatch(base, left = 12, top = 7) } +
                listOf(withPatch(base, left = 26, top = 7)) +
                (0 until 3).map { withPatch(base, left = 12, top = 7) }

        val result = AutoFramePlanner.plan(samples(frames))

        assertTrue("no keyframe may jump to the blip",
            result.path.none { it.x > 0.60f })
        assertEquals("a blip is not a target change", 0, result.targetSwitches)
    }

    @Test
    fun degenerateInputsAreNotAnError() {
        assertEquals(0, AutoFramePlanner.plan(emptyList()).path.size)
        assertEquals(0, AutoFramePlanner.plan(samples(listOf(texture()))).path.size)
    }

    @Test
    fun thePathIsThinnedAndSimplified() {
        val base = texture()
        // A straight march: after thinning/simplification the path must be far smaller than
        // the sample count, which is what keeps the ffmpeg expression small.
        val frames = (0 until 21).map { i -> withPatch(base, left = 3 + i, top = 7) }

        val result = AutoFramePlanner.plan(samples(frames))

        assertTrue("path must be thinned, got ${result.path.size} of 21",
            result.path.size in 2..12)
        assertTrue("keyframes must stay inside the frame",
            result.path.all { it.x in 0f..1f && it.y in 0f..1f })
    }
}
