package io.embodit.shouldercheck.app

import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.GazeRay
import io.embodit.shouldercheck.domain.HazardSpec
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.JudgeSample
import io.embodit.shouldercheck.domain.MirrorAims
import io.embodit.shouldercheck.domain.MirrorId
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.Phase
import io.embodit.shouldercheck.domain.Progress
import io.embodit.shouldercheck.domain.QuestionJudge
import io.embodit.shouldercheck.domain.QuestionRecord
import io.embodit.shouldercheck.domain.SawHazard
import io.embodit.shouldercheck.domain.Scenario
import io.embodit.shouldercheck.domain.Scenes
import io.embodit.shouldercheck.domain.Signal
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.TurnProfile
import io.embodit.shouldercheck.domain.TurnRange
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.platform.ScLog
import kotlin.math.abs
import kotlin.math.max

/**
 * Scripted head / gaze / signal / execute samples for the debug hooks. They are fed into a real [QuestionJudge] (the same
 * rules as live input), never written into a result directly: `result outcome=…` runs a whole question offline,
 * `goto S06 done=I,L` and `goto S08` feed the live question through [AppController.feedSample].
 */
internal object DebugScripts {
    /** Builds samples like the frame system would at 72 Hz. */
    class Driver(private val sink: (JudgeSample) -> Unit, startT: Double, private val eye: Boolean) {
        var t = startT
        var yaw = 0.0
        var pitch = 0.0
        var signal = Signal.OFF
        private val dt = 1.0 / 72.0

        private fun gaze(): GazeRay? = if (eye) GazeRay(Vec3.ZERO, Angles.dir(yaw, pitch)) else null

        fun step(exec: Boolean = false) {
            sink(JudgeSample(t, yaw, pitch, Vec3.ZERO, gaze(), signal, null, 0.0, exec))
            t += dt
        }

        fun hold(sec: Double) {
            val end = t + sec
            while (t < end - 1e-9) step()
        }

        fun turn(toYaw: Double, toPitch: Double = 0.0, degPerSec: Double = 150.0) {
            val y0 = yaw
            val p0 = pitch
            val dist = max(abs(toYaw - y0), abs(toPitch - p0))
            val dur = dist / degPerSec
            val start = t
            while (t < start + dur - 1e-9) {
                val u = ((t - start) / dur).coerceIn(0.0, 1.0)
                yaw = y0 + (toYaw - y0) * u
                pitch = p0 + (toPitch - p0) * u
                step()
            }
            yaw = toYaw
            pitch = toPitch
        }

        /** Look at a mirror (head and eyes on it, held still 0.5 s) and come back to the front. */
        fun lookMirror(s: Scenario, id: MirrorId) {
            val m = s.cabin.mirror(id) ?: return
            turn(m.yawDeg, m.pitchDeg)
            hold(0.5)
            turn(0.0, 0.0)
            hold(0.15)
        }

        fun shoulder(dir: Dir, deg: Double = 66.0) {
            turn(dir.sign * deg)
            hold(0.45)
            turn(0.0)
            hold(0.15)
        }
    }

    /** `goto S06 done=I,L,R,S,G`: inner / left / right mirror, shoulder, signal, fed into the live question. */
    fun inject(r: QuestionRun, done: Set<String>, eye: Boolean) {
        val sc = r.scenario
        val d = Driver({ AppController.feedSample(r, it) }, maxOf(0.0, r.t), eye)
        if ("I" in done) d.lookMirror(sc, MirrorId.INNER)
        if ("L" in done) d.lookMirror(sc, MirrorId.LEFT)
        if ("R" in done) d.lookMirror(sc, MirrorId.RIGHT)
        if ("S" in done) d.shoulder(sc.dir)
        if ("G" in done) {
            r.signal = Signal.of(sc.dir)
            d.signal = r.signal
            AppController.audio?.indicator(true)
            d.hold(1.1)
        }
        ScLog.i("DEBUG injected done=$done until t=${"%.2f".format(d.t)}")
    }

    /** A full clean check for the live question (`goto S08`): mirrors, shoulder, signal 3.2 s, execute, then 3 s. */
    fun cleanRun(r: QuestionRun, eye: Boolean) {
        val sc = r.scenario
        val d = Driver({ AppController.feedSample(r, it) }, maxOf(0.0, r.t), eye)
        d.signal = Signal.of(sc.dir)
        r.signal = d.signal
        if (Situation.LANE_LEFT == sc.situation || sc.requiredSteps.contains(io.embodit.shouldercheck.domain.Step.INNER_MIRROR)) d.lookMirror(sc, MirrorId.INNER)
        d.lookMirror(sc, MirrorId.side(sc.dir))
        d.shoulder(sc.dir)
        d.hold(1.2)
        d.step(exec = true)
        var guard = 0
        while (r.judge.phase != Phase.DONE && guard++ < 72 * 6) d.step()
    }

