package io.embodit.shouldercheck.app

import io.embodit.shouldercheck.domain.Actor
import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.ActorRole
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.FreeModeSet
import io.embodit.shouldercheck.domain.GazeRay
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.Key
import io.embodit.shouldercheck.domain.Lesson
import io.embodit.shouldercheck.domain.LiveStatus
import io.embodit.shouldercheck.domain.MirrorAims
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.QuestionJudge
import io.embodit.shouldercheck.domain.QuestionResult
import io.embodit.shouldercheck.domain.RoadLayout
import io.embodit.shouldercheck.domain.Scenario
import io.embodit.shouldercheck.domain.Signal
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Step
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3

/** Head in the car frame of the current seat. */
data class HeadIn(val yaw: Double, val pitch: Double, val pos: Vec3)

/** One controller this frame, car frame. */
data class CtrlIn(val hand: Hand, val pos: Vec3?, val grip: Boolean, val stickX: Float)

/** Everything the ECS system read this frame, converted to the car frame of the current seat. */
data class FrameInput(
    val nowMs: Long,
    val dt: Double,
    val head: HeadIn?,
    val headLost: Boolean,
    val blockReason: String?,
    val gaze: GazeRay?,
    val eyeReason: String?,
    val controllers: List<CtrlIn>,
    val controllersTracked: Boolean,
    val pinches: List<Triple<Hand, Vec3, Float>>,
    val aPresses: Int,
    val bPresses: Int,
    val yPresses: Int,
)

/** Where the current questions come from. */
sealed interface Course {
    class LessonCourse(val lesson: Lesson, val trials: List<Scenario>) : Course {
        var index = 0
        val outcomes = arrayOfNulls<Outcome>(trials.size)

        val last: Boolean
            get() = index >= trials.size - 1
    }

    class FreeCourse(val set: FreeModeSet) : Course

    /** Debug `scene=` / `goto` questions: one scenario, repeated by "下一题". */
    class DebugCourse(val scenario: Scenario) : Course
}

/**
 * One running question (S05 … S09): the scenario, its judge (created at S05 = judge mode locked), the run clock
 * t (t = 0 at the start of S06; S05 runs from −2) and the input state that turns raw controller values into judge
 * samples (turn-signal stalk, the door grab, the execute press).
 */
class QuestionRun(val scenario: Scenario, val judge: QuestionJudge) {
    var t = -2.0
    var signal = Signal.OFF
    var execPending = false

    /** Hand holding the door handle, the point where it grabbed (car frame). */
    var grabHand: Hand? = null
    var grabPoint: Vec3? = null
    var push = 0.0
    var live: LiveStatus? = null
    var result: QuestionResult? = null

    /** Stick state for edge detection (push = beyond 0.7, released below 0.3). */
    val stickArmed = mutableMapOf(Hand.LEFT to true, Hand.RIGHT to true)

    /** Lesson 1: confirms done. */
    val confirmed = mutableSetOf<Dir>()

    /** S09: conflict actor and the run time where traffic froze; S08 / S09 entry time. */
    var conflictActor: String? = null
    var frozenAt: Double? = null
    var stateSinceMs = 0L
    var doorSoundAngle = 0.0
    var lostSinceMs = -1L

    /** Lesson 2: last moment the hazard was in its side mirror. */
    var mirrorExit: Double? = null

    val aims: MirrorAims
        get() = judge.aims

    val actorTime: Double
        get() = frozenAt ?: t
}

/** S11 replay of the last result. */
class ReplayState(val scenario: Scenario, val judge: QuestionJudge, val result: QuestionResult) {
    var t = 0.0
    var speed = 1.0

    val end: Double
        get() = (result.execTime?.let { it + 3.0 } ?: result.trace.lastOrNull()?.t ?: 6.0).coerceAtLeast(1.0)
}

/** S04 demo: the steps light one after another (2.4 s each), the demo hand for lessons 3 / 4. */
class DemoState(val lesson: Lesson, val scenario: Scenario, val steps: List<Step>) {
    var t = 0.0

    /** Debug `step=n`: hold on that step. */
    var holdStep: Int? = null

    val stepIndex: Int
        get() = holdStep ?: (t / STEP_SEC).toInt().coerceAtMost(steps.size)

    val done: Boolean
        get() = holdStep == null && t >= steps.size * STEP_SEC + 0.6

    companion object {
        const val STEP_SEC = 2.4
    }
}

/** The home scene (S02 …): parked at the kerb, cars keep passing in the lanes on your left (visible in the left mirror). */
object Ambient {
    const val PERIOD = 40.0

    fun scenario(side: TrafficSide): Scenario {
        val road = RoadLayout(Situation.PULL_OUT, side)
        val near = Dir.LEFT.physical(side)
        val lane1 = road.hazardX(ActorKind.SEDAN, near)
        val lane2 = lane1 + near.sign * 3.5
        fun car(id: String, kind: ActorKind, x: Double, z0: Double, v: Double, colour: Int) =
            Actor(id, kind, x, listOf(Key(0.0, z0), Key(PERIOD, z0 - v * PERIOD)), ActorRole.TRAFFIC, colour)
        val actors =
            listOf(
                car("amb-1", ActorKind.SEDAN, lane1, 60.0, 3.0, 1),
                car("amb-2", ActorKind.SUV, lane2, 75.0, 3.8, 2),
                car("amb-3", ActorKind.SEDAN, lane2, 115.0, 3.8, 3),
                car("amb-4", ActorKind.MOTO, lane1, 110.0, 3.0, 4),
            )
        return Scenario(Situation.PULL_OUT, side, actors)
    }

    /** Wrapped time: each car loops every [PERIOD] seconds (starts again 60–110 m behind). */
    fun time(t: Double): Double = ((t % PERIOD) + PERIOD) % PERIOD
}
