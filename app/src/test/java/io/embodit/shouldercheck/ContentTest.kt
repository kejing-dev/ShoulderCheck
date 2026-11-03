package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.ActorRole
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Difficulty
import io.embodit.shouldercheck.domain.DifficultyTracker
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Lessons
import io.embodit.shouldercheck.domain.MirrorAims
import io.embodit.shouldercheck.domain.MiniJson
import io.embodit.shouldercheck.domain.ModelCalibration
import io.embodit.shouldercheck.domain.NoHazardReason
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.PracticeStatus
import io.embodit.shouldercheck.domain.Progress
import io.embodit.shouldercheck.domain.ProgressCodec
import io.embodit.shouldercheck.domain.QuestionRecord
import io.embodit.shouldercheck.domain.SawHazard
import io.embodit.shouldercheck.domain.Scenario
import io.embodit.shouldercheck.domain.Scorecard
import io.embodit.shouldercheck.domain.Settings
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Solvability
import io.embodit.shouldercheck.domain.Step
import io.embodit.shouldercheck.domain.TrafficGenerator
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.TurnRange
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.domain.VolumeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Content (lessons, generator, scorecard), persistence and model calibration: U9, U16, U17, free-mode solvability, D25. */
class ContentTest {
    @Test
    fun u16_turnRangeThresholds() {
        assertEquals(45.0, TurnRange.thresholdFor(50.0), 1e-9)
        assertEquals(53.0, TurnRange.thresholdFor(58.0), 1e-9)
        assertEquals(80.0, TurnRange.thresholdFor(110.0), 1e-9)
        assertEquals(40.0, TurnRange.thresholdFor(45.0), 1e-9)
        assertTrue(TurnRange(45.0, 70.0).needsRemeasure(Dir.LEFT))
        assertFalse(TurnRange(45.0, 70.0).needsRemeasure(Dir.RIGHT))
        assertEquals(60.0, TurnRange.thresholdFor(75.0), 1e-9)
        // skipped: 80° each side, T = 60°
        assertEquals(60.0, TurnRange.SKIPPED.threshold(Dir.LEFT), 1e-9)
        assertEquals(80.0, TurnRange.SKIPPED.maxRight, 1e-9)
    }

    private fun rec(i: Int, outcome: Outcome, sit: Situation = Situation.LANE_LEFT) =
        QuestionRecord(1000L + i, sit, outcome, null, JudgeMode.EYE, 60.0 + i, 60.0, 2.0 + i * 0.1, SawHazard.NO_HAZARD, mapOf(Step.SHOULDER_LEFT to true))

    @Test
    fun u9_scorecardSteadyAndPractice() {
        // last 10: 9 correct, 2 of them well-waited → steady
        val nine = (0 until 10).map { i -> rec(i, when (i) { 0 -> Outcome.NEAR_MISS; 1, 2 -> Outcome.WAITED; else -> Outcome.CLEAR }) }
        val row = Scorecard.row(Situation.LANE_LEFT, nine)
        assertEquals(10, row.attempts)
        assertEquals(9, row.correct)
        assertEquals(PracticeStatus.STEADY, row.status)
        assertEquals(1, row.nearMisses)
        // 8 correct → practising
        val eight = (0 until 10).map { i -> rec(i, if (i < 2) Outcome.LUCKY else Outcome.CLEAR) }
        assertEquals(PracticeStatus.PRACTICE, Scorecard.row(Situation.LANE_LEFT, eight).status)
        // only the last 10 count; skipped ones never count
        val old = (0 until 5).map { rec(it - 100, Outcome.NEAR_MISS) } + nine + rec(50, Outcome.SKIPPED)
        assertEquals(PracticeStatus.STEADY, Scorecard.row(Situation.LANE_LEFT, old).status)
        assertEquals(PracticeStatus.NOT_YET, Scorecard.row(Situation.DOOR_REAR, nine).status)
        assertEquals(6, Scorecard.rows(nine).size)
        assertNotNull(Scorecard.row(Situation.LANE_LEFT, nine).medianShoulder)
    }

    @Test
    fun lessonsMatchDesign() {
        assertEquals(8, Lessons.ALL.size)
        assertEquals((1..8).toList(), Lessons.ALL.map { it.n })
        for (l in Lessons.ALL.drop(2)) {
            assertEquals(3, l.trials)
            assertEquals(2, l.passNeeded)
            assertTrue(l.passed(listOf(Outcome.CLEAR, Outcome.LUCKY, Outcome.WAITED)))
            assertFalse(l.passed(listOf(Outcome.CLEAR, Outcome.LUCKY, Outcome.NEAR_MISS)))
        }
        val range = TurnRange(80.0, 80.0)
        for (n in 1..8) {
            val trials = TrafficGenerator.lessonTrials(n, 7L, TrafficSide.RIGHT_HAND, range)
            assertEquals(Lessons.byN(n).trials, trials.size)
            assertTrue(trials.all { it.situation == Lessons.byN(n).situation })
            if (n >= 3 && n != 4) assertEquals("lesson $n hazard trials", 2, trials.count { it.hazard != null })
        }
        // lesson 2: driver side first
        assertEquals(Dir.LEFT, Dir.ofSign(TrafficGenerator.lessonTrials(2, 1L, TrafficSide.RIGHT_HAND, range)[0].hazard!!.x))
        assertEquals(Dir.RIGHT, Dir.ofSign(TrafficGenerator.lessonTrials(2, 1L, TrafficSide.LEFT_HAND, range)[0].hazard!!.x))
    }

