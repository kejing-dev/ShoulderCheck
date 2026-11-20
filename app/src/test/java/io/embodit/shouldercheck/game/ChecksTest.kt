package io.embodit.shouldercheck.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mustPass M8 (design `judging`): the shoulder threshold is always > the same-side mirror + 10°, a mirror look never
 * lights 「回头」, both sides at the bottom and top of T; freshness; the honest adaptive threshold; D27 sloppy turns
 * still count; a novice's first game lights both lights in ≥ 80 % of rounds.
 */
class ChecksTest {
    private val side = TrafficSide.RIGHT_HAND
    private fun round(s: Situation, side: TrafficSide = this.side) = RoundGenerator.debugRound(Levels.of(1), s, side, null, 0.0, 0.0, trap = false)

    /** Holds [yaw] (head; gaze too when [eye]) from [from] for [dur] seconds at 90 Hz. */
    private fun hold(j: CheckJudge, from: Double, dur: Double, yaw: Double, pitch: Double = 0.0, eye: Boolean = false, pos: Vec3 = Vec3.ZERO, gazeDir: Vec3? = null): Double {
        var t = from
        while (t <= from + dur + 1e-9) {
            val g = if (eye) GazeRay(pos, gazeDir ?: Angles.dir(yaw, pitch)) else null
            j.update(CheckSample(t, yaw, pitch, pos, g))
            t += 1.0 / 90
        }
        return t
    }

    /** A smooth head turn from [a] to [b] over [dur] s. */
    private fun turn(j: CheckJudge, from: Double, dur: Double, a: Double, b: Double, pos: Vec3 = Vec3.ZERO): Double {
        var t = from
        while (t <= from + dur + 1e-9) {
            val u = ((t - from) / dur).coerceIn(0.0, 1.0)
            j.update(CheckSample(t, a + (b - a) * u, 0.0, pos, null))
            t += 1.0 / 90
        }
        return t
    }

    @Test
    fun shoulderThresholdIsAlwaysPastTheMirror() {
        for (ts in TrafficSide.values()) {
            val cabin = Cabin(Seat.DRIVER, ts)
            for (sit in listOf(Situation.LANE_LEFT, Situation.LANE_RIGHT)) {
                val dir = sit.dir(ts)
                val m = cabin.mirror(MirrorId.side(dir))!!
                val far = dir != cabin.doorSide
                for (eye in listOf(false, true)) {
                    val low = ShoulderBounds.low(eye, far)
                    assertTrue("$ts $dir eye=$eye low $low > |mirror| ${kotlin.math.abs(m.yawDeg)} + 10", low >= kotlin.math.abs(m.yawDeg) + 10.0 - 1e-6)
                    assertTrue(ShoulderBounds.default(eye, far) in low..ShoulderBounds.high(eye, far))
                }
                // the head mirror band ends below the lowest shoulder threshold
                val j = CheckJudge(round(sit, ts), JudgeMode.HEAD, Thresholds())
                val outer = maxOf(kotlin.math.abs(j.band.start), kotlin.math.abs(j.band.endInclusive))
                assertTrue("$ts $dir band edge $outer < low", outer < ShoulderBounds.low(false, far))
            }
        }
    }

    @Test
    fun mirrorLookNeverLightsShoulderLeftAndRightAtBothEndsOfT() {
        for ((s, far) in listOf(Situation.LANE_LEFT to false, Situation.LANE_RIGHT to true)) {
            for (tValue in listOf(ShoulderBounds.low(false, far), ShoulderBounds.high(false, far))) {
                val th = Thresholds()
                if (far) th.headFar = tValue else th.headNear = tValue
                val r = round(s)
                val j = CheckJudge(r, JudgeMode.HEAD, th)
                val sign = r.dir.sign
                // the far end of the mirror band, held for a long time
                val edge = if (sign < 0) j.band.start else j.band.endInclusive
                hold(j, 0.0, 1.0, edge)
                assertNotNull("$s T=$tValue mirror lit", j.mirrorLast)
                assertNull("$s T=$tValue a mirror look is not a shoulder check", j.shoulderLast)
                // just under T: still no
                hold(j, 1.1, 0.5, sign * (tValue - 0.5))
                assertNull(j.shoulderLast)
                // at T for 0.2 s: yes
                hold(j, 2.0, 0.2, sign * (tValue + 0.2))
                assertNotNull("$s T=$tValue shoulder lit", j.shoulderLast)
            }
        }
    }

