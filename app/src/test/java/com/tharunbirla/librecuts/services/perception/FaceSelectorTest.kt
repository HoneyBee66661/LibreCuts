package com.tharunbirla.librecuts.services.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the face decision layer. Pure Kotlin, so CI runs them.
 *
 * The interesting assertions are the *stability* ones: a follower that teleports onto a
 * bystander, or that invents a subject that was never there, is worse than one that admits it
 * lost the subject. Those cases are therefore first-class here, not edge cases.
 */
class FaceSelectorTest {

    private val seed = 0.5f
    private fun selector(config: FaceSelector.Config = FaceSelector.Config()) =
        FaceSelector(seedX = seed, seedY = seed, config = config)

    private fun face(x: Float, y: Float, area: Float = 0.05f, id: Int? = null) =
        FaceSelector.Face(x, y, area, id)

    private fun eps(a: Float, b: Float, e: Float = 1e-4f) = assertEquals(a, b, e)

    @Test
    fun theFirstLockTakesTheFaceNearestTheBoxNotTheLargest() {
        val s = selector()
        val d = s.accept(
            listOf(
                face(0.9f, 0.9f, area = 0.30f, id = 1),   // a big face, but not the marked one
                face(0.52f, 0.5f, area = 0.03f, id = 2)   // the face under the box
            )
        )
        assertTrue(d.seen)
        eps(0.52f, d.x)
        assertEquals(2, d.trackId)
    }

    @Test
    fun lockedIdentityBeatsANearerFace() {
        val s = selector()
        val first = s.accept(listOf(face(0.5f, 0.5f, id = 7)))
        assertEquals(7, first.trackId)

        // A bigger, closer bystander appears: identity still wins, no switch is counted.
        val d = s.accept(listOf(face(0.53f, 0.5f, id = 7), face(0.5f, 0.5f, area = 0.3f, id = 9)))
        assertTrue(d.seen)
        eps(0.53f, d.x)
        assertEquals(7, d.trackId)
        assertEquals(0, s.switches)
    }

    @Test
    fun aBystanderFarAwayDoesNotTeleportTheFrame() {
        val s = selector()
        s.accept(listOf(face(0.5f, 0.5f, id = 7)))

        // The subject is gone and someone else is on the other side of the frame.
        val d = s.accept(listOf(face(0.95f, 0.5f, area = 0.25f, id = 9)))
        assertFalse(d.seen)
        eps(0.5f, d.x, 1e-5f)          // still where the subject last was
        eps(0.0f, d.confidence, 1e-5f)  // and honest about it
        assertEquals(0, s.switches)
    }

    @Test
    fun aDifferentFaceIsAdoptedOnlyAfterItInsists() {
        val s = selector(FaceSelector.Config(minHold = 3))

        // Lock subject 1 the way the ladder demands: three consistent samples in the same place.
        assertFalse(s.accept(listOf(face(0.2f, 0.2f, id = 1))).seen)
        assertFalse(s.accept(listOf(face(0.2f, 0.2f, id = 1))).seen)
        assertTrue(s.accept(listOf(face(0.2f, 0.2f, id = 1))).seen)
        assertEquals(1, s.seenCount)

        // A second subject appears near enough to be a real candidate, and stays there.
        val d1 = s.accept(listOf(face(0.30f, 0.2f, id = 2)))
        val d2 = s.accept(listOf(face(0.30f, 0.2f, id = 2)))
        assertFalse("one or two samples must not move the camera", d1.seen || d2.seen)

        val d3 = s.accept(listOf(face(0.30f, 0.2f, id = 2)))
        assertTrue(d3.seen)
        assertEquals(2, d3.trackId)
        assertEquals(1, s.switches)
    }

    @Test
    fun aLostSubjectHoldsItsLastPositionAtZeroConfidence() {
        val s = selector()
        s.accept(listOf(face(0.4f, 0.42f, id = 3)))
        val d = s.accept(emptyList())
        assertFalse(d.seen)
        eps(0.4f, d.x)
        eps(0.42f, d.y)
        eps(0f, d.confidence)
    }

    @Test
    fun aFaceTooSmallToBeTheSubjectIsIgnored() {
        val s = selector()
        // Only a distant face: below minArea, so nothing is adopted at all.
        val d = s.accept(listOf(face(0.5f, 0.5f, area = 0.0005f, id = 4)))
        assertFalse(d.seen)
        assertEquals(0, s.seenCount)
    }

    @Test
    fun afterEnoughMissesTheSearchReopensFromTheBox() {
        val cfg = FaceSelector.Config(minHold = 3, reacquireAfter = 2)
        val s = selector(cfg)
        s.accept(listOf(face(0.5f, 0.5f, id = 7)))

        repeat(2) { s.accept(emptyList()) }        // subject leaves the frame
        // Comes back on the other side: far from the stale position, but the ladder applies
        // from the user's box, so it is re-acquired after minHold samples.
        assertFalse(s.accept(listOf(face(0.9f, 0.5f, id = 9))).seen)
        assertFalse(s.accept(listOf(face(0.9f, 0.5f, id = 9))).seen)
        val back = s.accept(listOf(face(0.9f, 0.5f, id = 9)))
        assertTrue(back.seen)
        eps(0.9f, back.x)
    }