    /** Asserts the free-mode solvability rule on one scenario. */
    private fun assertSolvable(s: Scenario, range: TurnRange, label: String) {
        val h = s.hazard ?: return
        val sol = Solvability.check(s, range, MirrorAims())
        assertTrue("$label not solvable: $sol", sol.ok)
        assertTrue("$label visible ${sol.visibleRun}", sol.visibleRun >= 1.5)
        assertTrue("$label passes in ${sol.passDuration}", sol.passDuration!! <= 6.0)
        // the hazard really can be seen in the band / mirror in that window (independent re-check at the entry − 0.1)
        assertTrue(label, sol.entryTime!! > 0)
        // distractors never get into the conflict zone
        for (a in s.actors.filter { it.role == ActorRole.DISTRACTOR }) {
            var t = 0.0
            while (t < 20.0) {
                assertFalse("$label distractor", s.inConflictZone(a.box(t)))
                t += 0.1
            }
        }
        assertTrue(h.role == ActorRole.HAZARD)
    }

    @Test
    fun u17_rearSeatSolvability() {
        for (max in listOf(60.0, 90.0, 130.0)) {
            val range = TurnRange(max, max)
            var hazards = 0
            var rangeNotes = 0
            for (seed in 0 until 200) {
                val lessonTrials = TrafficGenerator.lessonTrials(4, seed.toLong(), TrafficSide.RIGHT_HAND, range)
                val free = TrafficGenerator.question(seed.toLong(), 0, 1 + seed % 5, TrafficSide.RIGHT_HAND, range, situation = Situation.DOOR_REAR)
                for (s in lessonTrials + free) {
                    if (s.hazard != null) {
                        hazards++
                        assertSolvable(s, range, "door-rear max=$max seed=$seed")
                    } else if (s.noHazardReason == NoHazardReason.RANGE) {
                        rangeNotes++
                    }
                }
            }
            if (max < 100) {
                assertEquals("max=$max must give no hazard questions", 0, hazards)
                assertTrue(rangeNotes > 0)
            } else {
                assertTrue("max=$max should give hazard questions", hazards > 200)
            }
        }
    }

    @Test
    fun freeModeSolvabilityAllSituationsSeatsRanges() {
        for (sit in Situation.PRACTICE) {
            for (max in listOf(60.0, 90.0, 130.0)) {
                val range = TurnRange(max, max)
                var hazards = 0
                for (seed in 0 until 200) {
                    val level = 1 + seed % 5
                    val s = TrafficGenerator.question(seed.toLong() * 31 + 5, seed % 10, level, TrafficSide.RIGHT_HAND, range, situation = sit)
                    assertEquals(sit.seat, s.seat)
                    if (level == 5) assertEquals(8.0, s.timeLimit!!, 1e-9)
                    if (s.hazard != null) {
                        hazards++
                        assertSolvable(s, range, "${sit.id} max=$max seed=$seed L$level")
                        if (s.timeLimit != null) assertTrue(s.hazardPassTime!! <= s.timeLimit!! - 2.5)
                    }
                }
                if (sit.seat == io.embodit.shouldercheck.domain.Seat.DRIVER) assertTrue("${sit.id} max=$max hazards=$hazards", hazards > 60)
            }
        }
    }

    @Test
    fun generatorIsSeededAndMirrorsForLeftHandTraffic() {
        val range = TurnRange(80.0, 80.0)
        val a = TrafficGenerator.question(42L, 3, 2, TrafficSide.RIGHT_HAND, range)
        val b = TrafficGenerator.question(42L, 3, 2, TrafficSide.RIGHT_HAND, range)
        assertEquals(a.situation, b.situation)
        assertEquals(a.actors.map { it.x to it.keys }, b.actors.map { it.x to it.keys })
        val l = TrafficGenerator.question(42L, 3, 2, TrafficSide.LEFT_HAND, range)
        assertEquals(a.situation, l.situation)
        assertEquals(a.hazard?.x?.let { -it }, l.hazard?.x)
        // difficulty: 3 correct → up, a near miss → down
        val d = DifficultyTracker()
        repeat(3) { d.onResult(Outcome.CLEAR) }
        assertEquals(2, d.level)
        d.onResult(Outcome.NEAR_MISS)
        assertEquals(1, d.level)
        assertEquals(5, Difficulty.LEVELS.size)
    }

