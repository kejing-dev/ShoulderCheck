package io.embodit.shouldercheck.domain

import kotlin.random.Random

/**
 * The 8 lessons (design `content.lessons`). [passNeeded] of [trials] must be correct (lessons 1–2: every target lit,
 * modelled as all trials). [hazardRate] 0.67 is applied as exactly 2 hazard trials out of 3 (seeded order).
 */
data class Lesson(
    val n: Int,
    val situation: Situation,
    val zh: String,
    val en: String,
    val trials: Int,
    val passNeeded: Int,
    val hazardRate: Double,
    /** Lesson 4: the hazard needs a turn to the rear window; with a small range only hazard-free trials. */
    val rangeNote: Boolean = false,
) {
    fun passed(results: List<Outcome>): Boolean = results.count { it.correct } >= passNeeded
}

object Lessons {
    val ALL: List<Lesson> =
        listOf(
            Lesson(1, Situation.MIRRORS, "三面镜子 · 调侧镜", "Mirrors and how to set them", 1, 1, 0.0),
            Lesson(2, Situation.BLIND_SPOT, "盲区在哪", "Where the blind spot is", 2, 2, 1.0),
            Lesson(3, Situation.DOOR_DRIVER, "下车开门 · 驾驶位", "Dutch reach · driver", 3, 2, 0.67),
            Lesson(4, Situation.DOOR_REAR, "下车开门 · 后排右侧", "Dutch reach · rear passenger", 3, 2, 0.67, rangeNote = true),
            Lesson(5, Situation.LANE_LEFT, "向左变道", "Change lane left", 3, 2, 0.67),
            Lesson(6, Situation.LANE_RIGHT, "向右变道", "Change lane right", 3, 2, 0.67),
            Lesson(7, Situation.PULL_OUT, "路边起步", "Pull out from the kerb", 3, 2, 0.67),
            Lesson(8, Situation.RIGHT_TURN, "右转看非机动车", "Right turn: check for bikes", 3, 2, 0.67),
        )

    fun byN(n: Int): Lesson = ALL.first { it.n == n }

    /** Groups of the lesson list (S03): 认识 1–2 / 开门 3–4 / 变道与转弯 5–8. */
    val GROUPS: List<List<Int>> = listOf(listOf(1, 2), listOf(3, 4), listOf(5, 6, 7, 8))
}

/**
 * Difficulty table of random traffic (design `content.freeMode.difficulty`), levels 1–5:
 * 1 = 50 % hazards, one hazard, enters the conflict zone at 4 s (early); 3 adds a distractor (far car in the mirror,
 * never a threat); 4 = the hazard arrives late (5–7 s: after your mirror check, tests freshness); 5 = 8 s time limit.
 */
data class DifficultyLevel(
    val n: Int,
    val hazardRate: Double,
    val entryMin: Double,
    val entryMax: Double,
    val speedMin: Double,
    val speedMax: Double,
    val distractors: Int,
    /** Lane scenes: chance the hazard is a slower car you are passing (relative speed negative). */
    val slowerChance: Double,
    val timeLimit: Double?,
)

object Difficulty {
    val LEVELS: List<DifficultyLevel> =
        listOf(
            DifficultyLevel(1, 0.50, 4.0, 4.0, 2.5, 3.5, 0, 0.0, null),
            DifficultyLevel(2, 0.60, 3.0, 5.0, 2.5, 4.5, 0, 0.0, null),
            DifficultyLevel(3, 0.67, 3.0, 5.0, 2.5, 5.0, 1, 0.2, null),
            DifficultyLevel(4, 0.70, 5.0, 7.0, 3.0, 6.0, 1, 0.2, null),
            DifficultyLevel(5, 0.75, 1.8, 3.5, 3.5, 6.0, 2, 0.0, 8.0),
        )

    fun level(n: Int): DifficultyLevel = LEVELS[(n - 1).coerceIn(0, LEVELS.size - 1)]

