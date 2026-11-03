package io.embodit.shouldercheck.domain

import kotlin.math.max

/** Judging mode, locked per question in S05 (design `judging.mode`). */
enum class JudgeMode {
    EYE,
    HEAD,
}

/** Turn-signal state. */
enum class Signal(val dir: Dir?) {
    OFF(null),
    LEFT(Dir.LEFT),
    RIGHT(Dir.RIGHT),
    ;

    companion object {
        fun of(dir: Dir?): Signal = when (dir) {
            Dir.LEFT -> LEFT
            Dir.RIGHT -> RIGHT
            null -> OFF
        }
    }
}

/** Gaze ray in the car frame (origin ≈ eye, unit direction). */
data class GazeRay(val origin: Vec3, val dir: Vec3)

/**
 * One time-stamped input sample for [QuestionJudge] (car frame of the seat, degrees, metres).
 *
 * @param t seconds since the start of S06 (checking); samples must come in increasing t.
 * @param headYaw head yaw relative to the car front, + = right (a left shoulder check is negative).
 * @param headPitch + = up.
 * @param headPos head position (≈ eye; used for the direction to the mirrors).
 * @param gaze eye-gaze ray, or null when there is no fresh eye sample (then the head rules apply to this sample).
 * @param signal turn-signal state now.
 * @param grab the hand gripping inside the door handle's reach sphere (door scenes), or null.
 * @param push outward hand displacement since the grab, m (door scenes).
 * @param exec true on the sample where "execute" was pressed (A key / P2 button) — lane, pull-out, turn scenes.
 */
data class JudgeSample(
    val t: Double,
    val headYaw: Double,
    val headPitch: Double = 0.0,
    val headPos: Vec3 = Vec3.ZERO,
    val gaze: GazeRay? = null,
    val signal: Signal = Signal.OFF,
    val grab: Hand? = null,
    val push: Double = 0.0,
    val exec: Boolean = false,
)

enum class StepState {
    /** Not done (grey). */
    PENDING,

    /** Done and valid now (lit). */
    DONE,

    /** Was done more than 5 s ago: grey again + "过了 5 秒，再看一次". */
    STALE,

    /** Wrong (signal on the wrong side, door held with the near hand): ✗. */
    WRONG,
}

/** Live state of one step block on P2. */
data class StepLive(
    val step: Step,
    val state: StepState,
    /** Latest completion time (s), null if never. */
    val doneAt: Double?,
    val firstDoneAt: Double?,
    /** Completed by the head rule while the question was in eye mode, or the question is in head mode. */
    val judgedByHead: Boolean,
    /** Mirror look judged by head direction ("低置信"). */
    val lowConfidence: Boolean,
    /** 0 … 1 progress (signal held toward 1 s, shoulder peak / threshold). */
    val progress: Double,
)

enum class Phase {
    CHECKING,
    EXECUTING,
    DONE,
}

/** Outcome of a question (design `judging.outcome`). [correct] = clear or well-waited ("做对"). */
enum class Outcome(val correct: Boolean) {
    CLEAR(true),
    WAITED(true),
    LUCKY(false),
    NEAR_MISS(false),
    TIMEOUT(false),

    /** "跳过这题": counts as a question, not in the correct rate. */
    SKIPPED(false),
}

enum class SawHazard {
    SAW,
    NOT_SEEN,
    NO_HAZARD,
}

/** How the hazard was seen (result card "看到了（在左镜里）"). */
enum class SeenVia {
    GAZE,
    HEAD,
    MIRROR_INNER,
    MIRROR_LEFT,
    MIRROR_RIGHT,
    ;

    companion object {
        fun mirror(id: MirrorId) = when (id) {
            MirrorId.INNER -> MIRROR_INNER
            MirrorId.LEFT -> MIRROR_LEFT
            MirrorId.RIGHT -> MIRROR_RIGHT
        }
    }
}

/** Events for sound / panels, drained by [QuestionJudge.update]. */
sealed interface JudgeEvent {
    val t: Double

    data class StepLit(override val t: Double, val step: Step, val byHead: Boolean) : JudgeEvent

    data class StepStale(override val t: Double, val step: Step) : JudgeEvent

    /** A head-judged mirror look withdrawn because the same head turn went on to shoulder-check range (U15). */
    data class StepRevoked(override val t: Double, val step: Step) : JudgeEvent

