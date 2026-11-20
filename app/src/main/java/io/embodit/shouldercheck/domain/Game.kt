package io.embodit.shouldercheck.domain

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The blind-spot game of v0.2.0 (design `game`, `rounds`, `scoring`, `judging.conflict`): 8 rounds a game, each round a
 * few seconds of real traffic in which you check the mirror, look over your shoulder and tap 「走」 when the way is clear.
 * Pure Kotlin; JVM tests in GameGenTest / ChecksTest / ScoringTest.
 *
 * Round time t: 0 = the scenario card appears (traffic already moving); the 「走」 button lights at [GO_LIT]; the round
 * times out at [Round.window]. Coordinates: car frame of the driver's eye at the start of the round (see AGENTS.md).
 */
const val GO_LIT = 1.0

/** Gaps shorter than this are not gaps (design `rounds.gaps`). */
const val MIN_GAP = 2.5

/** Time step of the conflict table (s). */
const val TABLE_STEP = 0.1

/** One level (design `scoring.levels`; gate B: four levels, rain / night / reversing in v2). */
data class Level(
    val n: Int,
    val situations: List<Situation>,
    /** Relative speed of the approaching traffic (m/s, + = faster than you). */
    val speedMin: Double,
    val speedMax: Double,
    /** Follower spacing factor (L2+: half the gap of L1). */
    val spacing: Double,
    /** Timer window (s), card included. */
    val window: Double,
    val traps: Int,
    /** 「镜」「回头」 lights shown (L1–L3). */
    val checkChips: Boolean,
    /** Dusk lighting (L4). */
    val dusk: Boolean,
)

object Levels {
    private val LANE = listOf(Situation.LANE_LEFT, Situation.LANE_RIGHT)

    val ALL: List<Level> =
        listOf(
            Level(1, LANE, 3.0, 6.0, 1.0, 14.0, 1, checkChips = true, dusk = false),
            Level(2, LANE + Situation.PULL_OUT, 4.0, 8.0, 0.5, 12.0, 2, checkChips = true, dusk = false),
            Level(3, LANE + listOf(Situation.PULL_OUT, Situation.MERGE), 6.0, 10.0, 0.5, 11.0, 3, checkChips = true, dusk = false),
            Level(4, LANE + listOf(Situation.PULL_OUT, Situation.MERGE, Situation.DOOR), 6.0, 10.0, 0.5, 11.0, 3, checkChips = false, dusk = true),
        )

    const val COUNT = 4
    const val DAILY_LEVEL = 2

    fun of(n: Int): Level = ALL[(n - 1).coerceIn(0, ALL.size - 1)]

    /**
     * Today's run (design `scoring.daily`): seed = local date, difficulty fixed at level 2 (it does not follow your
     * progress), the four situations mixed. Copy says 「今天这一套」.
     */
    fun daily(): Level = of(DAILY_LEVEL).copy(situations = LANE + listOf(Situation.PULL_OUT, Situation.MERGE, Situation.DOOR))
}

enum class RoundKind {
    /** Only distant traffic: check and go (reaction score). */
    CLEAR,

    /** A road user comes up the target lane: wait for it, then go. */
    HAZARD,

    /** A road user sits in your blind spot (not in the mirror, only a shoulder check shows it), then leaves. */
    TRAP,
}

/** What a conflict was: who, and how long after 「走」. */
data class Conflict(val actorId: String, val tau: Double)

/** Your own car's move after 「走」 (design `model.egoMotion`, D43: slow, ease in / out, ≥ 1 s acceleration). */
object EgoMotion {
    const val LANE_TIME = 2.0
    const val PULL_ACCEL = 2.0
    const val EGO_WIDTH = 1.82
    const val EGO_HEIGHT = 1.45

    /** Cruising speed of your car (36 km/h) — scenery only: the traffic scripts are relative to you. */
    const val CRUISE = 10.0