    /** Runs a whole question offline for `result outcome=clear|waited|lucky|miss|timeout mode=eye|head`. */
    fun result(outcome: String, mode: JudgeMode, side: TrafficSide, aims: MirrorAims): Pair<Scenario, QuestionJudge> {
        val dir = Dir.LEFT.physical(side)
        val eye = mode == JudgeMode.EYE
        val sc: Scenario =
            when (outcome) {
                "waited" -> Scenes.build(Situation.LANE_LEFT, side, HazardSpec(ActorKind.EBIKE, dir, 3.0, 1.0))
                "miss" -> Scenes.build(Situation.LANE_LEFT, side, HazardSpec(ActorKind.EBIKE, dir, 3.0, 3.0))
                "timeout" -> Scenes.build(Situation.LANE_LEFT, side, null, timeLimit = 8.0)
                else -> Scenes.build(Situation.LANE_LEFT, side, null)
            }
        // a fresh profile with the session's thresholds: scripted near misses must not relax the real one
        val judge = QuestionJudge(sc, mode, TurnProfile(TurnRange(80.0, 80.0, skipped = true)), aims)
        val d = Driver({ judge.update(it) }, 0.0, eye)
        when (outcome) {
            "clear" -> {
                d.signal = Signal.of(dir)
                d.lookMirror(sc, MirrorId.INNER)
                d.lookMirror(sc, MirrorId.side(dir))
                d.shoulder(dir)
                d.hold(1.4)
                d.step(exec = true)
            }
            "waited" -> {
                d.lookMirror(sc, MirrorId.INNER)
                d.lookMirror(sc, MirrorId.side(dir))
                d.shoulder(dir) // sees the e-bike beside you
                val pass = sc.hazardPassTime ?: 4.0
                d.signal = Signal.of(dir)
                d.hold(maxOf(0.2, pass - d.t + 0.3))
                d.lookMirror(sc, MirrorId.side(dir))
                d.shoulder(dir)
                d.step(exec = true)
            }
            "lucky" -> {
                d.signal = Signal.of(dir)
                d.hold(1.3)
                d.step(exec = true)
            }
            "miss" -> {
                d.signal = Signal.of(dir)
                d.lookMirror(sc, MirrorId.side(dir))
                d.hold(0.3)
                d.step(exec = true)
            }
            "timeout" -> d.hold(8.2)
            else -> d.step(exec = true)
        }
        var guard = 0
        while (judge.phase != Phase.DONE && guard++ < 72 * 12) d.step()
        val got = if (judge.phase == Phase.DONE) judge.result().outcome else null
        ScLog.i("DEBUG scripted result want=$outcome got=$got mode=$mode")
        return sc to judge
    }

    /** `seed-scores=1`: sample records for the scorecard (S12), plus the numbers about you. */
    fun sampleScores(p: Progress): Progress {
        val now = System.currentTimeMillis()
        val rows =
            listOf(
                Situation.LANE_LEFT to listOf("CLEAR", "CLEAR", "WAITED", "CLEAR", "CLEAR", "CLEAR", "WAITED", "CLEAR", "CLEAR", "LUCKY"),
                Situation.LANE_RIGHT to listOf("CLEAR", "LUCKY", "CLEAR", "NEAR_MISS", "CLEAR"),
                Situation.PULL_OUT to listOf("CLEAR", "CLEAR", "LUCKY"),
                Situation.DOOR_DRIVER to listOf("NEAR_MISS", "CLEAR", "WAITED", "CLEAR"),
                Situation.DOOR_REAR to listOf("CLEAR", "LUCKY"),
            )
        var out = p
        var i = 0
        for ((sit, outs) in rows) {
            for ((k, o) in outs.withIndex()) {
                val outcome = Outcome.valueOf(o)
                out =
                    out.withRecord(
                        QuestionRecord(
                            atMs = now - (100 - i) * 60_000L,
                            situation = sit,
                            outcome = outcome,
                            lesson = sit.lesson,
                            mode = JudgeMode.HEAD,
                            shoulderDeg = 58.0 + (k * 7) % 15,
                            thresholdDeg = 60.0,
                            checkSec = if (outcome == Outcome.LUCKY) null else 2.0 + (k % 4) * 0.4,
                            saw = if (outcome == Outcome.WAITED) SawHazard.SAW else SawHazard.NO_HAZARD,
                            steps = emptyMap(),
                        )
                    )
                i++
            }
        }
        return out.copy(range = out.range ?: TurnRange(72.0, 68.0), blindSpotLeft = out.blindSpotLeft ?: 5.1, blindSpotRight = out.blindSpotRight ?: 4.8)
    }
}
