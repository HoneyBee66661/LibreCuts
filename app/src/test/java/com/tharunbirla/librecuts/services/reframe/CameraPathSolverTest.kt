package com.tharunbirla.librecuts.services.reframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First real unit test of the reframe module — and the part of PR #6 that CI can verify:
 * `ReframePlanner` / `CameraPathSolver` carry no Android or ffmpeg dependency, so
 * `testDebugUnitTest` executes them on the CI runner without a device.
 *
 * The numbers asserted here come from the spike that motivated the feature (9:16 out of a
 * 1920x1080 source, subject walking 0.15 -> 0.50 with a pause -> 0.85 over 5 s at 10 fps):
 * clamping leaves up to ~28 px of miss on a 1080-wide delivery and is off-centre 4% of the
 * time; solving the path returns one steady zoom that is centred 100% of the time.
 */
class CameraPathSolverTest {

    /** 9:16 delivered out of a 1920x1080 source: the full-height strip is ~31.6% wide. */
    private val baseW = (9f / 16f) / (1920f / 1080f)
    private val baseH = 1f

    private fun traverse(): List<ReframeKeyframe> {
        val out = ArrayList<ReframeKeyframe>(51)
        for (i in 0..50) {
            val x = when {
                i < 20 -> 0.15f + 0.35f * (i / 20f)
                i <= 30 -> 0.50f
                else -> 0.50f + 0.35f * ((i - 30) / 20f)
            }
            out.add(ReframeKeyframe(timeMs = i * 100L, x = x, y = 0.5f))
        }
        return out
    }

    @Test
    fun baseWindowIsTheFullHeightNinthSixteenthStrip() {
        assertEquals(0.31640625f, baseW, 1e-6f)
    }

    @Test
    fun clampingCannotCentreASubjectAtTheFrameEdge() {
        // The subject ends at 0.85; a fixed window can only travel to 0.5 +- (1-fw)/2 = 0.842.
        val edge = ReframeKeyframe(timeMs = 0L, x = 0.85f, y = 0.5f)
        val reach = (1f - baseW) / 2f
        assertEquals(0.3418f, reach, 1e-3f)

        val miss = CameraPathSolver.missFraction(edge, zoom = 1f, baseFractionW = baseW, baseFractionH = baseH)
        assertTrue("clamping must leave a visible miss, got $miss", miss > 0.005f)

        val missPx = miss * 1080f / baseW
        assertTrue("miss should be around 28 px of a 1080-wide delivery, got $missPx", missPx in 20f..35f)
    }

    @Test
    fun solverBuysTheReachWithOneSteadyZoom() {
        val solution = CameraPathSolver.solve(traverse(), baseW, baseH, maxZoom = 3f)

        assertEquals("one zoom level per sample", 51, solution.schedule.size)
        assertTrue("zoom must cover the traverse, got ${solution.constantZoom}",
            solution.constantZoom in 1.05f..1.15f)
        assertTrue("no push-in means the miss would be visible", solution.constantZoom > 1f)

        // A single level for the whole path = no visible breathing.
        assertEquals("churn must be zero for a monotone traverse", 0f, solution.churn, 1e-4f)

        for (k in traverse()) {
            val miss = CameraPathSolver.missFraction(k, solution.constantZoom, baseW, baseH)
            assertEquals("subject must be centred once the solved zoom is applied", 0f, miss, 1e-3f)
        }
    }

    @Test
    fun solverDoesNotPushInWhenThePathAlreadyFits() {
        // A subject that never leaves +-0.25 is reachable at zoom 1: the solver must answer 1.0,
        // otherwise every clip would get a pointless resolution loss.
        val gentle = (0..20).map { ReframeKeyframe(it * 100L, 0.40f + 0.01f * it, 0.5f) }
        val solution = CameraPathSolver.solve(gentle, baseW, baseH, maxZoom = 3f)

        assertEquals(1f, solution.constantZoom, 1e-4f)
        assertEquals(0f, solution.churn, 1e-4f)
    }

    @Test
    fun minimumCoveringZoomAgreesWithTheSolver() {
        val path = traverse()
        val floor = CameraPathSolver.minimumCoveringZoom(path, baseW, baseH, maxZoom = 3f)
        val solved = CameraPathSolver.solve(path, baseW, baseH, maxZoom = 3f).constantZoom

        // The DP lands on the first grid level at or above the continuous floor.
        assertTrue("floor $floor vs solved $solved", solved >= floor - 1e-4f)
        assertEquals(floor, solved, CameraPathSolver.LEVEL_STEP)
    }

    @Test
    fun degeneratePathIsNotAnError() {
        val solution = CameraPathSolver.solve(emptyList(), baseW, baseH, maxZoom = 3f)
        assertEquals(1f, solution.constantZoom, 1e-6f)
        assertEquals(0, solution.schedule.size)
        assertEquals(0f, solution.churn, 1e-6f)
    }

    @Test
    fun plannerPanOnlyClamps_butDpModeUsesTheSolvedZoom() {
        val path = traverse().map {
            com.tharunbirla.librecuts.models.EditOperation.KeyframePoint(it.timeMs, it.x, it.y)
        }
        val planClamp = ReframePlanner.plan(
            sourceWidth = 1920,
            sourceHeight = 1080,
            spec = com.tharunbirla.librecuts.models.ReframeSpec(
                com.tharunbirla.librecuts.models.ReframeAspect.TIKTOK_9_16,
                com.tharunbirla.librecuts.models.ReframeMode.PAN_ONLY,
                0f,
                com.tharunbirla.librecuts.models.PathMode.CLAMP
            ),
            keyframes = traverse()
        )
        val planDp = ReframePlanner.plan(
            sourceWidth = 1920,
            sourceHeight = 1080,
            spec = com.tharunbirla.librecuts.models.ReframeSpec(
                com.tharunbirla.librecuts.models.ReframeAspect.TIKTOK_9_16,
                com.tharunbirla.librecuts.models.ReframeMode.PAN_ONLY,
                0f,
                com.tharunbirla.librecuts.models.PathMode.DP
            ),
            keyframes = traverse()
        )

        assertEquals("clamp keeps the shipped no-zoom behaviour", 1f, planClamp.zoom, 1e-4f)
        assertTrue("DP must zoom to buy reach, got ${planDp.zoom}", planDp.zoom > 1f)
        assertTrue("DP must stay inside MAX_ZOOM, got ${planDp.zoom}", planDp.zoom <= 3f)

        // The window must keep the target aspect, otherwise the render letterboxes. Window
        // fractions are relative to frame width/height, so the aspect needs the source dims.
        val windowAspect = (planDp.windowWidthFraction * 1920f) / (planDp.windowHeightFraction * 1080f)
        assertEquals(9f / 16f, windowAspect, 1e-3f)
        assertEquals(1080, planDp.outWidth)
        assertEquals(1920, planDp.outHeight)
        assertTrue("path is only stored for the clamp spec too", path.isNotEmpty())
    }
}