    fun ease(u: Double): Double {
        val c = u.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    /** How long the judge follows the move (lane: 2 s; door: open 1 s + watch 1 s). */
    fun duration(s: Situation): Double = if (s == Situation.DOOR) DoorModel.OPEN_TIME + DoorModel.WATCH_TIME else LANE_TIME

    /** Sideways offset of your car [tau] s after 「走」 (physical x, m). */
    fun lateral(s: Situation, side: TrafficSide, tau: Double): Double =
        if (!s.lane || tau <= 0) 0.0 else s.dir(side).sign * Road.LANE_WIDTH * ease(tau / LANE_TIME)

    /** Forward offset (z, negative = ahead) [tau] s after 「走」: only the pull-out moves forward in the round's frame. */
    fun forward(s: Situation, tau: Double): Double {
        if (s != Situation.PULL_OUT || tau <= 0) return 0.0
        val a = min(tau, LANE_TIME)
        val cruise = max(0.0, tau - LANE_TIME)
        return -(0.5 * PULL_ACCEL * a * a + PULL_ACCEL * LANE_TIME * cruise)
    }

    /** Your forward speed at the end of the move, in the round's frame (pull-out 4 m/s; cruising scenes 0 = relative). */
    fun endSpeed(s: Situation): Double = if (s == Situation.PULL_OUT) PULL_ACCEL * LANE_TIME else 0.0

    /** Your car's box [tau] s after 「走」 (null tau = not moved). */
    fun egoBox(s: Situation, cabin: Cabin, side: TrafficSide, tau: Double?): Box {
        val t = tau ?: 0.0
        val len = cabin.egoRearZ - cabin.egoFrontZ
        val cz = (cabin.egoFrontZ + cabin.egoRearZ) / 2
        return Box(Vec3(cabin.carCentreX + lateral(s, side, t), Road.ROAD_Y, cz + forward(s, t)), len, EGO_WIDTH, EGO_HEIGHT)
    }
}

/**
 * One generated round: the situation, its road users (scripted f(t)), which one is the hazard, and the conflict table
 * (design `judging.conflict`): tapping 「走」 at time t is a close call when your move (lane: 2 s sideways; door: open
 * 1 s + 1 s watch) touches any road user's box grown by 0.3 m, or — lane moves — ends closer to a car behind in your new
 * lane than its closing speed × 2 s + 5 m. A gap = the times at which 「走」 passes.
 */
class Round(
    val situation: Situation,
    val side: TrafficSide,
    val kind: RoundKind,
    val actors: List<Actor>,
    val hazardId: String?,
    val window: Double,
    val seed: Long,
    val levelN: Int,
    /** How long the trap sits in the blind spot (s), for the honest-budget test. */
    val trapDwell: Double = 0.0,
) {
    val cabin = Cabin(Seat.DRIVER, side)
    val road = RoadLayout(situation, side)
    val dir: Dir = situation.dir(side)
    val hazard: Actor? = actors.firstOrNull { it.id == hazardId }
    val mirror: MirrorSpec = cabin.mirror(MirrorId.side(dir))!!

    /** Conflict of a 「走」 at [tGo], or null when it is safe. */
    fun conflictAt(tGo: Double): Conflict? = Conflicts.at(this, tGo)

    /** free[i] ⇔ 「走」 at t = i · [TABLE_STEP] is safe; covers 0 … window + 3 s. */
    val free: BooleanArray by lazy {
        val n = ((window + 3.0) / TABLE_STEP).toInt() + 1
        BooleanArray(n) { i -> conflictAt(i * TABLE_STEP) == null }
    }

    fun freeAt(t: Double): Boolean {
        val i = Math.round(t / TABLE_STEP).toInt()
        return if (i < 0) free[0] else if (i >= free.size) free.last() else free[i]
    }

    /** The free run containing [t] as (start, end), or null when [t] is not free. */
    fun gapAround(t: Double): Pair<Double, Double>? {
        val i0 = Math.round(t / TABLE_STEP).toInt().coerceIn(0, free.size - 1)
        if (!free[i0]) return null
        var a = i0
        while (a > 0 && free[a - 1]) a--
        var b = i0
        while (b < free.size - 1 && free[b + 1]) b++
        return a * TABLE_STEP to b * TABLE_STEP
    }

    /** The first gap of at least [minLen] s that starts in [from] … window − minLen (null = none). */
    fun firstGap(from: Double = GO_LIT, minLen: Double = MIN_GAP): Pair<Double, Double>? {
        var i = Math.round(from / TABLE_STEP).toInt()
        val last = Math.round((window - minLen) / TABLE_STEP).toInt()
        while (i <= last && i < free.size) {
            if (free[i]) {
                val g = gapAround(i * TABLE_STEP)!!
                val start = max(g.first, from)
                if (g.second - start >= minLen - 1e-9) return start to g.second
                i = Math.round(g.second / TABLE_STEP).toInt() + 1
            } else i++
        }
        return null
    }

    companion object {
        /** Scripts run this long (s); a round never lasts longer than 30 s. */
        const val HORIZON = 60.0
    }

    /** True when there is danger at some time in [GO_LIT] … [until) (a tap there would be a close call). */
    fun dangerBefore(until: Double): Boolean {
        var t = GO_LIT
        while (t < until - 1e-9) {
            if (!freeAt(t)) return true
            t += TABLE_STEP
        }
        return false
    }
}

object Conflicts {
    const val MARGIN = 0.3
    const val FOLLOW_SEC = 2.0
    const val FOLLOW_EXTRA = 5.0

