package io.embodit.shouldercheck.domain

import kotlin.math.abs
import kotlin.math.max

/** How mirror looks and shoulder checks are judged in one round (design `judging.mode`). */
enum class JudgeMode {
    /** Eye tracking usable: shoulder = gaze yaw (head + eyes), mirror = gaze dwell on the glass. */
    EYE,

    /** No eye data: both from the head direction (S51 small line under the card). */
    HEAD,
}

/** Gaze ray in the car frame. */
data class GazeRay(val origin: Vec3, val dir: Vec3)

/** One frame of head / gaze, car frame (degrees: yaw + right, pitch + up). */
data class CheckSample(val t: Double, val headYaw: Double, val headPitch: Double, val headPos: Vec3, val gaze: GazeRay?)

/**
 * The adaptive shoulder thresholds (design `judging.shoulder` / `judging.adaptive`), one per judge mode and side
 * (near = your door's side, far = the passenger side). Defaults head 65° / 80°, eyes 80° / 95°; never below the same-side
 * mirror + 10° (head 60° / 77°, eyes 70° / 87°) and never above default + 10°.
 *
 * Only an honest near miss lowers it: the peak was within 10° under T AND the hazard was in the headset view past the
 * pillars at that moment; two of those on the same side → T − 5°. Five turns in a row more than 15° past T → T + 5°.
 * Turning half-way on purpose therefore never brings it down.
 */
class Thresholds(
    var headNear: Double = ShoulderBounds.HEAD_NEAR_DEFAULT,
    var headFar: Double = ShoulderBounds.HEAD_FAR_DEFAULT,
    var eyeNear: Double = ShoulderBounds.EYE_NEAR_DEFAULT,
    var eyeFar: Double = ShoulderBounds.EYE_FAR_DEFAULT,
) {
    private val nearMisses = IntArray(4)
    private val easy = IntArray(4)

    private fun idx(eye: Boolean, far: Boolean) = (if (eye) 2 else 0) + (if (far) 1 else 0)

    fun of(eye: Boolean, far: Boolean): Double = when (idx(eye, far)) {
        0 -> headNear
        1 -> headFar
        2 -> eyeNear
        else -> eyeFar
    }

    private fun set(eye: Boolean, far: Boolean, v: Double) {
        val c = v.coerceIn(ShoulderBounds.low(eye, far), ShoulderBounds.high(eye, far))
        when (idx(eye, far)) {
            0 -> headNear = c
            1 -> headFar = c
            2 -> eyeNear = c
            else -> eyeFar = c
        }
    }

    /** A finished turn toward one side: returns the change made (−5, 0, +5). */
    fun onTurn(eye: Boolean, far: Boolean, peak: Double, hazardVisible: Boolean): Int {
        val i = idx(eye, far)
        val t = of(eye, far)
        if (peak >= t + EASY_MARGIN) {
            nearMisses[i] = 0
            easy[i]++
            if (easy[i] >= EASY_COUNT) {
                easy[i] = 0
                val before = t
                set(eye, far, t + STEP)
                return if (of(eye, far) > before) STEP.toInt() else 0
            }
            return 0
        }
        easy[i] = 0
        if (peak < t && peak >= t - NEAR_MISS && hazardVisible) {
            nearMisses[i]++
            if (nearMisses[i] >= NEAR_MISS_COUNT) {
                nearMisses[i] = 0
                val before = t
                set(eye, far, t - STEP)
                return if (of(eye, far) < before) -STEP.toInt() else 0
            }
        }
        return 0
    }

    fun copy(): Thresholds = Thresholds(headNear, headFar, eyeNear, eyeFar)

    companion object {
        const val NEAR_MISS = 10.0
        const val NEAR_MISS_COUNT = 2
        const val EASY_MARGIN = 15.0
        const val EASY_COUNT = 5
        const val STEP = 5.0
    }
}

/** Something a check judge noticed this frame (sounds, chips, logs). */
sealed interface CheckEvent {
    val t: Double

    /** The mirror check completed (a new look, not the same one continuing). */
    data class MirrorLit(override val t: Double, val byHead: Boolean) : CheckEvent

    data class ShoulderLit(override val t: Double, val byHead: Boolean, val yaw: Double) : CheckEvent

    /** A turn toward the target side ended; [change] = threshold change (−5 / 0 / +5). */
    data class TurnEnded(override val t: Double, val peak: Double, val threshold: Double, val change: Int, val nearMiss: Boolean) : CheckEvent

    data class HazardSeen(override val t: Double, val via: String) : CheckEvent
}

