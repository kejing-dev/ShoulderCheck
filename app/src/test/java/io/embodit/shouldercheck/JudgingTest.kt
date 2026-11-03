package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.ActorRole
import io.embodit.shouldercheck.domain.Actor
import io.embodit.shouldercheck.domain.BlindSpot
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.DoorModel
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.HazardSpec
import io.embodit.shouldercheck.domain.JudgeEvent
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.Key
import io.embodit.shouldercheck.domain.MirrorAims
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.Phase
import io.embodit.shouldercheck.domain.QuestionJudge
import io.embodit.shouldercheck.domain.QuestionResult
import io.embodit.shouldercheck.domain.SawHazard
import io.embodit.shouldercheck.domain.Scenario
import io.embodit.shouldercheck.domain.Scenes
import io.embodit.shouldercheck.domain.SeenVia
import io.embodit.shouldercheck.domain.Signal
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Step
import io.embodit.shouldercheck.domain.StepState
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.TurnProfile
import io.embodit.shouldercheck.domain.TurnRange
import io.embodit.shouldercheck.domain.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design `model.judging.unitTests` U1–U8, U10, U13–U15 (U9, U11, U12, U16, U17 live in the other test classes). */
class JudgingTest {
    /** T = 60° both sides: a measured range of 75° (0.8 × 75). */
    private fun profile60() = TurnProfile(TurnRange(75.0, 75.0))

    private fun step(r: QuestionResult, s: Step) = r.steps.first { it.step == s }

    /**
     * The U1 routine for a lane change toward the scenario's direction: inner mirror 150 ms, side mirror 150 ms (eye
     * mode: gaze), head 64° toward the side held 200 ms, signal, then execute [signalToExec] s after the signal.
     * [shoulderLead] = seconds between the end of the shoulder hold and executing (U2).
     */
    private fun laneChange(s: Scenario, mode: JudgeMode, signalToExec: Double = 3.2, shoulderLead: Double? = null, profile: TurnProfile = profile60()): Pair<QuestionResult, TestDriver> {
        val judge = QuestionJudge(s, mode, profile)
        val d = TestDriver(judge)
        val dir = s.dir
        val inner = s.cabin.mirror(MirrorId.INNER)!!
        val side = s.cabin.mirror(MirrorId.side(dir))!!
        d.hold(0.3)
        if (s.requiredSteps.contains(Step.INNER_MIRROR)) {
            d.gazeAt(inner.pos, 0.15)
            d.hold(0.25)
        }
        d.gazeAt(side.pos, 0.15)
        d.hold(0.2)
        d.turn(64.0 * dir.sign)
        d.hold(0.2)
        d.turn(0.0)
        val shoulderEnd = d.t
        if (shoulderLead != null) {
            // wait so that the look back is [shoulderLead] s old at execute; refresh the side mirror close to execute
            val execAt = shoulderEnd + shoulderLead
            d.until(execAt - signalToExec)
            d.signal = Signal.of(dir)
            d.until(execAt - 1.0)
            d.gazeAt(side.pos, 0.15)
            d.until(execAt)
        } else {
            d.hold(0.3)
            d.signal = Signal.of(dir)
            d.hold(signalToExec)
        }
        d.exec()
        d.finish()
        return judge.result() to d
    }