    @Test
    fun designNumbersHeadMode() {
        // verification list: left 66° 0.3 s → 回头; left 50° (in the mirror band) → 镜 only; right 66.7° → 镜; right 81° → 回头
        val l = CheckJudge(round(Situation.LANE_LEFT), JudgeMode.HEAD, Thresholds())
        hold(l, 0.0, 0.4, -50.0)
        assertNotNull(l.mirrorLast)
        assertNull(l.shoulderLast)
        hold(l, 0.5, 0.3, -66.0)
        assertNotNull(l.shoulderLast)
        val r = CheckJudge(round(Situation.LANE_RIGHT), JudgeMode.HEAD, Thresholds())
        hold(r, 0.0, 0.4, 66.7)
        assertNotNull(r.mirrorLast)
        assertNull(r.shoulderLast)
        hold(r, 0.5, 0.3, 81.0)
        assertNotNull(r.shoulderLast)
    }

    @Test
    fun eyeModeUsesGazeOnTheGlassAndGazeYaw() {
        val r = round(Situation.LANE_LEFT)
        val j = CheckJudge(r, JudgeMode.EYE, Thresholds())
        // head straight, eyes on the mirror glass for 0.15 s
        hold(j, 0.0, 0.15, -10.0, eye = true, gazeDir = r.mirror.pos.normalized())
        assertNotNull("eye dwell ≥ 120 ms on the glass", j.mirrorLast)
        assertFalse(j.mirrorByHead)
        // gaze yaw 78° (< 80) no; 82° yes
        hold(j, 0.5, 0.3, -78.0, eye = true)
        assertNull(j.shoulderLast)
        hold(j, 1.0, 0.2, -82.0, eye = true)
        assertNotNull(j.shoulderLast)
    }

    @Test
    fun freshness() {
        val j = CheckJudge(round(Situation.LANE_LEFT), JudgeMode.HEAD, Thresholds())
        val t1 = hold(j, 2.0, 0.3, -50.0)
        hold(j, t1, 0.25, -70.0)
        val m = j.mirrorLast!!
        val s = j.shoulderLast!!
        assertTrue(j.mirrorFresh(m + 3.9))
        assertFalse(j.mirrorFresh(m + 4.1))
        assertTrue(j.shoulderFresh(s + 2.9))
        assertFalse(j.shoulderFresh(s + 3.1))
    }

    @Test
    fun adaptiveThresholdOnlyFallsForHonestNearMisses() {
        val th = Thresholds()
        // half turns with nothing to see: no change
        repeat(6) { assertEquals(0, th.onTurn(false, false, 58.0, hazardVisible = false)) }
        assertEquals(65.0, th.headNear, 1e-9)
        // two honest near misses (peak within 10° under T, the hazard in view) → −5
        assertEquals(0, th.onTurn(false, false, 59.0, true))
        assertEquals(-5, th.onTurn(false, false, 57.0, true))
        assertEquals(60.0, th.headNear, 1e-9)
        // never below the floor
        repeat(4) { th.onTurn(false, false, 55.0, true) }
        assertEquals(ShoulderBounds.HEAD_NEAR_LOW, th.headNear, 1e-9)
        // five easy turns in a row (≥ T + 15) → +5, capped at default + 10
        repeat(4) { assertEquals(0, th.onTurn(false, false, 80.0, false)) }
        assertEquals(5, th.onTurn(false, false, 80.0, false))
        assertEquals(65.0, th.headNear, 1e-9)
        repeat(30) { th.onTurn(false, false, 120.0, false) }
        assertEquals(ShoulderBounds.high(false, false), th.headNear, 1e-9)
    }

    @Test
    fun judgeReportsHonestNearMissTurns() {
        // a trap sits in the blind spot; two turns to 58° (it is in view at 58° ± 40°) relax T from 65 to 60
        val r = RoundGenerator.debugRound(Levels.of(1), Situation.LANE_LEFT, side, ActorKind.SEDAN, 3.6, 0.0, trap = true)
        val th = Thresholds()
        val j = CheckJudge(r, JudgeMode.HEAD, th)
        var t = turn(j, 0.0, 0.4, 0.0, -58.0)
        t = turn(j, t, 0.4, -58.0, 0.0)
        t = turn(j, t, 0.4, 0.0, -58.0)
        turn(j, t, 0.4, -58.0, 0.0)
        assertEquals(60.0, th.headNear, 1e-9)
        assertTrue("saw the trap", j.saw)
    }