    fun at(r: Round, tGo: Double): Conflict? = if (r.situation == Situation.DOOR) door(r, tGo) else lane(r, tGo)

    private fun door(r: Round, tGo: Double): Conflict? {
        var tau = 0.0
        val end = EgoMotion.duration(Situation.DOOR)
        while (tau <= end + 1e-9) {
            val deg = DoorModel.angleAt(tau)
            for (a in r.actors) if (DoorModel.conflicts(r.cabin, deg, a.box(tGo + tau))) return Conflict(a.id, tau)
            tau += TABLE_STEP
        }
        return null
    }

    private fun lane(r: Round, tGo: Double): Conflict? {
        val s = r.situation
        var tau = 0.0
        while (tau <= EgoMotion.LANE_TIME + 1e-9) {
            val ego = EgoMotion.egoBox(s, r.cabin, r.side, tau)
            val fp = ego.footprint()
            for (a in r.actors) {
                val b = a.box(tGo + tau)
                if (abs(b.centre.z - ego.centre.z) > (b.length + ego.length) / 2 + 1.0) continue
                if (abs(b.centre.x - ego.centre.x) > (b.width + ego.width) / 2 + 1.0) continue
                if (Geometry2D.overlap(fp, b.grown(MARGIN).footprint())) return Conflict(a.id, tau)
            }
            tau += TABLE_STEP
        }
        // the end of the move: a car behind you in your new lane must not have to brake
        val end = EgoMotion.egoBox(s, r.cabin, r.side, EgoMotion.LANE_TIME)
        val egoRear = end.centre.z + end.length / 2
        val tEnd = tGo + EgoMotion.LANE_TIME
        for (a in r.actors) {
            val b = a.box(tEnd)
            if (abs(b.centre.x - end.centre.x) > (b.width + end.width) / 2 + MARGIN) continue
            val front = b.centre.z - b.length / 2
            if (front < egoRear) continue
            val approach = -a.velocityZ(tEnd) - EgoMotion.endSpeed(s)
            if (front - egoRear < max(approach, 0.0) * FOLLOW_SEC + FOLLOW_EXTRA) return Conflict(a.id, EgoMotion.LANE_TIME)
        }
        return null
    }
}

/**
 * Head-yaw lower bounds of the shoulder check (design `judging.shoulder`): always ≥ the same-side mirror's azimuth
 * + 10°, so looking at the mirror never counts as looking back. Near = your door's side (left in right-hand traffic).
 */
object ShoulderBounds {
    const val HEAD_NEAR_DEFAULT = 65.0
    const val HEAD_FAR_DEFAULT = 80.0
    const val EYE_NEAR_DEFAULT = 80.0
    const val EYE_FAR_DEFAULT = 95.0
    const val HEAD_NEAR_LOW = 60.0
    const val HEAD_FAR_LOW = 77.0
    const val EYE_NEAR_LOW = 70.0
    const val EYE_FAR_LOW = 87.0
    const val ABOVE_DEFAULT = 10.0

    fun low(eye: Boolean, far: Boolean): Double = if (eye) (if (far) EYE_FAR_LOW else EYE_NEAR_LOW) else (if (far) HEAD_FAR_LOW else HEAD_NEAR_LOW)

    fun default(eye: Boolean, far: Boolean): Double = if (eye) (if (far) EYE_FAR_DEFAULT else EYE_NEAR_DEFAULT) else (if (far) HEAD_FAR_DEFAULT else HEAD_NEAR_DEFAULT)