    @Test
    fun confidenceTracksProminenceAndIsClamped() {
        val s = selector()
        eps(0.5f, s.accept(listOf(face(0.5f, 0.5f, area = 0.1f))).confidence)
        val big = selector()
        eps(1f, big.accept(listOf(face(0.5f, 0.5f, area = 0.6f))).confidence)
    }

    @Test
    fun theTraceDropsEverythingBeforeTheFirstSighting() {
        val s = selector(FaceSelector.Config(minHold = 3))
        val times = (0 until 6).map { it * 100L }
        // Three missed samples, then three where the face is found.
        val decisions = listOf(
            s.accept(emptyList()), s.accept(emptyList()), s.accept(emptyList()),
            s.accept(listOf(face(0.5f, 0.5f, id = 1))),
            s.accept(listOf(face(0.5f, 0.5f, id = 1))),
            s.accept(listOf(face(0.5f, 0.5f, id = 1)))
        )
        val trace = FaceSelector.trace(times, decisions)
        assertNotNull(trace)
        requireNotNull(trace)
        assertEquals(3, trace.times.size)
        assertEquals(300L, trace.times.first())
        assertTrue(trace.confidenceFlags().all { it })
        assertEquals(3, trace.seenCount)
    }

    @Test
    fun aSingleOrNoSightingYieldsNoTraceAtAll() {
        val s = selector()
        val times = (0 until 4).map { it * 100L }
        assertNull("nothing seen: no path, no guess", FaceSelector.trace(times, listOf(
            s.accept(emptyList()), s.accept(emptyList()), s.accept(emptyList()), s.accept(emptyList())
        )))

        val one = selector()
        val decisions = listOf(
            one.accept(emptyList()),
            one.accept(listOf(face(0.5f, 0.5f, id = 1))),
            one.accept(emptyList())
        )
        assertNull("a single sighting cannot drive a moving frame", FaceSelector.trace(times, decisions))
    }

    @Test
    fun aStaticSubjectIsRecognisableAsStatic() {
        val s = selector()
        val d = (0 until 5).map { s.accept(listOf(face(0.5f, 0.5f, id = 1))) }
        val trace = FaceSelector.trace((0 until 5).map { it * 100L }, d)
        assertNotNull(trace)
        requireNotNull(trace)
        assertTrue("a face that never moves must not manufacture a moving camera", trace.isStatic())
    }

    @Test
    fun withoutABoxTheMostProminentFaceBecomesTheSubject() {
        val frames = listOf(
            face(0.5f, 0.5f, area = 0.02f, id = 1),   // small, dead centre
            face(0.8f, 0.4f, area = 0.12f, id = 2)    // bigger, off-centre
        )

        // With a box at the centre the small centred face is the subject: the user pointed there.
        val boxed = selector(FaceSelector.Config(minHold = 2))
        assertEquals(1, (0 until 3).map { boxed.accept(frames) }.first { it.seen }.trackId)

        // Auto frame has no box, so prominence decides instead — and the ladder still has to
        // confirm the subject before the camera moves to it.
        val auto = FaceSelector(
            seedX = 0.5f,
            seedY = 0.5f,
            config = FaceSelector.Config(minHold = 2),
            preferProminentFirst = true
        )
        assertFalse(auto.accept(frames).seen)
        val adopted = auto.accept(frames)
        assertTrue(adopted.seen)
        assertEquals(2, adopted.trackId)
    }

    @Test
    fun aCandidateThatKeepsChangingIsNeverAdopted() {
        val s = FaceSelector(
            seedX = 0.5f,
            seedY = 0.5f,
            config = FaceSelector.Config(minHold = 3),
            preferProminentFirst = true
        )
        // A different prominent face in every sample: prominence alone must never lock the
        // camera on, because the ladder keys on the candidate staying (place or id).
        val eachFrame = listOf(
            listOf(face(0.2f, 0.2f, area = 0.12f, id = 1)),
            listOf(face(0.8f, 0.2f, area = 0.12f, id = 2)),
            listOf(face(0.2f, 0.8f, area = 0.12f, id = 3))
        )
        assertTrue(eachFrame.all { !s.accept(it).seen })
        assertEquals(0, s.seenCount)
    }

    @Test
    fun aFullFrameSelectionDoesNotPinThePathToTheCentre() {
        // Auto frame's selection is the whole frame. Clamping to half of it (as the correlator
        // does with a marked box) would freeze every sample at 0.5 and emit a still path.
        eps(0.02f, FaceSelector.clampMargin(1f))
        // A real box keeps the correlator's rule: the subject centre stays inside the box.
        eps(0.2f, FaceSelector.clampMargin(0.4f))
        eps(0f, FaceSelector.clampMargin(0f))
        // And the margin can never invert the clamp range.
        assertTrue(FaceSelector.clampMargin(1f) <= 0.5f && FaceSelector.clampMargin(0.99f) <= 0.5f)
    }
}