/**
 * The checks of one round (design `judging`): target-side mirror (eyes: gaze on the glass grown 50 % ≥ 120 ms; head:
 * yaw inside the mirror's band — 20° forward, 8° back of its azimuth — ≥ 250 ms) and shoulder (gaze or head yaw toward
 * the side ≥ T for ≥ 150 ms). A check is fresh for 4 s (mirror) / 3 s (shoulder) after it last held. Also: did you see
 * the hazard (eyes on its box + 0.5 m ≥ 100 ms, in the headset view past the pillars ≥ 150 ms, or in the mirror while you
 * looked at it), the turns for the adaptive threshold, and a 10 Hz trace for the replay trail.
 *
 * Feed [update] every frame with the same time base as the round; read [events] after each call.
 */
class CheckJudge(val round: Round, val mode: JudgeMode, val thresholds: Thresholds) {
    val cabin = round.cabin
    val dir = round.dir
    val mirror = round.mirror
    val far = dir != cabin.doorSide
    private val aim = MirrorAims().of(mirror.id)

    /** Shoulder threshold of this round (head or eye value, fixed when the round starts). */
    val threshold: Double = thresholds.of(mode == JudgeMode.EYE, far)

    /** Head threshold (used on frames without gaze in eye mode). */
    private val headThreshold: Double = thresholds.of(false, far)

    /** Mirror band of head yaw (degrees): 20° toward the front, 8° toward the back of the mirror's azimuth. */
    val band: ClosedFloatingPointRange<Double> = run {
        val y = mirror.yawDeg
        val s = if (y < 0) -1.0 else 1.0
        val front = y - s * BAND_FRONT
        val back = y + s * BAND_BACK
        minOf(front, back)..maxOf(front, back)
    }

    var mirrorFirst: Double? = null
        private set
    var mirrorLast: Double? = null
        private set
    var mirrorByHead = false
        private set
    var shoulderFirst: Double? = null
        private set
    var shoulderLast: Double? = null
        private set
    var shoulderByHead = false
        private set
    var shoulderPeak = 0.0
        private set
    var saw = false
        private set
    var sawDuringDanger = false
        private set

    private var lastT: Double? = null
    private var mirrorRun = 0.0
    private var mirrorHolding = false
    private var shoulderRun = 0.0
    private var shoulderHolding = false
    private var sawEye = 0.0
    private var sawView = 0.0
    private var excursionPeak = 0.0
    private var inExcursion = false
    private var excursionVisible = false
    private var lastTrace = -1.0

    /** 10 Hz trace: (t, gaze or head yaw, pitch, head yaw) for the replay trail. */
    val trace = ArrayList<TraceSample>()

    val events = ArrayList<CheckEvent>()

    fun mirrorFresh(t: Double): Boolean = mirrorLast?.let { t - it <= MIRROR_FRESH } ?: false

    fun shoulderFresh(t: Double): Boolean = shoulderLast?.let { t - it <= SHOULDER_FRESH } ?: false

    /** The later of the two checks' end times (for the reaction score), null if neither was done. */
    fun lastCheckEnd(): Double? = listOfNotNull(mirrorLast, shoulderLast).maxOrNull()

    fun update(s: CheckSample) {
        events.clear()
        val dt = lastT?.let { (s.t - it).coerceIn(0.0, 0.1) } ?: 0.0
        lastT = s.t
        val gaze = if (mode == JudgeMode.EYE) s.gaze else null
        updateMirror(s, gaze, dt)
        updateShoulder(s, gaze, dt)
        updateSaw(s, gaze, dt)
        if (s.t - lastTrace >= 0.1 - 1e-9) {
            lastTrace = s.t
            val g = gaze?.dir
            trace += TraceSample(s.t, g?.let { Angles.yawOf(it) } ?: s.headYaw, g?.let { Angles.pitchOf(it) } ?: s.headPitch, s.headYaw)
        }
    }

    private fun updateMirror(s: CheckSample, gaze: GazeRay?, dt: Double) {
        val byHead = gaze == null
        val on = if (gaze != null) gazeHitsMirror(gaze, mirror) else s.headYaw in band
        val need = if (byHead) MIRROR_HEAD_DWELL else MIRROR_EYE_DWELL
        if (on) {
            mirrorRun += dt
            if (mirrorRun >= need - 1e-9) {
                if (!mirrorHolding) {
                    mirrorHolding = true
                    if (mirrorFirst == null) mirrorFirst = s.t
                    mirrorByHead = byHead
                    events += CheckEvent.MirrorLit(s.t, byHead)
                }
                mirrorLast = s.t
            }
        } else {
            mirrorRun = 0.0
            mirrorHolding = false
        }
    }