    fun high(eye: Boolean, far: Boolean): Double = default(eye, far) + ABOVE_DEFAULT
}

/**
 * Seeded round generator. Every round it returns is honest (design `rounds.honest`, checked by [RoundCheck] and
 * GameGenTest over 200 seeds × 4 levels): ① a gap ≥ 2.5 s inside the window; ② card 1 s + trap dwell + 1.5 s check + gap
 * ≤ window; ③ a trap is out of both mirrors for ≥ 1.5 s while a shoulder check at the lowest threshold sees it past the
 * pillars; hazards and traps are dangerous when the button lights, the gap comes after they leave.
 */
object RoundGenerator {
    private val AIMS = MirrorAims()
    const val ATTEMPTS = 120

    fun mix(seed: Long, index: Int): Long = seed * 6364136223846793005L + index * 1442695040888963407L + 0x2545F4914F6CDD1DL

    fun generate(level: Level, situation: Situation, kind: RoundKind, side: TrafficSide, seed: Long): Round {
        val rng = Random(seed)
        for (attempt in 0 until ATTEMPTS) {
            val r = attemptRound(level, situation, kind, side, rng, seed) ?: continue
            if (RoundCheck.problems(r).isEmpty()) return r
        }
        // never seen in the 200-seed test; a plain clear round is always honest
        return attemptRound(level, situation, RoundKind.CLEAR, side, Random(seed xor 0x5DEECE66DL), seed)!!
    }

    /** Debug `hazard=… side=L|R z=… v=… trap=0|1`: a hand-placed hazard in the target lane plus the scene's decor. */
    fun debugRound(level: Level, situation: Situation, side: TrafficSide, kind: ActorKind?, rearZ: Double, speed: Double, trap: Boolean, seed: Long = 1L): Round {
        val road = RoadLayout(situation, side)
        val dir = situation.dir(side)
        val actors = ArrayList<Actor>(decor(situation, side, road, Random(seed)))
        var hazardId: String? = null
        if (kind != null) {
            val x = road.hazardX(kind, dir)
            val c0 = rearZ - kind.length / 2
            val keys = if (trap) listOf(Key(0.0, c0), Key(3.0, c0), Key(4.0, c0 - 2.0), Key(60.0, c0 - 2.0 - 4.0 * 56)) else listOf(Key(0.0, c0), Key(60.0, c0 - speed * 60))
            actors += Actor("hazard", kind, x, keys, ActorRole.HAZARD, 1)
            hazardId = "hazard"
        }
        return Round(situation, side, if (kind == null) RoundKind.CLEAR else if (trap) RoundKind.TRAP else RoundKind.HAZARD, actors, hazardId, level.window, seed, level.n, if (trap) 3.0 else 0.0)
    }

    private fun attemptRound(level: Level, s: Situation, kind: RoundKind, side: TrafficSide, rng: Random, seed: Long): Round? {
        val road = RoadLayout(s, side)
        val dir = s.dir(side)
        val actors = ArrayList<Actor>(decor(s, side, road, rng))
        val static = !s.cruising
        return when (kind) {
            RoundKind.CLEAR -> {
                // distant traffic only: one car well ahead in the target lane pulling away
                if (s.lane) {
                    val k = pick(rng, ActorKind.SEDAN, ActorKind.SUV)
                    val z = -(18.0 + rng.nextDouble() * 14.0)
                    val v = if (static) 9.0 + rng.nextDouble() * 3.0 else 1.0 + rng.nextDouble() * 2.0
                    actors += moving("far", k, road.hazardX(k, dir), z, v, ActorRole.TRAFFIC, rng.nextInt(4))
                }
                Round(s, side, kind, actors, null, level.window, seed, level.n)
            }
            RoundKind.HAZARD -> {
                val k = hazardKind(s, rng)
                val v = hazardSpeed(level, s, k, rng)
                // the hazard reaches you a few seconds into the window, close enough to be a danger when the button
                // lights (its front within closing speed × 2 s + 5 m of your rear at the end of a move started then)
                val arriveMax = min(level.window - 4.5, 5.0 + (6.5 + k.length) / v - 0.2)
                val arrive = 2.0 + rng.nextDouble() * (arriveMax - 2.0).coerceAtLeast(0.2)
                val rear0 = v * arrive + 1.0
                actors += moving("hazard", k, road.hazardX(k, dir), rear0 - k.length / 2, v, ActorRole.HAZARD, rng.nextInt(4))
                // sometimes a follower closes the gap a few seconds later (timing matters)
                if (rng.nextDouble() < 0.6) {
                    val gapSec = 2.6 + rng.nextDouble() * 4.0 * level.spacing
                    val kf = hazardKind(s, rng)
                    val rearF = rear0 + v * (gapSec + 4.5) + kf.length
                    actors += moving("follow", kf, road.hazardX(kf, dir), rearF - kf.length / 2, v, ActorRole.TRAFFIC, rng.nextInt(4))
                }
                Round(s, side, kind, actors, "hazard", level.window, seed, level.n)
            }
            RoundKind.TRAP -> trap(level, s, side, road, dir, actors, rng, seed)
        }
    }