    /** Lesson trials use level 2 numbers (moderate, no distractor, no limit). */
    const val LESSON_LEVEL = 2
}

/** "每做对 3 题升一级、险情一次降一级" (correct answers counted since the last level change). */
class DifficultyTracker(level: Int = 1) {
    var level: Int = level.coerceIn(1, 5)
        private set
    private var correctSinceChange = 0

    fun onResult(outcome: Outcome) {
        when {
            outcome == Outcome.NEAR_MISS -> {
                if (level > 1) level--
                correctSinceChange = 0
            }
            outcome.correct -> {
                correctSinceChange++
                if (correctSinceChange >= 3) {
                    if (level < 5) level++
                    correctSinceChange = 0
                }
            }
            else -> Unit
        }
    }
}

/** Solvability report (design `freeMode.solvable`). */
data class Solvability(
    val entryTime: Double?,
    val passTime: Double?,
    /** Longest continuous run (s) before entry in which the hazard could be seen (band or mirror). */
    val visibleRun: Double,
) {
    val passDuration: Double?
        get() = if (entryTime != null && passTime != null) passTime - entryTime else null

    val ok: Boolean
        get() = entryTime != null && entryTime > 0 && passTime != null && visibleRun >= MIN_VISIBLE - 1e-9 && passDuration!! <= MAX_PASS + 1e-9

    companion object {
        const val MIN_VISIBLE = 1.5
        const val MAX_PASS = 6.0

        /**
         * The hazard of [s] can be seen for ≥ 1.5 s before it enters the conflict zone, inside the "max + 40° minus
         * pillars" band of [range] or (driver seat only) in a mirror; and it passes within 6 s.
         */
        fun check(s: Scenario, range: TurnRange, aims: MirrorAims): Solvability {
            val h = s.hazard ?: return Solvability(null, null, 0.0)
            val entry = s.hazardEntryTime ?: return Solvability(null, null, 0.0)
            var best = 0.0
            var run = 0.0
            var t = 0.0
            while (t < entry - 1e-9) {
                if (Visibility.reachable(h.box(t), s.cabin, range, aims)) {
                    run += Scenario.STEP
                    if (run > best) best = run
                } else {
                    run = 0.0
                }
                t += Scenario.STEP
            }
            return Solvability(entry, s.hazardPassTime, best)
        }
    }
}

/**
 * Seeded traffic generator for lesson trials and random traffic (design `content.freeMode.generator`). Every
 * question is solvable or has no hazard ([Solvability]); distractors never enter the conflict zone.
 */
object TrafficGenerator {
    private const val TRIES = 12

    fun kindsFor(s: Situation): List<ActorKind> =
        when (s) {
            Situation.LANE_LEFT, Situation.LANE_RIGHT, Situation.PULL_OUT -> listOf(ActorKind.SEDAN, ActorKind.SUV, ActorKind.MOTO, ActorKind.EBIKE)
            Situation.RIGHT_TURN -> listOf(ActorKind.EBIKE, ActorKind.BIKE)
            Situation.DOOR_DRIVER -> listOf(ActorKind.EBIKE, ActorKind.BIKE, ActorKind.MOTO, ActorKind.SEDAN)
            Situation.DOOR_REAR -> listOf(ActorKind.EBIKE, ActorKind.BIKE)
            else -> listOf(ActorKind.SEDAN)
        }

    private fun speedCap(kind: ActorKind): Double = when (kind) {
        ActorKind.BIKE -> 5.0
        ActorKind.EBIKE -> 6.0
        else -> 7.0
    }

    private fun mix(seed: Long, index: Int): Long = seed * 6364136223846793005L + index * 1442695040888963407L + 0x9E3779B97F4A7C15uL.toLong()

