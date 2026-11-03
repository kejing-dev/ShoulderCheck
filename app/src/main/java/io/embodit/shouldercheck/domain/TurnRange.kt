package io.embodit.shouldercheck.domain

import kotlin.math.max
import kotlin.math.min

/**
 * The turn range measured in S01B (design `judging.turnRange`): head yaw relative to the car front, upper body
 * included, degrees (positive numbers for both sides).
 */
data class TurnRange(val maxLeft: Double, val maxRight: Double, val skipped: Boolean = false) {
    fun max(dir: Dir): Double = if (dir == Dir.LEFT) maxLeft else maxRight

    /** Shoulder-check threshold T_side for [dir] before any near-miss relaxing. */
    fun threshold(dir: Dir): Double = if (skipped) SKIP_THRESHOLD else thresholdFor(max(dir))

    /** "量到 < 50° 时提示「连上身转过去，再量一次」". */
    fun needsRemeasure(dir: Dir): Boolean = !skipped && max(dir) < REMEASURE_BELOW

    companion object {
        const val ABS_MIN = 40.0
        const val CLAMP_MIN = 55.0
        const val CLAMP_MAX = 80.0
        const val REMEASURE_BELOW = 50.0
        const val SKIP_MAX = 80.0

        /** Design: skipping the measurement counts as 80° each side with T = 60° (explicit, not 0.8 × 80). */
        const val SKIP_THRESHOLD = 60.0

        val SKIPPED = TurnRange(SKIP_MAX, SKIP_MAX, skipped = true)

        /** T_side = min(clamp(0.8 × max, 55°, 80°), max − 5°), never below 40° (U16). */
        fun thresholdFor(max: Double): Double = max(ABS_MIN, min((0.8 * max).coerceIn(CLAMP_MIN, CLAMP_MAX), max - 5.0))
    }
}

/** Result of one head excursion toward a side (from leaving the centre to coming back). */
sealed interface Excursion {
    val dir: Dir
    val peak: Double

    /** Reached the threshold. */
    data class Reached(override val dir: Dir, override val peak: Double) : Excursion

    /** "差一点": peak within 8° below T; [missing] = degrees still needed ("再往左转 8°"). */
    data class NearMiss(override val dir: Dir, override val peak: Double, val missing: Double) : Excursion

    /** Second near miss in a row: T lowered by 5° (not below 40°). */
    data class Relaxed(override val dir: Dir, override val peak: Double, val newThreshold: Double) : Excursion

    /** Too small to be an attempt (a mirror glance, …). */
    data class Small(override val dir: Dir, override val peak: Double) : Excursion
}

/**
 * The session's live shoulder thresholds: the measured range plus the near-miss relaxing (design B27 "宽容先行": two
 * near misses in a row → T − 5°, not below 40°). Shared across questions, so a relaxed threshold carries on.
 */
class TurnProfile(val range: TurnRange) {
    private val threshold = mutableMapOf(Dir.LEFT to range.threshold(Dir.LEFT), Dir.RIGHT to range.threshold(Dir.RIGHT))
    private val nearMisses = mutableMapOf(Dir.LEFT to 0, Dir.RIGHT to 0)

    fun threshold(dir: Dir): Double = threshold.getValue(dir)

    /** Feed the peak of a finished excursion; returns what it counted as. */
    fun onExcursion(dir: Dir, peak: Double): Excursion {
        val t = threshold(dir)
        if (peak >= t) {
            nearMisses[dir] = 0
            return Excursion.Reached(dir, peak)
        }
        if (peak >= t - NEAR_MISS_BAND) {
            val n = nearMisses.getValue(dir) + 1
            if (n >= 2) {
                nearMisses[dir] = 0
                val relaxed = max(TurnRange.ABS_MIN, t - RELAX_STEP)
                threshold[dir] = relaxed
                return Excursion.Relaxed(dir, peak, relaxed)
            }
            nearMisses[dir] = n
            return Excursion.NearMiss(dir, peak, t - peak)
        }
        return Excursion.Small(dir, peak)
    }

    companion object {
        const val NEAR_MISS_BAND = 8.0
        const val RELAX_STEP = 5.0
    }
}
