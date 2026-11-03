package io.embodit.shouldercheck.domain

/** What kind of action a situation ends with. */
enum class ActionKind {
    LANE,
    TURN,
    DOOR,
    MIRRORS_LESSON,
    BLIND_SPOT_LESSON,
}

/**
 * Situations (design `content.lessons`; ids are the debug `scene=` names). [designDir] is the direction for
 * right-hand traffic; use [dir] for the physical one (left-hand traffic flips it: "lane-left" becomes a change to the
 * RIGHT lane, the overtaking side, U10). The first six are the practice situations of the scorecard / random traffic.
 */
enum class Situation(val id: String, val designDir: Dir, val seat: Seat, val action: ActionKind, val lesson: Int) {
    LANE_LEFT("lane-left", Dir.LEFT, Seat.DRIVER, ActionKind.LANE, 5),
    LANE_RIGHT("lane-right", Dir.RIGHT, Seat.DRIVER, ActionKind.LANE, 6),
    PULL_OUT("pull-out", Dir.LEFT, Seat.DRIVER, ActionKind.LANE, 7),
    RIGHT_TURN("right-turn", Dir.RIGHT, Seat.DRIVER, ActionKind.TURN, 8),
    DOOR_DRIVER("door-driver", Dir.LEFT, Seat.DRIVER, ActionKind.DOOR, 3),
    DOOR_REAR("door-rear", Dir.RIGHT, Seat.REAR, ActionKind.DOOR, 4),
    MIRRORS("mirrors", Dir.LEFT, Seat.DRIVER, ActionKind.MIRRORS_LESSON, 1),
    BLIND_SPOT("blind-spot", Dir.LEFT, Seat.DRIVER, ActionKind.BLIND_SPOT_LESSON, 2),
    ;

    fun dir(side: TrafficSide): Dir = designDir.physical(side)

    val practice: Boolean
        get() = ordinal < 6

    /** Steps to do, in the recommended display order (design `judging.order` / `required`). */
    fun requiredSteps(side: TrafficSide): List<Step> {
        val d = dir(side)
        return when (this) {
            LANE_LEFT, LANE_RIGHT, PULL_OUT -> listOf(Step.INNER_MIRROR, Step.mirror(d), Step.shoulder(d), Step.signal(d))
            RIGHT_TURN -> listOf(Step.mirror(d), Step.shoulder(d), Step.signal(d))
            DOOR_DRIVER -> listOf(Step.FAR_HAND, Step.mirror(d), Step.shoulder(d))
            DOOR_REAR -> listOf(Step.FAR_HAND, Step.shoulder(d))
            MIRRORS -> listOf(Step.INNER_MIRROR, Step.LEFT_MIRROR, Step.RIGHT_MIRROR, Step.CONFIRM_LEFT, Step.CONFIRM_RIGHT)
            BLIND_SPOT -> listOf(Step.SEE_HAZARD)
        }
    }

    companion object {
        val PRACTICE: List<Situation> = values().filter { it.practice }

        fun byId(id: String): Situation? = values().firstOrNull { it.id == id }
    }
}

/** Judged steps (design `copy.steps` + the far hand, lesson-1 confirms and lesson-2 "see it"). */
enum class Step {
    INNER_MIRROR,
    LEFT_MIRROR,
    RIGHT_MIRROR,
    SHOULDER_LEFT,
    SHOULDER_RIGHT,
    SIGNAL_LEFT,
    SIGNAL_RIGHT,
    FAR_HAND,
    CONFIRM_LEFT,
    CONFIRM_RIGHT,
    SEE_HAZARD,
    ;

    val mirrorId: MirrorId?
        get() =
            when (this) {
                INNER_MIRROR -> MirrorId.INNER
                LEFT_MIRROR -> MirrorId.LEFT
                RIGHT_MIRROR -> MirrorId.RIGHT
                else -> null
            }

    /** Side of a shoulder / signal / side-mirror / confirm step. */
    val dir: Dir?
        get() =
            when (this) {
                LEFT_MIRROR, SHOULDER_LEFT, SIGNAL_LEFT, CONFIRM_LEFT -> Dir.LEFT
                RIGHT_MIRROR, SHOULDER_RIGHT, SIGNAL_RIGHT, CONFIRM_RIGHT -> Dir.RIGHT
                else -> null
            }

