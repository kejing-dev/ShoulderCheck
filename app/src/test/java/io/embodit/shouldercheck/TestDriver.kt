package io.embodit.shouldercheck

import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.GazeRay
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.JudgeEvent
import io.embodit.shouldercheck.domain.JudgeSample
import io.embodit.shouldercheck.domain.LiveStatus
import io.embodit.shouldercheck.domain.Phase
import io.embodit.shouldercheck.domain.QuestionJudge
import io.embodit.shouldercheck.domain.Signal
import io.embodit.shouldercheck.domain.Vec3

/**
 * Feeds a [QuestionJudge] like the frame system would: 72 Hz samples, a head pose that moves at a given angular
 * speed, an optional gaze target point (null target = gaze along the head; [eyeOff] = no gaze sample at all).
 */
class TestDriver(val judge: QuestionJudge, private val hz: Double = 72.0) {
    var t = 0.0
        private set
    var yaw = 0.0
    var pitch = 0.0
    var signal = Signal.OFF
    var grab: Hand? = null
    var push = 0.0
    var eyeOff = false
    var gazeTarget: Vec3? = null
    val events = ArrayList<JudgeEvent>()
    var last: LiveStatus? = null
        private set

    private val dt get() = 1.0 / hz

    private fun gaze(): GazeRay? {
        if (eyeOff) return null
        val target = gazeTarget
        val dir = if (target != null) target.normalized() else Angles.dir(yaw, pitch)
        return GazeRay(Vec3.ZERO, dir)
    }

    fun step(exec: Boolean = false): LiveStatus {
        val s = judge.update(JudgeSample(t = t, headYaw = yaw, headPitch = pitch, gaze = gaze(), signal = signal, grab = grab, push = push, exec = exec))
        events += s.events
        last = s
        t += dt
        return s
    }

    /** Keep everything as it is for [seconds]. */
    fun hold(seconds: Double) {
        val end = t + seconds
        while (t < end - 1e-9) step()
    }

    /** Hold until absolute time [time]. */
    fun until(time: Double) {
        while (t < time - 1e-9) step()
    }

    /** Turn the head to ([toYaw], [toPitch]) at [degPerSec]. */
    fun turn(toYaw: Double, toPitch: Double = pitch, degPerSec: Double = 150.0) {
        val y0 = yaw
        val p0 = pitch
        val dist = maxOf(kotlin.math.abs(toYaw - y0), kotlin.math.abs(toPitch - p0))
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

    /** Look at [point] with the eyes only for [seconds], then back to gazing along the head. */
    fun gazeAt(point: Vec3, seconds: Double) {
        gazeTarget = point
        hold(seconds)
        gazeTarget = null
    }

    fun exec(): LiveStatus = step(exec = true)

    /** Run until the judge is done (max [maxSeconds]). */
    fun finish(maxSeconds: Double = 10.0) {
        val end = t + maxSeconds
        while (judge.phase != Phase.DONE && t < end) step()
    }
}