    /** Random-traffic question [index] (0-based) of the set with [seed] at difficulty [level]. */
    fun question(seed: Long, index: Int, level: Int, side: TrafficSide, range: TurnRange, aims: MirrorAims = MirrorAims(), situation: Situation? = null): Scenario {
        val pick = Random(mix(seed, index))
        val sit = situation ?: Situation.PRACTICE[pick.nextInt(Situation.PRACTICE.size)]
        val lv = Difficulty.level(level)
        val rng = Random(mix(seed, index) xor (level.toLong() shl 40))
        val wantHazard = rng.nextDouble() < lv.hazardRate
        return make(sit, side, rng, lv, wantHazard, range, aims, seed, null)
    }

    /** The trials of lesson [n] (seeded): lessons 3–8 have 2 hazard trials of 3. */
    fun lessonTrials(n: Int, seed: Long, side: TrafficSide, range: TurnRange, aims: MirrorAims = MirrorAims()): List<Scenario> {
        val lesson = Lessons.byN(n)
        return when (lesson.situation) {
            Situation.MIRRORS -> listOf(Scenes.build(Situation.MIRRORS, side))
            Situation.BLIND_SPOT -> {
                val near = Dir.LEFT.physical(side)
                listOf(Scenes.blindSpotTrial(side, near), Scenes.blindSpotTrial(side, near.flip()))
            }
            else -> {
                val rng = Random(mix(seed, 1000 + n))
                val clearIndex = rng.nextInt(lesson.trials)
                val lv = Difficulty.level(Difficulty.LESSON_LEVEL)
                (0 until lesson.trials).map { i ->
                    make(lesson.situation, side, Random(mix(seed, 2000 + n * 10 + i)), lv, i != clearIndex, range, aims, seed, n)
                }
            }
        }
    }

    private fun make(sit: Situation, side: TrafficSide, rng: Random, lv: DifficultyLevel, wantHazard: Boolean, range: TurnRange, aims: MirrorAims, seed: Long, lesson: Int?): Scenario {
        val road = RoadLayout(sit, side)
        val dir = sit.dir(side)
        val base = ArrayList(Scenes.context(sit, side, road))
        val probe = Scenario(sit, side, base)
        repeat(lv.distractors) { i ->
            distractor(sit, side, road, dir, rng, i)?.let { d ->
                val trial = Scenario(sit, side, base + d, d.id)
                if (trial.hazardEntryTime == null) base += d
            }
        }
        val limit = lv.timeLimit
        if (!wantHazard) return Scenario(sit, side, base, null, limit, seed, lv.n, NoHazardReason.RATE, lesson)
        val kinds = kindsFor(sit)
        var visibilityFailed = false
        repeat(TRIES) {
            val kind = kinds[rng.nextInt(kinds.size)]
            val cap = speedCap(kind)
            val vMax = minOf(lv.speedMax, cap)
            val vMin = minOf(lv.speedMin, vMax)
            val speed = vMin + rng.nextDouble() * (vMax - vMin)
            val entry = lv.entryMin + rng.nextDouble() * (lv.entryMax - lv.entryMin)
            val slower = (sit.action == ActionKind.LANE) && rng.nextDouble() < lv.slowerChance
            val spec = hazardSpec(sit, probe, kind, dir, speed, entry, slower)
            val actor = Scenes.hazardActor("hazard", spec, road)
            val s = Scenario(sit, side, base + actor, actor.id, limit, seed, lv.n, null, lesson)
            val sol = Solvability.check(s, range, aims)
            val inTime = limit == null || (s.hazardPassTime != null && s.hazardPassTime!! <= limit - 2.5)
            if (sol.ok && inTime) return s
            if (sol.entryTime != null && sol.visibleRun < Solvability.MIN_VISIBLE) visibilityFailed = true
        }
        return Scenario(sit, side, base, null, limit, seed, lv.n, if (visibilityFailed) NoHazardReason.RANGE else NoHazardReason.RATE, lesson)
    }