    /** Head yaw first reached T_side in this turn: play the "嗒". */
    data class TurnReached(override val t: Double, val dir: Dir, val threshold: Double) : JudgeEvent

    /** A head turn ended: near miss ("再往左转 8°"), relaxed threshold, … */
    data class TurnEnded(override val t: Double, val excursion: Excursion) : JudgeEvent

    data class HazardSeen(override val t: Double, val via: SeenVia) : JudgeEvent

    data class Executed(override val t: Double) : JudgeEvent

    /** The signal goes off by itself after executing. */
    data class SignalAutoCancel(override val t: Double) : JudgeEvent

    /** Near miss: who and when (lane scenes: predicted moment of the overlap). */
    data class Conflict(override val t: Double, val actorId: String) : JudgeEvent

    data class Finished(override val t: Double, val outcome: Outcome) : JudgeEvent
}

/** What the HUD shows after a sample. */
data class LiveStatus(
    val t: Double,
    val phase: Phase,
    val steps: List<StepLive>,
    /** First moment all required steps were lit at once (s since S06), null until then. */
    val checkTime: Double?,
    val peakLeft: Double,
    val peakRight: Double,
    val thresholdLeft: Double,
    val thresholdRight: Double,
    val saw: Boolean,
    val doorAngle: Double,
    /** Continuous seconds without an eye sample in eye mode (≥ 5 s → show S17). */
    val eyeDropout: Double,
    val events: List<JudgeEvent>,
)

data class StepResult(
    val step: Step,
    val done: Boolean,
    val doneAt: Double?,
    val judgedByHead: Boolean,
    val lowConfidence: Boolean,
    /** Done once but older than 5 s at execute. */
    val stale: Boolean,
    /** Signal on the wrong side / door held with the near hand. */
    val wrong: Boolean,
)

/** 10 Hz gaze trace sample (gaze in eye mode, else head direction). */
data class TraceSample(val t: Double, val yaw: Double, val pitch: Double, val headYaw: Double, val eye: Boolean)

/** The final result record of a question (design `judging.metrics`). */
data class QuestionResult(
    val situation: Situation,
    val side: TrafficSide,
    val lesson: Int?,
    val seed: Long,
    val difficulty: Int?,
    val mode: JudgeMode,
    val outcome: Outcome,
    val steps: List<StepResult>,
    /** Steps in the order they were first done. */
    val actualOrder: List<Step>,
    val shoulderPeak: Double,
    val threshold: Double,
    val checkTime: Double?,
    val hazardKind: ActorKind?,
    val saw: SawHazard,
    val seenVia: SeenVia?,
    val execTime: Double?,
    val conflictTime: Double?,
    val conflictActor: String?,
    /** Signal on less than 3 s before executing: yellow tip (design `judging.signal`). */
    val signalTip: Boolean,
    val trace: List<TraceSample>,
) {
    val correct: Boolean
        get() = outcome.correct

    val judgedByHeadSteps: List<Step>
        get() = steps.filter { it.judgedByHead }.map { it.step }

    fun record(atMs: Long): QuestionRecord =
        QuestionRecord(
            atMs = atMs,
            situation = situation,
            outcome = outcome,
            lesson = lesson,
            mode = mode,
            shoulderDeg = shoulderPeak,
            thresholdDeg = threshold,
            checkSec = checkTime,
            saw = saw,
            steps = steps.associate { it.step to it.done },
            leftHandTraffic = side == TrafficSide.LEFT_HAND,
        )
}

/**
 * Judges one question (design `model.judging`). Feed time-stamped [JudgeSample]s with [update]; read the live step
 * status for the HUD from its return value and the final [result] once [phase] is [Phase.DONE].
 *
 * Rules implemented (degrees, seconds):
 * - look, eye: gaze ray inside the mirror's target (glass expanded 50 %) for ≥ 0.12 s accumulated (gaps > 0.3 s reset);
 * - look, head (head mode, or an eye-mode sample without gaze → that step flagged "按头部判断"): head direction within
 *   [HEAD_LOOK_CONE] of the mirror, angular speed < 30°/s, held ≥ 0.3 s, and the head turn's peak toward that side
 *   stays < T_side − 8° (a later rise withdraws it); flagged low confidence;
 * - shoulder (both modes): head yaw toward the side ≥ T_side held ≥ 0.15 s; near misses relax T via [TurnProfile];
 * - fresh: side mirrors and shoulder checks must be ≤ 5.0 s old at execute;
 * - signal: right side on ≥ 1.0 s before execute; wrong side = ✗; < 3.0 s = yellow tip; auto off after execute;
 * - far hand (doors): the hand holding the handle at execute is the far hand;
 * - saw hazard (only while the hazard is still a threat — before it has passed — and not plainly in the forward view):
 *   (a) gaze on its box + 0.5 m ≥ 0.1 s, (b) head rule: an un-occluded part within ±40° of the head ≥ 0.15 s,
 *   (c) driver seat: drawn in the mirror you are looking at (look rule) for its look time;
 * - execute: A press (lanes / turn) or door angle crossing 12°; conflict: 3 s slot prediction / door sweep;
 * - outcome: near miss > timeout > (lessons 1–2: clear when all targets are lit) > lucky (steps missing) > well
 *   waited (saw it, executed after it passed) > clear.
 */