    /** Steps that must be done within the last 5 s before executing (design `judging.fresh`). */
    val needsFresh: Boolean
        get() = this == LEFT_MIRROR || this == RIGHT_MIRROR || this == SHOULDER_LEFT || this == SHOULDER_RIGHT

    companion object {
        fun mirror(d: Dir) = if (d == Dir.LEFT) LEFT_MIRROR else RIGHT_MIRROR

        fun shoulder(d: Dir) = if (d == Dir.LEFT) SHOULDER_LEFT else SHOULDER_RIGHT

        fun signal(d: Dir) = if (d == Dir.LEFT) SIGNAL_LEFT else SIGNAL_RIGHT

        fun confirm(d: Dir) = if (d == Dir.LEFT) CONFIRM_LEFT else CONFIRM_RIGHT
    }
}

/** Why a question has no hazard. */
enum class NoHazardReason {
    /** The dice said so (hazard rate). */
    RATE,

    /** The turn range is too small to see it in time (lesson 4 note, generator solvability). */
    RANGE,
}

/**
 * A hazard to place (debug hook `hazard=car side=L z=12 v=3`): [side] physical side, [rearZ] = z of its REAR end at
 * t = 0 (m behind your eye, + = behind; "车尾在眼后 12 m"), [speed] relative speed (+ = overtaking you, m/s).
 */
data class HazardSpec(val kind: ActorKind, val side: Dir, val rearZ: Double, val speed: Double)

/**
 * One question: who drives where and when, what counts as a conflict, what must be done. Time t = 0 is the start of
 * S06 (checking); S05 (2 s) runs t ∈ [−2, 0). All coordinates are in [cabin]'s eye frame (physical x).
 */
class Scenario(
    val situation: Situation,
    val side: TrafficSide,
    val actors: List<Actor>,
    val hazardId: String? = null,
    /** Level-5 time limit (s) or null. */
    val timeLimit: Double? = null,
    val seed: Long = 0L,
    val difficulty: Int? = null,
    val noHazardReason: NoHazardReason? = null,
    val lesson: Int? = null,
) {
    val seat: Seat = situation.seat
    val cabin = Cabin(seat, side)
    val road = RoadLayout(situation, side)
    val dir: Dir = situation.dir(side)
    val requiredSteps: List<Step> = situation.requiredSteps(side)
    val hazard: Actor? = actors.firstOrNull { it.id == hazardId }

    /** Lane-type conflict slot (null for doors and lessons 1–2). */
    val slot: Slot? =
        if (situation.action == ActionKind.LANE || situation.action == ActionKind.TURN) LaneChangeModel.slot(situation, side, cabin, road) else null

    /** True when [box] is inside the conflict zone (slot, or full-open door sweep + margin). */
    fun inConflictZone(box: Box): Boolean =
        when (situation.action) {
            ActionKind.LANE, ActionKind.TURN -> slot!!.overlaps(box)
            ActionKind.DOOR -> DoorModel.inOpenZone(cabin, box)
            else -> false
        }

    /** First time the hazard is inside the conflict zone (null: never in 0 … 30 s, or no hazard). */
    val hazardEntryTime: Double? by lazy { hazard?.let { h -> scan(0.0, HORIZON) { inConflictZone(h.box(it)) } } }

    /** First time after entry that the hazard is out of the conflict zone again ("it has passed"). */
    val hazardPassTime: Double? by lazy {
        val h = hazard ?: return@lazy null
        val entry = hazardEntryTime ?: return@lazy null
        scan(entry, HORIZON) { !inConflictZone(h.box(it)) }
    }

    companion object {
        const val HORIZON = 30.0
        const val STEP = 0.05

        internal fun scan(from: Double, to: Double, pred: (Double) -> Boolean): Double? {
            var t = from
            while (t <= to + 1e-9) {
                if (pred(t)) return t
                t += STEP
            }
            return null
        }
    }
}

