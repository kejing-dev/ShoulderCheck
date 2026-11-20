package io.embodit.shouldercheck.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mustPass M9 (design `rounds.honest`): 200 seeds × 4 levels (+ today's run, + left-hand traffic), every round is
 * honest — a gap ≥ 2.5 s in the window; hazards / traps dangerous when the button lights; trap budget
 * 1 s + dwell + 1.5 s + 2.5 s ≤ window; a trap is out of both mirrors yet seen by a shoulder check at the lowest
 * threshold for ≥ 1.5 s. The generator never had to fall back to a clear round (the plan's kind is kept).
 */
class GameGenTest {
    private fun checkLevel(level: Level, side: TrafficSide, seeds: Int, daily: Boolean = false) {
        val bad = ArrayList<String>()
        var traps = 0
        var gapSum = 0.0
        var rounds = 0
        for (seed in 1..seeds) {
            val plan = GamePlan(level, seed.toLong() * 7919L, side, daily = daily, firstEver = seed == 1)
            assertEquals(GamePlan.ROUNDS, plan.kinds.size)
            assertEquals("traps per game L${level.n}", level.traps, plan.kinds.count { it == RoundKind.TRAP })
            for (i in 0 until GamePlan.ROUNDS) assertTrue("no door trap", !(plan.kinds[i] == RoundKind.TRAP && plan.situations[i] == Situation.DOOR))
            val clear = plan.kinds.count { it == RoundKind.CLEAR }
            assertTrue("1–2 clear rounds, got $clear", clear in 1..2)
            for (i in 0 until GamePlan.ROUNDS) {
                val r = plan.round(i)
                rounds++
                if (r.kind != plan.kinds[i]) bad += "L${level.n} seed=$seed round=$i ${plan.situations[i]} fell back ${plan.kinds[i]} -> ${r.kind}"
                val p = RoundCheck.problems(r)
                if (p.isNotEmpty()) bad += "L${level.n} seed=$seed round=$i ${r.situation} ${r.kind}: $p"
                if (r.kind == RoundKind.TRAP) traps++
                r.firstGap()?.let { gapSum += it.second - it.first }
            }
        }
        println("L${level.n} $side daily=$daily rounds=$rounds traps=$traps meanFirstGap=${"%.2f".format(gapSum / rounds)} bad=${bad.size}")
        assertTrue(bad.take(12).joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun level1_200seeds() = checkLevel(Levels.of(1), TrafficSide.RIGHT_HAND, 200)

    @Test
    fun level2_200seeds() = checkLevel(Levels.of(2), TrafficSide.RIGHT_HAND, 200)

    @Test
    fun level3_200seeds() = checkLevel(Levels.of(3), TrafficSide.RIGHT_HAND, 200)

    @Test
    fun level4_200seeds() = checkLevel(Levels.of(4), TrafficSide.RIGHT_HAND, 200)

    @Test
    fun daily_200seeds() = checkLevel(Levels.daily(), TrafficSide.RIGHT_HAND, 200, daily = true)

    @Test
    fun leftHandTraffic_100seedsEachLevel() {
        for (n in 1..4) checkLevel(Levels.of(n), TrafficSide.LEFT_HAND, 100)
    }

    @Test
    fun firstGameStartsWithAClearLaneChangeToTheLeft() {
        val plan = GamePlan(Levels.of(1), 42L, TrafficSide.RIGHT_HAND, daily = false, firstEver = true)
        assertEquals(RoundKind.CLEAR, plan.kinds[0])
        assertEquals(Situation.LANE_LEFT, plan.situations[0])
        for (seed in 1..200) assertTrue(GamePlan(Levels.of(3), seed.toLong(), TrafficSide.RIGHT_HAND, false, false).kinds[0] != RoundKind.TRAP)
    }

    @Test
    fun sameSeedSameRounds() {
        val a = GamePlan(Levels.daily(), 20261119L, TrafficSide.RIGHT_HAND, daily = true, firstEver = false)
        val b = GamePlan(Levels.daily(), 20261119L, TrafficSide.RIGHT_HAND, daily = true, firstEver = false)
        assertEquals(a.kinds, b.kinds)
        assertEquals(a.situations, b.situations)
        for (i in 0 until GamePlan.ROUNDS) assertEquals(a.round(i).actors, b.round(i).actors)
    }

    @Test
    fun trapIsInTheBlindSpotAndHiddenFromTheMirror() {
        val r = RoundGenerator.debugRound(Levels.of(1), Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, ActorKind.EBIKE.let { ActorKind.SEDAN }, 3.6, 0.0, trap = true)
        val box = r.hazard!!.box(1.5)
        assertTrue("debug trap rear 3.6 m behind: blind spot", BlindSpot.isInBlindSpot(box, Dir.LEFT, MirrorAims(), r.cabin, 0.0))
        assertTrue("seen by a 60° head turn", Visibility.anyInView(box, r.cabin, -60.0, Visibility.JUDGE_HALF_FOV))
        assertNotNull("go while it sits there: close call", r.conflictAt(1.5))
    }

    @Test
    fun conflictRules() {
        // a car 30 m back at +3 m/s: fine to change lanes now (gap 30 − 4.6 − 2.5 − 6 ≥ 3·2 + 5)
        val far = RoundGenerator.debugRound(Levels.of(1), Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, ActorKind.SEDAN, 30.0, 3.0, trap = false)
        assertNull(far.conflictAt(1.0))
        // 14 m back at +6 m/s: it would have to brake → close call
        val near = RoundGenerator.debugRound(Levels.of(1), Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, ActorKind.SEDAN, 14.0, 6.0, trap = false)
        assertNotNull(near.conflictAt(1.0))
        // door: an e-bike 10 m back at 5 m/s (5 m back when you tap at t = 1) reaches the opening door → close call;
        // 40 m back → fine
        val bike = RoundGenerator.debugRound(Levels.of(4), Situation.DOOR, TrafficSide.RIGHT_HAND, ActorKind.EBIKE, 10.0, 5.0, trap = false)
        assertNotNull(bike.conflictAt(1.0))
        val bikeFar = RoundGenerator.debugRound(Levels.of(4), Situation.DOOR, TrafficSide.RIGHT_HAND, ActorKind.EBIKE, 40.0, 5.0, trap = false)
        assertNull(bikeFar.conflictAt(1.0))
        // a car in the lane beyond the bike lane never meets the door
        val car = RoundGenerator.debugRound(Levels.of(4), Situation.DOOR, TrafficSide.RIGHT_HAND, ActorKind.SEDAN, 3.0, 5.0, trap = false)
        for (i in 0..40) assertNull(car.conflictAt(i * 0.25))
    }

    @Test
    fun egoMoveIsSlowAndSmooth() {
        // D43: lane move 2 s ease in / out; pull-out accelerates for 2 s (≥ 1 s); door 1 s to 35°
        assertEquals(0.0, EgoMotion.lateral(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, 0.0), 1e-9)
        assertEquals(-Road.LANE_WIDTH, EgoMotion.lateral(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, 2.0), 1e-9)
        assertEquals(-Road.LANE_WIDTH / 2, EgoMotion.lateral(Situation.LANE_LEFT, TrafficSide.RIGHT_HAND, 1.0), 1e-9)
        assertEquals(Road.LANE_WIDTH, EgoMotion.lateral(Situation.LANE_LEFT, TrafficSide.LEFT_HAND, 2.0), 1e-9)
        assertEquals(-4.0, EgoMotion.forward(Situation.PULL_OUT, 2.0), 1e-9)
        assertEquals(0.0, EgoMotion.forward(Situation.LANE_LEFT, 2.0), 1e-9)
        assertEquals(35.0, DoorModel.angleAt(1.0), 1e-9)
        assertEquals(12.0, DoorModel.angleAt(1.0, stopAt = DoorModel.STOP_DEG), 1e-9)
        assertEquals(0.0, DoorModel.angleAt(null), 1e-9)
    }
}