    @Test
    fun progressRoundTrip() {
        val p =
            Progress(
                settings = Settings(Language.EN, TrafficSide.LEFT_HAND, showGaze = false, hintsLessons = true, hintsFree = true, volume = VolumeLevel.HIGH, muted = true),
                range = TurnRange(72.0, 68.0),
                aims = MirrorAims(9.0, 6.0),
                blindSpotLeft = 5.1,
                blindSpotRight = 4.8,
                demoSeen = setOf(1, 3),
                passed = setOf(1),
                records = (0 until 5).map { rec(it, Outcome.WAITED) } + rec(9, Outcome.SKIPPED, Situation.DOOR_REAR).copy(checkSec = null, lesson = 4),
            )
        val back = ProgressCodec.decode(ProgressCodec.encode(p))
        assertEquals(p.settings, back.settings)
        assertEquals(p.range, back.range)
        assertEquals(p.aims, back.aims)
        assertEquals(p.blindSpotLeft, back.blindSpotLeft)
        assertEquals(p.demoSeen, back.demoSeen)
        assertEquals(p.passed, back.passed)
        assertEquals(p.records, back.records)
        // broken input → defaults, never a crash
        assertEquals(Progress(), ProgressCodec.decode("{not json"))
        assertEquals(Progress(), ProgressCodec.decode(null))
        // 300 records max
        var q = Progress()
        repeat(320) { q = q.withRecord(rec(it, Outcome.CLEAR)) }
        assertEquals(300, q.records.size)
        assertEquals(1000L + 20, q.records.first().atMs)
        assertEquals(q.settings, q.cleared().settings)
        assertTrue(q.cleared().records.isEmpty())
    }

    @Test
    fun miniJson() {
        val v = MiniJson.parse("""{"a":[1,2.5,-3e2],"b":"x\"yé","c":true,"d":null,"e":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("x\"yé", v["b"])
        assertEquals(true, v["c"])
        assertTrue(v.containsKey("d"))
        assertEquals(v, MiniJson.parse(MiniJson.write(v)))
    }

    @Test
    fun d25_calibrationMath() {
        // a model 0.5 × 0.2 × 0.15 units, front along +X, off-centre, design 4.6 × 1.8 × 1.45 (consistent proportions)
        val lo = Vec3(-0.21, 0.03, -0.12)
        val hi = Vec3(0.29, 0.03 + 0.5 * 1.45 / 4.6, -0.12 + 0.5 * 1.8 / 4.6)
        for (axis in listOf("+X", "-X", "+Z", "-Z")) {
            val f = ModelCalibration.axisVector(axis)!!
            // the bbox extent along the front must be the length: swap x/z when the front is along z
            val (bmin, bmax) = if (axis.endsWith("Z")) Vec3(lo.z, lo.y, lo.x) to Vec3(hi.z, hi.y, hi.x) else lo to hi
            val c = ModelCalibration.compute("sedan", bmin, bmax, f, 4.6, 1.8, 1.45)
            val bc = c.bottomCentre()
            assertTrue("$axis centre $bc", bc.length < 0.01)
            assertTrue("$axis forward", c.forwardError(f) < 2.0)
            val size = c.size()
            assertTrue(ModelCalibration.relError(size.z, 4.6) < 0.10)
            assertTrue(ModelCalibration.relError(size.x, 1.8) < 0.10)
            assertTrue(ModelCalibration.relError(size.y, 1.45) < 0.10)
            assertEquals(0.0, c.calibratedBounds().first.y, 0.01) // standing on the road
        }
        // a front at an odd yaw (model turned 30°): still within 2°
        val f30 = Angles.dir(30.0)
        val c30 = ModelCalibration.compute("bike", Vec3(-0.4, 0.0, -0.4), Vec3(0.4, 1.0, 0.4), f30, 1.75)
        assertTrue(c30.forwardError(f30) < 2.0)
        assertTrue(c30.bottomCentre().length < 0.01)
    }

    @Test
    fun d25_calibrationJsonOfTheShippedModels() {
        val file = listOf(File("src/main/assets/models/calibration.json"), File("app/src/main/assets/models/calibration.json")).firstOrNull { it.exists() } ?: return fail("calibration.json not found")
        val text = file.readText()
        val all = ModelCalibration.parseAll(text)
        val root = MiniJson.parse(text) as Map<*, *>
        for (kind in io.embodit.shouldercheck.domain.ActorKind.values()) {
            val role = kind.model ?: continue
            val c = all[role] ?: continue
            val entry = root[role] as Map<*, *>
            val f = ModelCalibration.axisVector(entry["forwardAxis"] as String)!!
            assertTrue("$role centre", c.bottomCentre().length < 0.01)
            assertTrue("$role forward", c.forwardError(f) < 2.0)
            assertTrue("$role length", ModelCalibration.relError(c.size().z, kind.length) < 0.10)
            // recomputing from bbox + forward axis + design length gives the same TRS as the tool wrote
            val again = ModelCalibration.compute(role, c.bboxMin, c.bboxMax, f, kind.length)
            assertEquals(role, c.scale, again.scale, c.scale * 0.01)
            assertEquals(0.0, Angles.wrap(c.yawDeg - again.yawDeg), 0.5)
            assertTrue(again.translation.distanceTo(c.translation) < 0.01)
        }
        assertNull(all["blanking"])
    }
}