    private fun trap(level: Level, s: Situation, side: TrafficSide, road: RoadLayout, dir: Dir, actors: ArrayList<Actor>, rng: Random, seed: Long): Round? {
        val cabin = Cabin(Seat.DRIVER, side)
        val k = if (s == Situation.DOOR) ActorKind.EBIKE else if (s == Situation.PULL_OUT) pick(rng, ActorKind.EBIKE, ActorKind.MOTO) else pick(rng, ActorKind.SEDAN, ActorKind.SUV, ActorKind.MOTO)
        val x = road.hazardX(k, dir)
        val far = dir != cabin.doorSide
        val low = ShoulderBounds.low(eye = false, far = far)
        // where along the lane is it in the blind spot and still seen by a shoulder check at the lowest threshold?
        val spots = ArrayList<Double>()
        var rear = -1.0
        while (rear <= 9.0) {
            val box = Box(Vec3(x, Road.ROAD_Y, rear - k.length / 2), k.length, k.width, k.height)
            if (BlindSpot.isInBlindSpot(box, dir, AIMS, cabin, 0.0) && Visibility.anyInView(box, cabin, dir.sign * low, Visibility.JUDGE_HALF_FOV)) spots += rear
            rear += 0.25
        }
        if (spots.isEmpty()) return null
        val maxDwell = min(6.0, level.window - GO_LIT - 1.5 - MIN_GAP)
        if (maxDwell < 2.0) return null
        val dwell = 2.0 + rng.nextDouble() * (maxDwell - 2.0)
        val keys: List<Key>
        if (!s.cruising) {
            // parked scenes: a slow rider (or moto) creeping up through the blind spot, then on past you
            val vIn = if (k.rider) 0.8 + rng.nextDouble() * 0.6 else 1.0 + rng.nextDouble() * 0.6
            val start = spots.max()
            val c0 = start - k.length / 2
            val vOut = (if (k.rider) 4.0 else 6.0) + rng.nextDouble() * 2.0
            keys = listOf(Key(0.0, c0), Key(dwell, c0 - vIn * dwell), Key(dwell + 1.0, c0 - vIn * dwell - (vIn + vOut) / 2), Key(Round.HORIZON, c0 - vIn * dwell - (vIn + vOut) / 2 - vOut * (Round.HORIZON - dwell - 1.0)))
        } else {
            // cruising: it keeps your speed in your blind spot, then speeds past or drops back
            val spot = spots[rng.nextInt(spots.size)]
            val c0 = spot - k.length / 2
            val past = rng.nextDouble() < 0.6
            val vOut = if (past) 4.0 + rng.nextDouble() * 2.0 else -(3.0 + rng.nextDouble() * 1.5)
            val after = c0 - vOut / 2
            keys = listOf(Key(0.0, c0), Key(dwell, c0), Key(dwell + 1.0, after), Key(Round.HORIZON, after - vOut * (Round.HORIZON - dwell - 1.0)))
        }
        actors += Actor("hazard", k, x, keys, ActorRole.HAZARD, rng.nextInt(4))
        return Round(s, side, RoundKind.TRAP, actors, "hazard", level.window, seed, level.n, dwell)
    }

    private fun hazardKind(s: Situation, rng: Random): ActorKind =
        when (s) {
            Situation.DOOR -> pick(rng, ActorKind.EBIKE, ActorKind.BIKE, ActorKind.EBIKE)
            Situation.PULL_OUT -> pick(rng, ActorKind.SEDAN, ActorKind.SUV, ActorKind.EBIKE, ActorKind.MOTO)
            else -> pick(rng, ActorKind.SEDAN, ActorKind.SUV, ActorKind.MOTO, ActorKind.SEDAN)
        }