    @Test
    fun u1_laneLeftEyeModeClear() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val (r, d) = laneChange(s, JudgeMode.EYE)
        assertEquals(Outcome.CLEAR, r.outcome)
        assertTrue(r.steps.all { it.done })
        assertEquals(64.0, r.shoulderPeak, 0.01)
        assertEquals(60.0, r.threshold, 1e-9)
        assertEquals(SawHazard.NO_HAZARD, r.saw)
        // check time = the moment the 4th step (signal, on for 1 s) lit
        val signalLit = d.events.filterIsInstance<JudgeEvent.StepLit>().first { it.step == Step.SIGNAL_LEFT }.t
        assertEquals(signalLit, r.checkTime!!, 1e-9)
        assertEquals(listOf(Step.INNER_MIRROR, Step.LEFT_MIRROR, Step.SHOULDER_LEFT, Step.SIGNAL_LEFT), r.actualOrder)
        assertFalse(r.signalTip) // 3.2 s ≥ 3 s
        assertTrue(r.judgedByHeadSteps.isEmpty())
        assertTrue(d.events.any { it is JudgeEvent.TurnReached && it.dir == Dir.LEFT })
    }

    @Test
    fun u2_staleShoulderIsLucky() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val (r, d) = laneChange(s, JudgeMode.EYE, shoulderLead = 5.6)
        assertEquals(Outcome.LUCKY, r.outcome)
        val sh = step(r, Step.SHOULDER_LEFT)
        assertFalse(sh.done)
        assertTrue(sh.stale)
        assertTrue(step(r, Step.LEFT_MIRROR).done)
        assertTrue(d.events.any { it is JudgeEvent.StepStale && it.step == Step.SHOULDER_LEFT })
    }

    @Test
    fun u3_hazardInBlindSpotWithoutShoulderIsNearMiss() {
        // e-bike in the left lane overtaking at +3 m/s, rear 3 m behind you at t = 3.2 s
        val spec = HazardSpec(ActorKind.EBIKE, Dir.LEFT, 3.0 + 3.0 * 3.2, 3.0)
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, spec)
        assertTrue(BlindSpot.isInBlindSpot(s.hazard!!.box(3.2), Dir.LEFT, MirrorAims(), s.cabin))
        val judge = QuestionJudge(s, JudgeMode.EYE, profile60())
        val d = TestDriver(judge)
        d.gazeAt(s.cabin.mirror(MirrorId.INNER)!!.pos, 0.2)
        d.gazeAt(s.cabin.mirror(MirrorId.LEFT)!!.pos, 0.2)
        d.signal = Signal.LEFT
        d.until(3.2)
        d.exec()
        d.finish()
        val r = judge.result()
        assertEquals(Outcome.NEAR_MISS, r.outcome)
        assertEquals("hazard", r.conflictActor)
        assertFalse(step(r, Step.SHOULDER_LEFT).done)
        assertTrue(d.events.any { it is JudgeEvent.Conflict })
    }

    /** U4 scene: a sedan sitting in the left blind spot (rear 3.6 m behind) for 4 s, then overtaking at 4 m/s. */
    private fun blindSpotSitter(): Scenario {
        val road = io.embodit.shouldercheck.domain.RoadLayout(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val c0 = 3.6 - ActorKind.SEDAN.length / 2
        val car = Actor("hazard", ActorKind.SEDAN, road.adjacentLaneX(Dir.LEFT), listOf(Key(0.0, c0), Key(4.0, c0), Key(30.0, c0 - 4.0 * 26.0)), ActorRole.HAZARD)
        return Scenario(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, listOf(car), "hazard")
    }

    /** Head-mode mirror look: turn the head onto the mirror (a little short of it), stay still 0.5 s, come back. */
    private fun headLook(d: TestDriver, yaw: Double, pitch: Double) {
        d.turn(yaw, pitch)
        d.hold(0.5)
        d.turn(0.0, 0.0)
    }

    private fun shoulder(d: TestDriver, deg: Double, hold: Double = 0.3) {
        d.turn(deg)
        d.hold(hold)
        d.turn(0.0)
    }

    @Test
    fun u4_sawItWaitedRecheckedIsWellWaited() {
        val s = blindSpotSitter()
        assertTrue(BlindSpot.isInBlindSpot(s.hazard!!.box(1.0), Dir.LEFT, MirrorAims(), s.cabin))
        val pass = s.hazardPassTime!!
        val judge = QuestionJudge(s, JudgeMode.HEAD, profile60())
        val d = TestDriver(judge)
        d.eyeOff = true
        headLook(d, 30.0, 8.0) // inner mirror
        headLook(d, -45.0, -8.0) // left mirror
        // look back: head 64°; the car's centre is outside ±40° of the head but its front is inside → seen
        shoulder(d, -64.0)
        assertTrue(d.last!!.saw)
        d.signal = Signal.LEFT
        val firstAllLit = d.t + 1.0
        d.until(pass + 0.3) // wait for it to go past
        headLook(d, -45.0, -8.0)
        shoulder(d, -64.0)
        d.exec()
        d.finish()
        val r = judge.result()
        assertEquals(Outcome.WAITED, r.outcome)
        assertEquals(SawHazard.SAW, r.saw)
        assertEquals(SeenVia.HEAD, r.seenVia)
        // check time counts the first time everything was lit, not the wait
        assertEquals(firstAllLit, r.checkTime!!, 0.05)
    }

    @Test
    fun u4_notSeenButWaitedIsClear() {
        val s = blindSpotSitter()
        val pass = s.hazardPassTime!!
        val judge = QuestionJudge(s, JudgeMode.HEAD, profile60())
        val d = TestDriver(judge)
        d.eyeOff = true
        // only the inner mirror and the signal while it sits there (turning the head toward the left mirror would
        // already bring its nose into the ±40° band), the rest after it has gone
        headLook(d, 30.0, 8.0)
        d.signal = Signal.LEFT
        d.until(pass + 0.3)
        headLook(d, -45.0, -8.0)
        shoulder(d, -64.0)
        d.exec()
        d.finish()
        val r = judge.result()
        assertEquals(Outcome.CLEAR, r.outcome)
        assertEquals(SawHazard.NOT_SEEN, r.saw)
    }

    @Test
    fun u5_twoNearMissesRelaxThreshold() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val profile = profile60()
        val judge = QuestionJudge(s, JudgeMode.HEAD, profile)
        val d = TestDriver(judge)
        d.eyeOff = true
        shoulder(d, -54.0)
        assertEquals(60.0, profile.threshold(Dir.LEFT), 1e-9)
        assertEquals(StepState.PENDING, d.last!!.steps.first { it.step == Step.SHOULDER_LEFT }.state)
        val near = d.events.filterIsInstance<JudgeEvent.TurnEnded>().last().excursion
        assertTrue(near is io.embodit.shouldercheck.domain.Excursion.NearMiss)
        assertEquals(6.0, (near as io.embodit.shouldercheck.domain.Excursion.NearMiss).missing, 0.01)
        shoulder(d, -54.0)
        assertEquals(55.0, profile.threshold(Dir.LEFT), 1e-9)
        shoulder(d, -56.0)
        assertEquals(StepState.DONE, d.last!!.steps.first { it.step == Step.SHOULDER_LEFT }.state)
    }

    private fun doorScene(hazard: HazardSpec?): Scenario = Scenes.build(Situation.DOOR_DRIVER, TrafficSide.RIGHT_HAND, hazard)

    private fun doorRun(s: Scenario, hand: Hand, pushAt: Double, push: Double = 0.10): Pair<QuestionJudge, TestDriver> {
        val judge = QuestionJudge(s, JudgeMode.EYE, profile60())
        val d = TestDriver(judge)
        d.until(maxOf(0.0, pushAt - 3.0))
        d.grab = hand
        d.hold(0.3)
        d.gazeAt(s.cabin.mirror(MirrorId.LEFT)!!.pos, 0.2)
        shoulder(d, -64.0, 0.25)
        d.until(pushAt)
        d.push = push
        d.hold(0.2)
        return judge to d
    }

    @Test
    fun u6_doorDriverFarHandClearAndNearHandWrong() {
        // a bike that has gone past by t ≈ 1.5 s
        val spec = HazardSpec(ActorKind.BIKE, Dir.LEFT, 6.0, 5.0)
        val s = doorScene(spec)
        val pass = s.hazardPassTime!!
        assertTrue(pass < 2.5)
        val (judge, d) = doorRun(s, Hand.RIGHT, pushAt = pass + 3.5)
        d.finish()
        val r = judge.result()
        assertEquals(Outcome.CLEAR, r.outcome)
        assertTrue(step(r, Step.FAR_HAND).done)
        assertNotNull(r.execTime)

        val s2 = doorScene(spec)
        val (judge2, d2) = doorRun(s2, Hand.LEFT, pushAt = pass + 3.5)
        d2.finish()
        val r2 = judge2.result()
        val far = step(r2, Step.FAR_HAND)
        assertFalse(far.done)
        assertTrue(far.wrong)
        assertEquals(Outcome.LUCKY, r2.outcome)
    }

    @Test
    fun u7_doorSweepConflictOnlyPast12Degrees() {
        // e-bike riding 0.85 m outside the body at 4 m/s; its front reaches the door at ~3.5 s
        val spec = HazardSpec(ActorKind.EBIKE, Dir.LEFT, 0.5 + 4.0 * 3.5 + ActorKind.EBIKE.length, 4.0)
        val s = doorScene(spec)
        val entry = s.hazardEntryTime!!
        val (judge, d) = doorRun(s, Hand.RIGHT, pushAt = entry - 0.6)
        d.finish()
        val r = judge.result()
        assertEquals(Outcome.NEAR_MISS, r.outcome)
        assertTrue(r.conflictTime!! > r.execTime!!)

        // only a crack (3 cm → 8°): the same e-bike rides past, no near miss, nothing executed
        val s2 = doorScene(spec)
        val (judge2, d2) = doorRun(s2, Hand.RIGHT, pushAt = entry - 0.6, push = 0.03)
        d2.until(s2.hazardPassTime!! + 0.5)
        assertEquals(8.0, judge2.doorAngle, 1e-6)
        assertEquals(Phase.CHECKING, judge2.phase)
        assertTrue(d2.events.none { it is JudgeEvent.Conflict })

        // the geometry itself: the rider beside the door
        val box = s.hazard!!.box(entry + 0.3)
        assertTrue(DoorModel.conflicts(s.cabin, 60.0, box))
        assertFalse(DoorModel.conflicts(s.cabin, 8.0, box))
        assertEquals(0.16, s.cabin.doorLength * kotlin.math.sin(Math.toRadians(8.0)), 0.005)
        assertEquals(1.0, s.cabin.doorLength * kotlin.math.sin(Math.toRadians(60.0)), 0.01)
    }

    @Test
    fun u8_signalTiming() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val (ok, dOk) = laneChange(s, JudgeMode.EYE, signalToExec = 1.2)
        assertTrue(step(ok, Step.SIGNAL_LEFT).done)
        assertTrue(ok.signalTip)
        assertEquals(Outcome.CLEAR, ok.outcome)
        assertTrue(dOk.events.any { it is JudgeEvent.SignalAutoCancel })

        val (short, _) = laneChange(Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND), JudgeMode.EYE, signalToExec = 0.6)
        assertFalse(step(short, Step.SIGNAL_LEFT).done)
        assertEquals(Outcome.LUCKY, short.outcome)

        // wrong side
        val s3 = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val judge = QuestionJudge(s3, JudgeMode.EYE, profile60())
        val d = TestDriver(judge)
        d.signal = Signal.RIGHT
        d.hold(1.5)
        assertEquals(StepState.WRONG, d.last!!.steps.first { it.step == Step.SIGNAL_LEFT }.state)
        d.exec()
        d.finish()
        val r = judge.result()
        assertFalse(step(r, Step.SIGNAL_LEFT).done)
        assertTrue(step(r, Step.SIGNAL_LEFT).wrong)
    }

    @Test
    fun u10_leftHandTrafficMirrorsU1() {
        val rht = laneChange(Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND), JudgeMode.EYE).first
        val lhtScene = Scenes.build(Situation.LANE_LEFT, TrafficSide.LEFT_HAND)
        assertEquals(Dir.RIGHT, lhtScene.dir)
        assertEquals(listOf(Step.INNER_MIRROR, Step.RIGHT_MIRROR, Step.SHOULDER_RIGHT, Step.SIGNAL_RIGHT), lhtScene.requiredSteps)
        // the right mirror of left-hand traffic is the design's left mirror with x negated
        assertEquals(0.70, lhtScene.cabin.mirror(MirrorId.RIGHT)!!.pos.x, 1e-9)
        assertEquals(-0.33, lhtScene.cabin.mirror(MirrorId.INNER)!!.pos.x, 1e-9)
        val lht = laneChange(lhtScene, JudgeMode.EYE).first
        assertEquals(rht.outcome, lht.outcome)
        assertEquals(Outcome.CLEAR, lht.outcome)
        assertEquals(rht.shoulderPeak, lht.shoulderPeak, 1e-9)
        assertEquals(rht.checkTime!!, lht.checkTime!!, 1e-9)
        assertEquals(rht.steps.map { it.done }, lht.steps.map { it.done })
    }

    @Test
    fun u13_sloppyMirrorLooks() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val m = s.cabin.mirror(MirrorId.LEFT)!!
        // eye: 3 cm outside the glass edge (inside the 50 % expansion)
        val n = (Vec3.ZERO - m.pos).normalized()
        val hx = Vec3.UP.cross(n).normalized()
        val off = m.pos + hx * (m.width / 2 + 0.03)
        val judge = QuestionJudge(s, JudgeMode.EYE, profile60())
        val d = TestDriver(judge)
        d.gazeAt(off, 0.2)
        assertEquals(StepState.DONE, d.last!!.steps.first { it.step == Step.LEFT_MIRROR }.state)
        // … but 10 cm outside does not count
        val judgeFar = QuestionJudge(s, JudgeMode.EYE, profile60())
        val dFar = TestDriver(judgeFar)
        dFar.gazeAt(m.pos + hx * (m.width / 2 + 0.10), 0.3)
        assertEquals(StepState.PENDING, dFar.last!!.steps.first { it.step == Step.LEFT_MIRROR }.state)

        // head mode: 18° off the mirror ✓, 24° off ✗
        for ((offDeg, expected) in listOf(18.0 to StepState.DONE, 24.0 to StepState.PENDING)) {
            val j = QuestionJudge(s, JudgeMode.HEAD, profile60())
            val dh = TestDriver(j)
            dh.eyeOff = true
            headLook(dh, m.yawDeg + offDeg, m.pitchDeg)
            assertEquals("offset $offDeg", expected, dh.last!!.steps.first { it.step == Step.LEFT_MIRROR }.state)
            if (expected == StepState.DONE) assertTrue(dh.last!!.steps.first { it.step == Step.LEFT_MIRROR }.lowConfidence)
        }
    }

    @Test
    fun u14_eyeDropoutJudgesThatStepByHead() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val judge = QuestionJudge(s, JudgeMode.EYE, profile60())
        val d = TestDriver(judge)
        d.gazeAt(s.cabin.mirror(MirrorId.INNER)!!.pos, 0.2)
        d.hold(0.2)
        // eyes drop out while looking at the left mirror: the head is on it
        d.eyeOff = true
        headLook(d, -45.0, -8.0)
        d.eyeOff = false
        shoulder(d, -64.0)
        d.signal = Signal.LEFT
        d.hold(1.5)
        d.exec()
        d.finish()
        val r = judge.result()
        assertEquals(JudgeMode.EYE, r.mode)
        assertEquals(Outcome.CLEAR, r.outcome)
        assertTrue(step(r, Step.LEFT_MIRROR).judgedByHead)
        assertTrue(step(r, Step.LEFT_MIRROR).lowConfidence)
        assertFalse(step(r, Step.INNER_MIRROR).judgedByHead)
        assertEquals(listOf(Step.LEFT_MIRROR), r.judgedByHeadSteps)
    }

    @Test
    fun u14_fiveSecondsWithoutEyesShowsNotice() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val judge = QuestionJudge(s, JudgeMode.EYE, profile60())
        val d = TestDriver(judge)
        d.eyeOff = true
        d.hold(4.0)
        assertTrue(d.last!!.eyeDropout < QuestionJudge.EYE_DROPOUT_NOTICE)
        d.hold(1.2)
        assertTrue(d.last!!.eyeDropout >= QuestionJudge.EYE_DROPOUT_NOTICE)
    }

    @Test
    fun u15_headModeShoulderOnlyDoesNotLightMirrors() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND)
        val judge = QuestionJudge(s, JudgeMode.HEAD, profile60())
        val d = TestDriver(judge)
        d.eyeOff = true
        d.turn(-64.0, 0.0, degPerSec = 20.0) // slowly, passing the left mirror direction
        d.hold(0.3)
        d.turn(0.0, 0.0, degPerSec = 20.0)
        d.hold(0.3)
        val live = d.last!!.steps
        assertEquals(StepState.DONE, live.first { it.step == Step.SHOULDER_LEFT }.state)
        assertEquals(StepState.PENDING, live.first { it.step == Step.LEFT_MIRROR }.state)
        assertEquals(StepState.PENDING, live.first { it.step == Step.INNER_MIRROR }.state)

        val s2 = Scenes.build(Situation.LANE_RIGHT, TrafficSide.RIGHT_HAND)
        val judge2 = QuestionJudge(s2, JudgeMode.HEAD, profile60())
        val d2 = TestDriver(judge2)
        d2.eyeOff = true
        d2.turn(70.0, 0.0, degPerSec = 20.0)
        d2.hold(0.3)
        d2.turn(0.0, 0.0, degPerSec = 20.0)
        d2.hold(0.3)
        val live2 = d2.last!!.steps
        assertEquals(StepState.DONE, live2.first { it.step == Step.SHOULDER_RIGHT }.state)
        assertEquals(StepState.PENDING, live2.first { it.step == Step.RIGHT_MIRROR }.state)
        assertEquals(StepState.PENDING, live2.first { it.step == Step.INNER_MIRROR }.state)
    }

    @Test
    fun mirrorSightingCountsAsSeen() {
        // driver seat, (c): a car far back in the left lane is in the left mirror while you look at it
        val spec = HazardSpec(ActorKind.SEDAN, Dir.LEFT, 16.0, 3.0)
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, spec)
        val judge = QuestionJudge(s, JudgeMode.EYE, profile60())
        val d = TestDriver(judge)
        d.gazeAt(s.cabin.mirror(MirrorId.LEFT)!!.pos, 0.3)
        assertTrue(d.last!!.saw)
        assertTrue(d.events.any { it is JudgeEvent.HazardSeen && it.via == SeenVia.MIRROR_LEFT })
    }

    @Test
    fun timeoutAndSkip() {
        val s = Scenes.build(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, timeLimit = 8.0)
        val judge = QuestionJudge(s, JudgeMode.HEAD, profile60())
        val d = TestDriver(judge)
        d.finish(9.0)
        assertEquals(Outcome.TIMEOUT, judge.result().outcome)

        val judge2 = QuestionJudge(Scenes.build(Situation.LANE_RIGHT, TrafficSide.RIGHT_HAND), JudgeMode.HEAD, profile60())
        TestDriver(judge2).hold(1.0)
        assertEquals(Outcome.SKIPPED, judge2.skip(1.0).outcome)
    }

    @Test
    fun lesson1And2Targets() {
        val s = Scenes.build(Situation.MIRRORS, TrafficSide.RIGHT_HAND)
        val judge = QuestionJudge(s, JudgeMode.EYE, profile60())
        val d = TestDriver(judge)
        for (id in listOf(MirrorId.INNER, MirrorId.LEFT, MirrorId.RIGHT)) {
            d.gazeAt(s.cabin.mirror(id)!!.pos, 0.2)
            d.hold(0.1)
        }
        assertEquals(Phase.CHECKING, judge.phase)
        judge.confirmMirror(Dir.LEFT, 9.0, d.t)
        judge.confirmMirror(Dir.RIGHT, 7.0, d.t)
        d.hold(0.1)
        assertEquals(Phase.DONE, judge.phase)
        assertEquals(Outcome.CLEAR, judge.result().outcome)
        assertEquals(9.0, judge.aims.left, 1e-9)

        // lesson 2: the car vanishes from the mirror and waits in the blind spot until you look back
        val s2 = Scenes.blindSpotTrial(TrafficSide.RIGHT_HAND, Dir.LEFT)
        assertNull(s2.hazardPassTime)
        val vanished = Scenes.mirrorExitTime(s2, MirrorAims())!!
        assertTrue(vanished in 1.0..4.0)
        val j2 = QuestionJudge(s2, JudgeMode.HEAD, profile60())
        val d2 = TestDriver(j2)
        d2.eyeOff = true
        d2.until(5.0)
        assertEquals(Phase.CHECKING, j2.phase)
        shoulder(d2, -64.0)
        assertEquals(Phase.DONE, j2.phase)
        assertEquals(Outcome.CLEAR, j2.result().outcome)
    }
}