    /** A hazard whose leading end reaches the conflict zone at about [entry] s. */
    private fun hazardSpec(sit: Situation, probe: Scenario, kind: ActorKind, dir: Dir, speed: Double, entry: Double, slower: Boolean): HazardSpec {
        val zoneRear: Double
        val zoneFront: Double
        val slot = probe.slot
        if (slot != null) {
            zoneRear = slot.zMax
            zoneFront = slot.zMin
        } else {
            val c = probe.cabin
            zoneRear = c.doorHinge.z + c.doorLength + DoorModel.MARGIN
            zoneFront = c.doorHinge.z - DoorModel.MARGIN
        }
        return if (slower) {
            // a slower vehicle ahead of you in the target lane, drifting back into your slot
            val rear0 = zoneFront - speed * entry
            HazardSpec(kind, dir, rear0, -speed)
        } else {
            val front0 = zoneRear + speed * entry
            HazardSpec(kind, dir, front0 + kind.length, speed)
        }
    }

    /** Distractor: far car in the mirror moving away (lanes), or a car passing in the far lane (doors, turn). */
    private fun distractor(sit: Situation, side: TrafficSide, road: RoadLayout, dir: Dir, rng: Random, i: Int): Actor? {
        val kind = if (rng.nextBoolean()) ActorKind.SEDAN else ActorKind.SUV
        return when (sit.action) {
            ActionKind.LANE -> {
                val rear0 = 20.0 + rng.nextDouble() * 10.0
                Scenes.hazardActor("distractor-$i", HazardSpec(kind, dir, rear0, -(1.0 + rng.nextDouble())), road, ActorRole.DISTRACTOR)
            }
            ActionKind.TURN, ActionKind.DOOR -> {
                val x = road.strips.filter { it.type == StripType.LANE }.minByOrNull { kotlin.math.abs(it.centre - (road.egoX + dir.sign * 6.0)) }?.centre ?: return null
                val rear0 = 15.0 + rng.nextDouble() * 10.0
                val v = 4.0 + rng.nextDouble() * 2.0
                val c0 = rear0 - kind.length / 2
                Actor("distractor-$i", kind, x, listOf(Key(0.0, c0), Key(Scenario.HORIZON, c0 - v * Scenario.HORIZON)), ActorRole.DISTRACTOR, 1 + i)
            }
            else -> null
        }
    }
}

/** Set result (S14). */
data class SetSummary(val seed: Long, val questions: Int, val correctRate: Double, val meanCheckTime: Double?, val medianShoulder: Double?, val nearMisses: Int)

/**
 * A random-traffic set (S13): 10 questions, seed shown on the set result so the same set can be replayed (same
 * situations; the hazards follow your difficulty level). Skipped questions count as questions, not in the rate.
 */
class FreeModeSet(val seed: Long, val side: TrafficSide, val range: TurnRange, val aims: MirrorAims = MirrorAims(), startLevel: Int = 1, startIndex: Int = 0) {
    val tracker = DifficultyTracker(startLevel)
    private val results = ArrayList<QuestionResult>()

    /** 0-based question index; [startIndex] lets the debug hook `seed=<n> q=<n>` open a set at question n. */
    var index = startIndex.coerceIn(0, SIZE)
        private set

    val finished: Boolean
        get() = index >= SIZE

    /** The current question (S05 of question [index]). */
    fun current(): Scenario = TrafficGenerator.question(seed, index, tracker.level, side, range, aims)

    fun record(result: QuestionResult) {
        results += result
        if (result.outcome != Outcome.SKIPPED) tracker.onResult(result.outcome)
        index++
    }

    fun summary(): SetSummary {
        val counted = results.filter { it.outcome != Outcome.SKIPPED }
        val rate = if (counted.isEmpty()) 0.0 else counted.count { it.correct }.toDouble() / counted.size
        val checks = counted.mapNotNull { it.checkTime }
        return SetSummary(
            seed = seed,
            questions = results.size,
            correctRate = rate,
            meanCheckTime = if (checks.isEmpty()) null else checks.average(),
            medianShoulder = Stats.median(counted.map { it.shoulderPeak }),
            nearMisses = counted.count { it.outcome == Outcome.NEAR_MISS },
        )
    }

    companion object {
        const val SIZE = 10
    }
}