    /** D27: ten sloppy recordings (slow, off-centre, head 10 cm off, short dwell edge, pitched head) still light both. */
    @Test
    fun sloppyChecksStillCount() {
        data class Sloppy(val name: String, val mirrorYaw: Double, val mirrorDur: Double, val peak: Double, val peakDur: Double, val pos: Vec3 = Vec3.ZERO, val pitch: Double = 0.0, val slow: Double = 1.0)
        val cases =
            listOf(
                Sloppy("mirror band front edge", -29.0, 0.3, 67.0, 0.2),
                Sloppy("mirror band back edge", -56.0, 0.3, 67.0, 0.2),
                Sloppy("just the dwell", -45.0, 0.26, 66.0, 0.16),
                Sloppy("head 10 cm left", -48.0, 0.3, 68.0, 0.2, pos = Vec3(-0.10, 0.0, 0.0)),
                Sloppy("head 10 cm forward", -48.0, 0.3, 68.0, 0.2, pos = Vec3(0.0, 0.0, -0.10)),
                Sloppy("slow ×2", -48.0, 0.3, 70.0, 0.2, slow = 2.0),
                Sloppy("head pitched down 15°", -48.0, 0.3, 68.0, 0.2, pitch = -15.0),
                Sloppy("head pitched up 15°", -48.0, 0.3, 68.0, 0.2, pitch = 15.0),
                Sloppy("overshoot", -48.0, 0.3, 95.0, 0.15),
                Sloppy("barely past T", -48.0, 0.3, 65.5, 0.18),
            )
        for (c in cases) {
            val j = CheckJudge(round(Situation.LANE_LEFT), JudgeMode.HEAD, Thresholds())
            var t = 0.0
            var yaw = 0.0
            fun go(to: Double, dur: Double) {
                val steps = (dur * c.slow * 90).toInt().coerceAtLeast(1)
                for (i in 1..steps) {
                    val y = yaw + (to - yaw) * i / steps
                    j.update(CheckSample(t, y, c.pitch, c.pos, null))
                    t += 1.0 / 90
                }
                yaw = to
            }
            go(c.mirrorYaw, 0.25)
            t = hold(j, t, c.mirrorDur, c.mirrorYaw, c.pitch, pos = c.pos)
            yaw = c.mirrorYaw
            go(-c.peak, 0.35)
            t = hold(j, t, c.peakDur, -c.peak, c.pitch, pos = c.pos)
            yaw = -c.peak
            go(0.0, 0.4)
            assertNotNull("${c.name}: mirror", j.mirrorLast)
            assertNotNull("${c.name}: shoulder", j.shoulderLast)
            assertTrue("${c.name}: both fresh at go", j.mirrorFresh(t + 0.3) && j.shoulderFresh(t + 0.3))
        }
    }

    /**
     * design `judging.principle`: a novice's first game lights both lights in ≥ 80 % of the rounds. Model of a novice who
     * follows the hint: a short mirror look, then a turn that peaks a little either side of the default threshold
     * (near side 63–77°, far side 78–92°), held 0.2 s. 50 first games.
     */
    @Test
    fun noviceFirstGameLightsBothInMostRounds() {
        var both = 0
        var rounds = 0
        for (game in 1..50) {
            val plan = GamePlan(Levels.of(1), game.toLong(), side, daily = false, firstEver = true)
            val th = Thresholds()
            val rng = java.util.Random(game * 17L)
            for (i in 0 until GamePlan.ROUNDS) {
                val r = plan.round(i)
                val j = CheckJudge(r, JudgeMode.HEAD, th)
                val sign = r.dir.sign
                val far = r.dir != r.cabin.doorSide
                val mid = (j.band.start + j.band.endInclusive) / 2
                var t = turn(j, GO_LIT, 0.3, 0.0, mid)
                t = hold(j, t, 0.3, mid)
                val peak = if (far) 78.0 + rng.nextDouble() * 14.0 else 63.0 + rng.nextDouble() * 14.0
                t = turn(j, t, 0.35, mid, sign * peak)
                t = hold(j, t, 0.2, sign * peak)
                t = turn(j, t, 0.4, sign * peak, 0.0)
                rounds++
                if (j.mirrorFresh(t + 0.2) && j.shoulderFresh(t + 0.2)) both++
            }
        }
        val rate = both.toDouble() / rounds
        println("novice both-lights rate ${"%.3f".format(rate)}")
        assertTrue("both lights in $both / $rounds rounds", rate >= 0.80)
    }

    @Test
    fun lookForwardGate() {
        assertTrue(CheckJudge.lookingForward(20.0))
        assertTrue(CheckJudge.lookingForward(-35.0))
        assertFalse(CheckJudge.lookingForward(-60.0))
    }
}