    /** Closing speed of a hazard (m/s): relative to you when cruising; absolute when you are parked. */
    private fun hazardSpeed(level: Level, s: Situation, k: ActorKind, rng: Random): Double {
        val rel = level.speedMin + rng.nextDouble() * (level.speedMax - level.speedMin)
        return when {
            s == Situation.DOOR -> (if (k == ActorKind.BIKE) 3.0 else 4.0) + rng.nextDouble() * 2.5
            s == Situation.PULL_OUT && k.rider -> 3.5 + rng.nextDouble() * 2.5
            s == Situation.PULL_OUT -> 5.0 + rel
            else -> rel
        }
    }

    /** Scene dressing that never conflicts: the car ahead in your lane, parked cars, traffic in the far lanes. */
    fun decor(s: Situation, side: TrafficSide, road: RoadLayout, rng: Random): List<Actor> {
        val out = ArrayList<Actor>()
        val dir = s.dir(side)
        when (s) {
            Situation.LANE_LEFT, Situation.LANE_RIGHT -> {
                out += Actor("ahead", ActorKind.SEDAN, road.egoX, listOf(Key(0.0, -(20.0 + rng.nextDouble() * 6.0))), ActorRole.DECOR, rng.nextInt(4))
                val other = road.adjacentLaneX(dir.flip())
                out += moving("other1", pick(rng, ActorKind.SEDAN, ActorKind.SUV), other, -(6.0 + rng.nextDouble() * 10.0), -(0.5 + rng.nextDouble()), ActorRole.DECOR, rng.nextInt(4))
                out += moving("other2", pick(rng, ActorKind.SEDAN, ActorKind.SUV, ActorKind.MOTO), other, 14.0 + rng.nextDouble() * 10.0, 0.5 + rng.nextDouble() * 1.5, ActorRole.DECOR, rng.nextInt(4))
            }
            Situation.MERGE -> {
                road.farLaneX(dir)?.let { fx ->
                    out += moving("far1", pick(rng, ActorKind.SEDAN, ActorKind.SUV), fx, -(8.0 + rng.nextDouble() * 12.0), 1.0 + rng.nextDouble(), ActorRole.DECOR, rng.nextInt(4))
                    out += moving("far2", ActorKind.SEDAN, fx, 10.0 + rng.nextDouble() * 10.0, 2.0 + rng.nextDouble(), ActorRole.DECOR, rng.nextInt(4))
                }
            }
            Situation.PULL_OUT, Situation.DOOR -> {
                // parked cars in your strip, one ahead and one behind
                out += Actor("parkedAhead", pick(rng, ActorKind.SEDAN, ActorKind.SUV), road.egoX, listOf(Key(0.0, -11.5)), ActorRole.PARKED, rng.nextInt(4))
                out += Actor("parkedBehind", ActorKind.SEDAN, road.egoX, listOf(Key(0.0, 9.5)), ActorRole.PARKED, rng.nextInt(4))
                road.farLaneX(dir)?.let { fx ->
                    out += moving("far1", pick(rng, ActorKind.SEDAN, ActorKind.SUV), fx, -(10.0 + rng.nextDouble() * 20.0), 8.0 + rng.nextDouble() * 3.0, ActorRole.DECOR, rng.nextInt(4))
                }
                if (s == Situation.DOOR) {
                    // a car in the traffic lane (out of reach of the door)
                    val k = pick(rng, ActorKind.SEDAN, ActorKind.SUV)
                    out += moving("lane1", k, road.hazardX(k, dir), -(5.0 + rng.nextDouble() * 15.0), 8.0 + rng.nextDouble() * 2.0, ActorRole.DECOR, rng.nextInt(4))
                }
            }
        }
        return out
    }

    /** A road user at box centre [z0] (t = 0) moving at constant [speed] (+ = overtaking you, toward −Z). */
    fun moving(id: String, k: ActorKind, x: Double, z0: Double, speed: Double, role: ActorRole, colour: Int): Actor =
        Actor(id, k, x, listOf(Key(0.0, z0), Key(Round.HORIZON, z0 - speed * Round.HORIZON)), role, colour)

    private fun <T> pick(rng: Random, vararg xs: T): T = xs[rng.nextInt(xs.size)]
}

/** The honest-round rules of design `rounds.honest` (used by the generator and GameGenTest). */
object RoundCheck {
    private val AIMS = MirrorAims()