    private fun updateShoulder(s: CheckSample, gaze: GazeRay?, dt: Double) {
        val yaw = gaze?.let { Angles.yawOf(it.dir) } ?: s.headYaw
        val t = if (gaze != null) threshold else headThreshold
        val toward = yaw * dir.sign
        val headToward = s.headYaw * dir.sign
        shoulderPeak = max(shoulderPeak, toward)
        if (toward >= t) {
            shoulderRun += dt
            if (shoulderRun >= SHOULDER_DWELL - 1e-9) {
                if (!shoulderHolding) {
                    shoulderHolding = true
                    if (shoulderFirst == null) shoulderFirst = s.t
                    shoulderByHead = gaze == null
                    events += CheckEvent.ShoulderLit(s.t, gaze == null, yaw)
                }
                shoulderLast = s.t
            }
        } else {
            shoulderRun = 0.0
            shoulderHolding = false
        }
        // turns toward the target side, for the adaptive threshold (judged on the same yaw as the check)
        if (!inExcursion && headToward > EXCURSION_START) {
            inExcursion = true
            excursionPeak = toward
            excursionVisible = hazardVisibleAt(s, yaw)
        } else if (inExcursion) {
            if (toward > excursionPeak) {
                excursionPeak = toward
                excursionVisible = hazardVisibleAt(s, yaw)
            }
            if (headToward < EXCURSION_END) {
                inExcursion = false
                val before = thresholds.of(gaze != null, far)
                val nearMiss = excursionPeak < before && excursionPeak >= before - Thresholds.NEAR_MISS && excursionVisible
                val change = thresholds.onTurn(gaze != null, far, excursionPeak, excursionVisible)
                events += CheckEvent.TurnEnded(s.t, excursionPeak, before, change, nearMiss)
            }
        }
    }

    private fun hazardVisibleAt(s: CheckSample, yaw: Double): Boolean {
        val h = round.hazard ?: return false
        return Visibility.anyInView(h.box(s.t), cabin, yaw, Visibility.JUDGE_HALF_FOV, eye = s.headPos)
    }

    private var sawFrame = 0

    private fun updateSaw(s: CheckSample, gaze: GazeRay?, dt: Double) {
        val h = round.hazard ?: return
        // the visibility walk is ~45 line tests: every other frame is plenty
        sawFrame++
        if (sawFrame % 2 == 1 && dt < 0.05) return
        val box = h.box(s.t)
        var via: String? = null
        if (gaze != null && box.grown(SAW_GROW).hitByRay(gaze.origin, gaze.dir)) {
            sawEye += dt * 2
            if (sawEye >= SAW_EYE) via = "eye"
        } else sawEye = 0.0
        if (Visibility.anyInView(box, cabin, s.headYaw, Visibility.JUDGE_HALF_FOV, s.headPitch, s.headPos)) {
            sawView += dt * 2
            if (sawView >= SAW_VIEW) via = via ?: "view"
        } else sawView = 0.0
        if (via == null && mirrorHolding && MirrorModel.sees(box, mirror, cabin, aim, s.headPos)) via = "mirror"
        if (via != null) {
            if (!saw) events += CheckEvent.HazardSeen(s.t, via)
            saw = true
            if (!round.freeAt(s.t)) sawDuringDanger = true
        }
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
        return abs(p.dot(hx)) <= ex && abs(p.dot(vy)) <= ey
    }

    companion object {
        const val BAND_FRONT = 20.0
        const val BAND_BACK = 8.0
        const val MIRROR_EYE_DWELL = 0.12
        const val MIRROR_HEAD_DWELL = 0.25
        const val SHOULDER_DWELL = 0.15
        const val MIRROR_FRESH = 4.0
        const val SHOULDER_FRESH = 3.0
        const val TARGET_EXPAND = 0.5
        const val SAW_GROW = 0.5
        const val SAW_EYE = 0.10
        const val SAW_VIEW = 0.15
        const val EXCURSION_START = 25.0
        const val EXCURSION_END = 20.0

        /** 「走」 only when the head is back within ±35° of straight ahead (design `judging.lookForward`). */
        const val LOOK_FORWARD = 35.0

        fun lookingForward(headYaw: Double): Boolean = abs(headYaw) <= LOOK_FORWARD
    }
}

/** One 10 Hz trace sample: gaze (or head) direction and the head yaw. */
data class TraceSample(val t: Double, val yaw: Double, val pitch: Double, val headYaw: Double)