/**
 * Builds the scripted scenes (debug `scene=…` + `hazard=…`, lessons 1–2 and the lesson context). Scripts for the
 * practice lessons and random traffic come from [TrafficGenerator]; this is the hand-written layer under it.
 */
object Scenes {
    /** A scene with an optional hazard from a [HazardSpec] (constant relative speed). */
    fun build(situation: Situation, side: TrafficSide, hazard: HazardSpec? = null, extra: List<Actor> = emptyList(), timeLimit: Double? = null): Scenario {
        val road = RoadLayout(situation, side)
        val actors = ArrayList<Actor>()
        actors += context(situation, side, road)
        actors += extra
        var hazardId: String? = null
        if (hazard != null) {
            val a = hazardActor("hazard", hazard, road)
            actors += a
            hazardId = a.id
        }
        return Scenario(situation, side, actors, hazardId, timeLimit, lesson = situation.lesson)
    }

    /** Hazard actor for a spec: box centre from the rear end, z(t) = centre0 − speed · t. */
    fun hazardActor(id: String, spec: HazardSpec, road: RoadLayout, role: ActorRole = ActorRole.HAZARD): Actor {
        val centre0 = spec.rearZ - spec.kind.length / 2
        return Actor(id, spec.kind, road.hazardX(spec.kind, spec.side), listOf(Key(0.0, centre0), Key(Scenario.HORIZON, centre0 - spec.speed * Scenario.HORIZON)), role)
    }

    /** Non-hazard context of each scene (design `content.lessons[].scene`). */
    fun context(situation: Situation, side: TrafficSide, road: RoadLayout): List<Actor> =
        when (situation) {
            // lesson 5: a bus ahead in your lane, slowing for its stop
            Situation.LANE_LEFT -> listOf(Actor("bus", ActorKind.BUS, road.egoX, listOf(Key(-2.0, -22.0), Key(6.0, -14.0), Key(30.0, -14.0)), ActorRole.DECOR))
            // lesson 1: one parked car in each neighbouring lane, rear ~3.7 m behind you (in the blind spot at aim 5°)
            Situation.MIRRORS ->
                listOf(
                    Actor("parked-L", ActorKind.SEDAN, road.adjacentLaneX(Dir.LEFT), listOf(Key(0.0, 3.7 - ActorKind.SEDAN.length / 2)), ActorRole.PARKED, 1),
                    Actor("parked-R", ActorKind.SEDAN, road.adjacentLaneX(Dir.RIGHT), listOf(Key(0.0, 3.4 - ActorKind.SEDAN.length / 2)), ActorRole.PARKED, 2),
                )
            else -> emptyList()
        }

    /**
     * Lesson 2 trial: a car comes up the [dir] lane from 15 m behind (in the mirror), slides into the blind spot by
     * t = 4 s (rear 3.6 m behind you: out of the mirror and out of the forward view) and stays there until you
     * look back.
     */
    fun blindSpotTrial(side: TrafficSide, dir: Dir): Scenario {
        val road = RoadLayout(Situation.BLIND_SPOT, side)
        val l = ActorKind.SEDAN.length
        val car = Actor("hazard", ActorKind.SEDAN, road.adjacentLaneX(dir), listOf(Key(0.0, 15.0 - l / 2), Key(4.0, 3.6 - l / 2), Key(Scenario.HORIZON, 3.6 - l / 2)), ActorRole.HAZARD, 3)
        return Scenario(Situation.BLIND_SPOT, side, listOf(car), car.id, lesson = 2)
    }

    /** Lesson 2 panel line "它在你镜子里消失了 x 秒": last time the hazard showed in its side mirror (null = never). */
    fun mirrorExitTime(s: Scenario, aims: MirrorAims, until: Double = 10.0): Double? {
        val h = s.hazard ?: return null
        val mirror = s.cabin.mirror(MirrorId.side(Dir.ofSign(h.x))) ?: return null
        var last: Double? = null
        var t = 0.0
        while (t <= until) {
            if (MirrorModel.sees(h.box(t), mirror, s.cabin, aims.of(mirror.id))) last = t
            t += Scenario.STEP
        }
        return last
    }
}