    fun problems(r: Round): List<String> {
        val out = ArrayList<String>()
        val gap = r.firstGap()
        if (gap == null) {
            out += "no gap >= $MIN_GAP s in window ${r.window}"
            return out
        }
        when (r.kind) {
            RoundKind.CLEAR -> if (!r.freeAt(GO_LIT)) out += "clear round not free at go-lit"
            RoundKind.HAZARD, RoundKind.TRAP -> {
                if (r.freeAt(GO_LIT)) out += "${r.kind} round already free when the button lights"
                if (r.hazard == null) out += "no hazard actor"
            }
        }
        if (r.kind == RoundKind.TRAP) {
            if (GO_LIT + r.trapDwell + 1.5 + MIN_GAP > r.window + 1e-9) out += "budget: 1 + dwell ${r.trapDwell} + 1.5 + 2.5 > ${r.window}"
            val span = trapHiddenSpan(r, gap.first)
            if (span < 1.5) out += "trap hidden-but-visible span $span < 1.5 s"
        }
        return out
    }

    /**
     * Longest run (s) in [GO_LIT] … [until) in which the trap is in neither mirror and a head turn to the lowest
     * shoulder threshold sees it past the pillars (±40° eye margin).
     */
    fun trapHiddenSpan(r: Round, until: Double): Double {
        val h = r.hazard ?: return 0.0
        val far = r.dir != r.cabin.doorSide
        val low = ShoulderBounds.low(eye = false, far = far)
        val inner = r.cabin.mirror(MirrorId.INNER)
        var best = 0.0
        var run = 0.0
        var t = GO_LIT
        while (t < until - 1e-9) {
            val b = h.box(t)
            val hidden = !MirrorModel.sees(b, r.mirror, r.cabin, AIMS.of(r.mirror.id)) && (inner == null || !MirrorModel.sees(b, inner, r.cabin, 0.0))
            val seenBack = Visibility.anyInView(b, r.cabin, r.dir.sign * low, Visibility.JUDGE_HALF_FOV)
            run = if (hidden && seenBack) run + TABLE_STEP else 0.0
            best = max(best, run)
            t += TABLE_STEP
        }
        return best
    }
}

/** The 8 rounds of a game (design `scoring.session`, `rounds.trap`, `rounds.noHazardRounds`). */
class GamePlan(val level: Level, val seed: Long, val side: TrafficSide, val daily: Boolean, val firstEver: Boolean) {
    val kinds: List<RoundKind>
    val situations: List<Situation>

    init {
        val rng = Random(seed * 31 + level.n)
        val clear = 1 + rng.nextInt(2)
        val traps = level.traps
        val k = ArrayList<RoundKind>()
        repeat(traps) { k += RoundKind.TRAP }
        repeat(clear) { k += RoundKind.CLEAR }
        while (k.size < ROUNDS) k += RoundKind.HAZARD
        k.shuffle(rng)
        // a gentle first round: never a trap; the very first game starts with a clear lane change to the left
        if (k[0] == RoundKind.TRAP) {
            val j = k.indexOfFirst { it != RoundKind.TRAP }
            k[0] = k[j].also { k[j] = k[0] }
        }
        if (firstEver) {
            val j = k.indexOf(RoundKind.CLEAR)
            k[0] = k[j].also { k[j] = k[0] }
        }
        kinds = k
        val pool = level.situations
        val sits = ArrayList<Situation>()
        while (sits.size < ROUNDS) sits += pool.shuffled(rng)
        val s = sits.take(ROUNDS).toMutableList()
        if (firstEver) {
            val j = s.indexOf(Situation.LANE_LEFT).takeIf { it >= 0 }
            if (j != null) s[0] = s[j].also { s[j] = s[0] } else s[0] = Situation.LANE_LEFT
        }
        // DEVIATION (within tolerance): no trap at the door. With the side mirror fixed 5° outward the bike lane right beside
        // your door is always in the mirror (RoundCheck finds no blind-spot position for a rider 0.85 m off the body), so a
        // 「镜子里看不到」 door trap would be dishonest; door rounds are clear or hazard rounds (e-bike / bicycle coming up).
        for (i in 0 until ROUNDS) {
            if (s[i] != Situation.DOOR || k[i] != RoundKind.TRAP) continue
            val j = (0 until ROUNDS).firstOrNull { it != i && k[it] != RoundKind.TRAP && s[it] != Situation.DOOR && !(firstEver && it == 0) } ?: continue
            s[i] = s[j].also { s[j] = s[i] }
        }
        situations = s
    }

    fun round(i: Int): Round = RoundGenerator.generate(level, situations[i], kinds[i], side, RoundGenerator.mix(seed, i))

    companion object {
        const val ROUNDS = 8
    }
}