class QuestionJudge(
    val scenario: Scenario,
    val mode: JudgeMode,
    val profile: TurnProfile,
    aims: MirrorAims = MirrorAims(),
) {
    var aims: MirrorAims = aims
        private set

    private val cabin = scenario.cabin
    private val lessonKind = scenario.situation.action == ActionKind.MIRRORS_LESSON || scenario.situation.action == ActionKind.BLIND_SPOT_LESSON
    private val door: DoorModel? = if (scenario.situation.action == ActionKind.DOOR) DoorModel(cabin) else null

    private class Rec {
        var doneAt: Double? = null
        var firstDoneAt: Double? = null
        var byHead = false
        var lowConf = false
        var staleAnnounced = false

        /** First / latest time the step block lit (all steps, incl. signal, far hand, confirms). */
        var firstLit: Double? = null
        var lastLit: Double? = null

        /** Head-judged look still tied to an ongoing head turn (excursion id), or −1. */
        var provisionalExcursion = -1
        var provisionalDir: Dir? = null
        var beforeProvisional: Double? = null
        var beforeProvisionalFirst: Double? = null
        var beforeByHead = false
        var beforeLowConf = false
    }

    private val recs = Step.values().associateWith { Rec() }

    private class LookAcc {
        var eyeAcc = 0.0
        var eyeLastHit = Double.NEGATIVE_INFINITY
        var headAcc = 0.0
        var active = false
        var sawAcc = 0.0
    }

    private val looks = cabin.mirrors.associate { it.id to LookAcc() }

    private class Exc {
        var active = false
        var peak = 0.0
        var id = 0
        var reachedAnnounced = false
        var holdStart: Double? = null
    }

    private val exc = mapOf(Dir.LEFT to Exc(), Dir.RIGHT to Exc())
    private var excCounter = 0
    private val peak = mutableMapOf(Dir.LEFT to 0.0, Dir.RIGHT to 0.0)

    private val history = ArrayDeque<Pair<Double, Vec3>>()
    private var lastT: Double? = null
    private var lastSample: JudgeSample? = null

    private var signal = Signal.OFF
    private var signalSince = 0.0
    private var signalWrongSeen = false

    private var sawAt: Double? = null
    private var seenVia: SeenVia? = null
    private var accGaze = 0.0
    private var accGazeLast = Double.NEGATIVE_INFINITY
    private var accHead = 0.0
    private val confirmed = mutableSetOf<Dir>()

    var phase = Phase.CHECKING
        private set
    private var execT: Double? = null
    private var conflictAt: Double? = null
    private var conflictActor: String? = null
    private var timedOut = false
    private var outcome: Outcome? = null
    private var finishT = 0.0
    private var checkTime: Double? = null
    private var frozenSteps: List<StepLive>? = null
    private var stepsAtExec: Map<Step, Boolean> = emptyMap()
    private var signalTip = false
    private var eyeDropout = 0.0
    private val trace = ArrayList<TraceSample>()
    private var lastTraceT = Double.NEGATIVE_INFINITY
    private val events = ArrayList<JudgeEvent>()

    /** Lesson 1: the live aim of a side mirror (left stick / right stick, 1° per push). */
    fun setAim(dir: Dir, aimDeg: Double) {
        aims = aims.with(dir, aimDeg)
    }

    /** Lesson 1 "确认" for a side mirror (T18): remembers the aim and lights CONFIRM_<dir>. */
    fun confirmMirror(dir: Dir, aimDeg: Double, t: Double) {
        setAim(dir, aimDeg)
        confirmed += dir
        mark(Step.confirm(dir), t, byHead = false, lowConf = false)
    }

    /** B key / P2 "关门" (T7): the door shuts again (only while still checking; an executed door stays). */
    fun closeDoor() {
        if (phase == Phase.CHECKING) door?.close()
    }

    /** "跳过这题" (T17): ends the question as skipped. */
    fun skip(t: Double): QuestionResult {
        if (phase != Phase.DONE) finish(t, Outcome.SKIPPED)
        return result()
    }

    fun update(s: JudgeSample): LiveStatus {
        val dt = lastT?.let { (s.t - it).coerceIn(0.0, 0.25) } ?: 0.0
        lastT = s.t
        lastSample = s
        if (phase == Phase.DONE) return status(s.t)

        val eyeUsable = mode == JudgeMode.EYE && s.gaze != null
        eyeDropout = if (mode == JudgeMode.EYE && s.gaze == null) eyeDropout + dt else 0.0
        val headDir = Angles.dir(s.headYaw, s.headPitch)
        val speed = angularSpeed(s.t, headDir)

        updateExcursions(s)
        if (phase == Phase.CHECKING) {
            updateShoulders(s)
            updateLooks(s, dt, eyeUsable, headDir, speed)
            updateSignal(s)
        }
        updateDoor(s, dt)
        updateSaw(s, dt, eyeUsable)

        when (phase) {
            Phase.CHECKING -> {
                if (!lessonKind && checkTime == null && scenario.requiredSteps.all { valid(it, s.t) }) checkTime = s.t
                val limit = scenario.timeLimit
                when {
                    limit != null && s.t >= limit -> {
                        timedOut = true
                        finish(s.t, Outcome.TIMEOUT)
                    }
                    lessonKind -> if (scenario.requiredSteps.all { valid(it, s.t) }) {
                        checkTime = s.t
                        finish(s.t, Outcome.CLEAR)
                    }
                    door != null -> if (door.executed) execute(s.t)
                    s.exec -> execute(s.t)
                }
            }
            Phase.EXECUTING -> {
                val e = execT!!
                if (door != null) {
                    for (a in scenario.actors) {
                        if (a.role == ActorRole.DECOR || a.role == ActorRole.PARKED) continue
                        if (DoorModel.conflicts(cabin, door.angle, a.box(s.t))) {
                            conflictAt = s.t
                            conflictActor = a.id
                            events += JudgeEvent.Conflict(s.t, a.id)
                            finish(s.t, Outcome.NEAR_MISS)
                            break
                        }
                    }
                } else if (conflictAt != null && s.t >= conflictAt!!) {
                    finish(s.t, Outcome.NEAR_MISS)
                }
                if (phase == Phase.EXECUTING && s.t >= e + LaneChangeModel.DURATION) finish(s.t, null)
            }
            Phase.DONE -> Unit
        }

        if (s.t - lastTraceT >= TRACE_DT - 1e-9) {
            lastTraceT = s.t
            val g = s.gaze
            trace += if (eyeUsable && g != null) TraceSample(s.t, Angles.yawOf(g.dir), Angles.pitchOf(g.dir), s.headYaw, true)
            else TraceSample(s.t, s.headYaw, s.headPitch, s.headYaw, false)
        }
        return status(s.t)
    }

    // ---------------------------------------------------------------- rules

    private fun toward(yaw: Double, dir: Dir) = yaw * dir.sign

    private fun angularSpeed(t: Double, dir: Vec3): Double {
        history.addLast(t to dir)
        while (history.size > 2 && history.first().first < t - 0.5) history.removeFirst()
        var ref: Pair<Double, Vec3>? = null
        for (h in history) if (h.first <= t - SPEED_WINDOW) ref = h
        if (ref == null) ref = history.first()
        val span = t - ref.first
        if (span < 1e-6) return 0.0
        return Angles.between(ref.second, dir) / span
    }

    private fun updateExcursions(s: JudgeSample) {
        for (d in Dir.values()) {
            val e = exc.getValue(d)
            val y = toward(s.headYaw, d)
            peak[d] = max(peak.getValue(d), y)
            if (y > EXCURSION_START) {
                if (!e.active) {
                    e.active = true
                    e.peak = 0.0
                    e.id = ++excCounter
                    e.reachedAnnounced = false
                }
                e.peak = max(e.peak, y)
                // a head-judged mirror look on this side is void once the same turn reaches shoulder range (U15)
                if (e.peak >= profile.threshold(d) - TurnProfile.NEAR_MISS_BAND) revokeProvisional(d, e.id, s.t)
            } else if (e.active) {
                e.active = false
                val verdict = profile.onExcursion(d, e.peak)
                events += JudgeEvent.TurnEnded(s.t, verdict)
                settleProvisional(e.id)
            }
        }
    }

    private fun updateShoulders(s: JudgeSample) {
        for (d in Dir.values()) {
            val e = exc.getValue(d)
            val th = profile.threshold(d)
            if (toward(s.headYaw, d) >= th) {
                if (!e.reachedAnnounced) {
                    e.reachedAnnounced = true
                    events += JudgeEvent.TurnReached(s.t, d, th)
                }
                val start = e.holdStart ?: s.t.also { e.holdStart = it }
                if (s.t - start >= SHOULDER_HOLD - 1e-9) mark(Step.shoulder(d), s.t, byHead = mode == JudgeMode.HEAD, lowConf = false)
            } else {
                e.holdStart = null
            }
        }
    }

    private fun updateLooks(s: JudgeSample, dt: Double, eyeUsable: Boolean, headDir: Vec3, speed: Double) {
        for (m in cabin.mirrors) {
            val acc = looks.getValue(m.id)
            val step = stepOf(m.id)
            acc.active = false
            if (eyeUsable) {
                acc.headAcc = 0.0
                if (gazeHitsMirror(s.gaze!!, m)) {
                    if (s.t - acc.eyeLastHit > GAP_RESET) acc.eyeAcc = 0.0
                    acc.eyeAcc += dt
                    acc.eyeLastHit = s.t
                    acc.active = true
                    if (acc.eyeAcc >= EYE_LOOK - 1e-9) mark(step, s.t, byHead = false, lowConf = false)
                }
            } else {
                val side = m.lookSide
                val limit = profile.threshold(side) - TurnProfile.NEAR_MISS_BAND
                val turn = exc.getValue(side)
                val within = Angles.between(headDir, m.pos - s.headPos) <= HEAD_LOOK_CONE
                // the whole head turn this look belongs to must stay below T − 8° (else it is a shoulder check passing by)
                val ok = within && speed < HEAD_MAX_SPEED && toward(s.headYaw, side) < limit && (!turn.active || turn.peak < limit)
                if (ok) {
                    acc.headAcc += dt
                    acc.active = true
                    if (acc.headAcc >= HEAD_LOOK - 1e-9) {
                        val e = exc.getValue(side)
                        markProvisional(step, s.t, if (e.active) e.id else -1, side)
                    }
                } else {
                    acc.headAcc = 0.0
                }
            }
        }
    }

    private fun updateSignal(s: JudgeSample) {
        if (s.signal != signal) {
            signal = s.signal
            signalSince = s.t
        }
    }

    private fun updateDoor(s: JudgeSample, dt: Double) {
        val d = door ?: return
        d.update(dt, s.grab, s.push)
        if (phase == Phase.CHECKING && s.grab != null) {
            if (s.grab == cabin.farHand) mark(Step.FAR_HAND, s.t, byHead = false, lowConf = false)
        }
    }

    private fun updateSaw(s: JudgeSample, dt: Double, eyeUsable: Boolean) {
        val h = scenario.hazard ?: return
        if (sawAt != null) return
        if (execT != null) return
        val pass = scenario.hazardPassTime
        if (pass != null && s.t >= pass) return
        val box = h.box(s.t)
        if (Visibility.anyInView(box, cabin, 0.0, BlindSpot.HMD_HALF_FOV)) {
            accHead = 0.0
            return
        }
        // (a) gaze on the box + 0.5 m
        val g = s.gaze
        if (eyeUsable && g != null) {
            if (box.grown(HAZARD_MARGIN).hitByRay(g.origin, g.dir)) {
                if (s.t - accGazeLast > GAP_RESET) accGaze = 0.0
                accGaze += dt
                accGazeLast = s.t
                if (accGaze >= SAW_GAZE - 1e-9) return saw(s.t, SeenVia.GAZE)
            }
        } else {
            // (b) head: an un-occluded part within ±40° of the head direction
            if (Visibility.anyInView(box, cabin, s.headYaw, Visibility.JUDGE_HALF_FOV, s.headPitch)) {
                accHead += dt
                if (accHead >= SAW_HEAD - 1e-9) return saw(s.t, SeenVia.HEAD)
            } else {
                accHead = 0.0
            }
        }
        // (c) drawn in the mirror you are looking at (driver seat only: the rear seat has no mirrors)
        for (m in cabin.mirrors) {
            val acc = looks.getValue(m.id)
            if (acc.active && MirrorModel.sees(box, m, cabin, aims.of(m.id), s.headPos)) {
                acc.sawAcc += dt
                if (acc.sawAcc >= (if (eyeUsable) EYE_LOOK else HEAD_LOOK) - 1e-9) return saw(s.t, SeenVia.mirror(m.id))
            } else {
                acc.sawAcc = 0.0
            }
        }
    }

    private fun saw(t: Double, via: SeenVia) {
        sawAt = t
        seenVia = via
        events += JudgeEvent.HazardSeen(t, via)
        if (scenario.requiredSteps.contains(Step.SEE_HAZARD)) mark(Step.SEE_HAZARD, t, byHead = via == SeenVia.HEAD, lowConf = false)
    }

    private fun gazeHitsMirror(g: GazeRay, m: MirrorSpec): Boolean {
        val n = (cabin.eye - m.pos).normalized()
        val dn = g.dir.dot(n)
        if (dn > -1e-6) return false
        val tHit = (m.pos - g.origin).dot(n) / dn
        if (tHit <= 0) return false
        val p = g.origin + g.dir * tHit - m.pos
        val hx = Vec3.UP.cross(n).normalized()
        val vy = n.cross(hx)
        val ex = m.width * (1 + TARGET_EXPAND) / 2
        val ey = m.height * (1 + TARGET_EXPAND) / 2
        return kotlin.math.abs(p.dot(hx)) <= ex && kotlin.math.abs(p.dot(vy)) <= ey
    }

    private fun stepOf(id: MirrorId) = when (id) {
        MirrorId.INNER -> Step.INNER_MIRROR
        MirrorId.LEFT -> Step.LEFT_MIRROR
        MirrorId.RIGHT -> Step.RIGHT_MIRROR
    }

    private fun mark(step: Step, t: Double, byHead: Boolean, lowConf: Boolean) {
        val r = recs.getValue(step)
        r.doneAt = t
        if (r.firstDoneAt == null) r.firstDoneAt = t
        r.byHead = byHead
        r.lowConf = lowConf
        r.staleAnnounced = false
        r.provisionalExcursion = -1
    }

    private fun markProvisional(step: Step, t: Double, excursion: Int, side: Dir) {
        val r = recs.getValue(step)
        val keepBefore = r.provisionalExcursion != excursion || excursion < 0
        val bDone = r.doneAt
        val bFirst = r.firstDoneAt
        val bHead = r.byHead
        val bLow = r.lowConf
        mark(step, t, byHead = true, lowConf = true)
        if (excursion >= 0) {
            if (keepBefore) {
                r.beforeProvisional = bDone
                r.beforeProvisionalFirst = bFirst
                r.beforeByHead = bHead
                r.beforeLowConf = bLow
            }
            r.provisionalExcursion = excursion
            r.provisionalDir = side
        }
    }

    private fun revokeProvisional(dir: Dir, excursion: Int, t: Double) {
        for ((step, r) in recs) {
            if (r.provisionalExcursion == excursion && r.provisionalDir == dir) {
                r.doneAt = r.beforeProvisional
                r.firstDoneAt = r.beforeProvisionalFirst
                r.byHead = r.beforeByHead
                r.lowConf = r.beforeLowConf
                r.provisionalExcursion = -1
                events += JudgeEvent.StepRevoked(t, step)
            }
        }
        for (m in cabin.mirrors) if (m.lookSide == dir) looks.getValue(m.id).headAcc = 0.0
    }

    private fun settleProvisional(excursion: Int) {
        for (r in recs.values) if (r.provisionalExcursion == excursion) r.provisionalExcursion = -1
    }

    /** Whether [step] counts as done at time [t]. */
    private fun valid(step: Step, t: Double): Boolean {
        val r = recs.getValue(step)
        return when (step) {
            Step.SIGNAL_LEFT, Step.SIGNAL_RIGHT -> signal.dir == step.dir && t - signalSince >= SIGNAL_MIN - 1e-9
            Step.FAR_HAND -> door?.holder?.let { it == cabin.farHand } ?: (door?.lastGrabHand == cabin.farHand)
            Step.CONFIRM_LEFT, Step.CONFIRM_RIGHT -> step.dir in confirmed
            Step.SEE_HAZARD -> sawAt != null
            else -> {
                val d = r.doneAt ?: return false
                !(step.needsFresh && !lessonKind && t - d > FRESH)
            }
        }
    }

    private fun liveStep(step: Step, t: Double): StepLive {
        val r = recs.getValue(step)
        val ok = valid(step, t)
        val state =
            when (step) {
                Step.SIGNAL_LEFT, Step.SIGNAL_RIGHT ->
                    if (signal.dir != null && signal.dir != step.dir) StepState.WRONG else if (ok) StepState.DONE else StepState.PENDING
                Step.FAR_HAND -> {
                    val h = door?.holder ?: door?.lastGrabHand
                    if (h == null) StepState.PENDING else if (h == cabin.farHand) StepState.DONE else StepState.WRONG
                }
                else -> if (ok) StepState.DONE else if (r.doneAt != null) StepState.STALE else StepState.PENDING
            }
        if (state == StepState.STALE && !r.staleAnnounced) {
            r.staleAnnounced = true
            events += JudgeEvent.StepStale(t, step)
        }
        val progress =
            when (step) {
                Step.SIGNAL_LEFT, Step.SIGNAL_RIGHT -> if (signal.dir == step.dir) ((t - signalSince) / SIGNAL_MIN).coerceIn(0.0, 1.0) else 0.0
                Step.SHOULDER_LEFT, Step.SHOULDER_RIGHT -> if (ok) 1.0 else (peak.getValue(step.dir!!) / profile.threshold(step.dir!!)).coerceIn(0.0, 1.0)
                else -> if (ok) 1.0 else 0.0
            }
        val byHead = r.byHead || (mode == JudgeMode.HEAD && step.mirrorId != null)
        return StepLive(step, state, r.doneAt, r.firstDoneAt, byHead && r.doneAt != null, r.lowConf, progress)
    }

    private fun execute(t: Double) {
        execT = t
        stepsAtExec = scenario.requiredSteps.associateWith { valid(it, t) }
        frozenSteps = scenario.requiredSteps.map { liveStep(it, t) }
        val sigStep = scenario.requiredSteps.firstOrNull { it == Step.SIGNAL_LEFT || it == Step.SIGNAL_RIGHT }
        signalTip = sigStep != null && stepsAtExec[sigStep] == true && t - signalSince < SIGNAL_TIP
        phase = Phase.EXECUTING
        events += JudgeEvent.Executed(t)
        if (signal != Signal.OFF) events += JudgeEvent.SignalAutoCancel(t)
        val slot = scenario.slot
        if (slot != null) {
            var best: Pair<String, Double>? = null
            for (a in scenario.actors) {
                if (a.role == ActorRole.DECOR || a.role == ActorRole.PARKED) continue
                val dtc = LaneChangeModel.predictConflict(a, t, slot) ?: continue
                if (best == null || dtc < best.second) best = a.id to dtc
            }
            if (best != null) {
                conflictAt = t + best.second
                conflictActor = best.first
                events += JudgeEvent.Conflict(t + best.second, best.first)
            }
        }
    }

    private fun finish(t: Double, forced: Outcome?) {
        if (phase == Phase.CHECKING && frozenSteps == null) {
            stepsAtExec = scenario.requiredSteps.associateWith { valid(it, t) }
            frozenSteps = scenario.requiredSteps.map { liveStep(it, t) }
        }
        val e = execT
        outcome =
            forced ?: when {
                conflictAt != null -> Outcome.NEAR_MISS
                timedOut -> Outcome.TIMEOUT
                lessonKind -> Outcome.CLEAR
                stepsAtExec.values.any { !it } -> Outcome.LUCKY
                scenario.hazard != null && sawAt != null && e != null && scenario.hazardPassTime != null && e >= scenario.hazardPassTime!! -> Outcome.WAITED
                else -> Outcome.CLEAR
            }
        finishT = t
        phase = Phase.DONE
        events += JudgeEvent.Finished(t, outcome!!)
    }

    private val lit = mutableSetOf<Step>()

    private fun status(t: Double): LiveStatus {
        val steps = frozenSteps ?: scenario.requiredSteps.map { liveStep(it, t) }
        for (st in steps) {
            if (st.state == StepState.DONE) {
                if (lit.add(st.step)) {
                    events += JudgeEvent.StepLit(t, st.step, st.judgedByHead)
                    val r = recs.getValue(st.step)
                    if (r.firstLit == null) r.firstLit = t
                    r.lastLit = t
                }
            } else {
                lit.remove(st.step)
            }
        }
        val out = ArrayList(events)
        events.clear()
        return LiveStatus(
            t = t,
            phase = phase,
            steps = steps,
            checkTime = checkTime,
            peakLeft = peak.getValue(Dir.LEFT),
            peakRight = peak.getValue(Dir.RIGHT),
            thresholdLeft = profile.threshold(Dir.LEFT),
            thresholdRight = profile.threshold(Dir.RIGHT),
            saw = sawAt != null,
            doorAngle = door?.angle ?: 0.0,
            eyeDropout = eyeDropout,
            events = out,
        )
    }

    /** Current door angle (door scenes), degrees. */
    val doorAngle: Double
        get() = door?.angle ?: 0.0

    /** Ghost car pose at [t] (lane / turn scenes, null before execute). */
    fun ghost(t: Double): GhostPose? {
        val e = execT ?: return null
        if (scenario.slot == null) return null
        val stopAt = conflictAt?.let { it - e }
        val local = (t - e).coerceAtMost(stopAt ?: LaneChangeModel.DURATION)
        return GhostRoute.pose(scenario.situation, scenario.side, scenario.road, local)
    }

    /** The final result (valid once [phase] is [Phase.DONE]). */
    fun result(): QuestionResult {
        check(phase == Phase.DONE) { "question not finished" }
        val e = execT
        val steps =
            scenario.requiredSteps.map { st ->
                val r = recs.getValue(st)
                val done = stepsAtExec[st] == true
                val live = frozenSteps?.firstOrNull { it.step == st }
                StepResult(
                    step = st,
                    done = done,
                    doneAt = r.doneAt ?: r.lastLit,
                    judgedByHead = live?.judgedByHead ?: false,
                    lowConfidence = r.lowConf,
                    stale = !done && live?.state == StepState.STALE,
                    wrong = live?.state == StepState.WRONG,
                )
            }
        fun firstTime(st: Step): Double? = recs.getValue(st).let { r -> if (st.mirrorId != null || st == Step.SHOULDER_LEFT || st == Step.SHOULDER_RIGHT) r.firstDoneAt else r.firstLit }
        val order = scenario.requiredSteps.filter { firstTime(it) != null }.sortedBy { firstTime(it) }
        val d = scenario.dir
        val shoulderPeak = if (scenario.situation.action == ActionKind.MIRRORS_LESSON) max(peak.getValue(Dir.LEFT), peak.getValue(Dir.RIGHT)) else peak.getValue(d)
        return QuestionResult(
            situation = scenario.situation,
            side = scenario.side,
            lesson = scenario.lesson,
            seed = scenario.seed,
            difficulty = scenario.difficulty,
            mode = mode,
            outcome = outcome!!,
            steps = steps,
            actualOrder = order,
            shoulderPeak = shoulderPeak,
            threshold = profile.threshold(d),
            checkTime = checkTime,
            hazardKind = scenario.hazard?.kind,
            saw = if (scenario.hazard == null) SawHazard.NO_HAZARD else if (sawAt != null && (e == null || sawAt!! <= e)) SawHazard.SAW else SawHazard.NOT_SEEN,
            seenVia = seenVia,
            execTime = e,
            conflictTime = conflictAt,
            conflictActor = conflictActor,
            signalTip = signalTip,
            trace = trace.toList(),
        )
    }

    companion object {
        const val EYE_LOOK = 0.12
        const val HEAD_LOOK = 0.30

        /**
         * Head-direction look cone. DEVIATION: the design text says ≤ 10°, but its own test U13 needs 18° ✓ and
         * 24° ✗; 20° satisfies U13 (and B27 lenient-first); U15 still holds through the turn-peak rule.
         */
        const val HEAD_LOOK_CONE = 20.0
        const val HEAD_MAX_SPEED = 30.0
        const val SHOULDER_HOLD = 0.15
        const val FRESH = 5.0
        const val SIGNAL_MIN = 1.0
        const val SIGNAL_TIP = 3.0
        const val SAW_GAZE = 0.10
        const val SAW_HEAD = 0.15
        const val HAZARD_MARGIN = 0.5
        const val TARGET_EXPAND = 0.5
        const val GAP_RESET = 0.3
        const val SPEED_WINDOW = 0.1
        const val EXCURSION_START = 15.0
        const val TRACE_DT = 0.1
        const val EYE_DROPOUT_NOTICE = 5.0
    }
}
