package io.embodit.shouldercheck.domain

import kotlin.math.max
import kotlin.math.roundToInt

/** Round outcomes (design `scoring.outcomes`). */
enum class Outcome(val code: String) {
    /** In a gap, mirror and shoulder fresh. */
    SAFE("safe"),

    /** Same, and you saw a hazard and waited for it to pass first. */
    WAITED("waited"),

    /** In a gap, but the mirror or the shoulder check was missing or stale. */
    LUCKY("lucky"),

    /** Not in a gap: horn / bell, slow-motion replay. */
    MISS("miss"),

    /** The timer ran out. */
    TIMEOUT("timeout"),
    ;

    val clear: Boolean
        get() = this == SAFE || this == WAITED

    companion object {
        fun parse(s: String): Outcome? = values().firstOrNull { it.code.equals(s, ignoreCase = true) || it.name.equals(s, ignoreCase = true) }
    }
}

/** One finished round. */
data class RoundResult(
    val index: Int,
    val situation: Situation,
    val kind: RoundKind,
    val outcome: Outcome,
    val points: Int,
    val stars: Int,
    /** Combo after this round (0 = broken). */
    val combo: Int,
    val multiplier: Double,
    /** Reaction seconds counted for the bonus (null: no bonus). */
    val reaction: Double?,
    val tGo: Double?,
    val mirrorOk: Boolean,
    val shoulderOk: Boolean,
    val conflictActor: String? = null,
    val conflictTau: Double? = null,
    val trap: Boolean = false,
    val dir: Dir = Dir.LEFT,
    /** Time from the button lighting to the shoulder check (s), for 「平均回头」. */
    val shoulderTime: Double? = null,
    val mode: JudgeMode = JudgeMode.HEAD,
    val shoulderPeak: Double = 0.0,
    val threshold: Double = 0.0,
    val saw: Boolean = false,
)

object Scoring {
    const val SAFE_POINTS = 100
    const val WAITED_POINTS = 120
    const val LUCKY_POINTS = 30
    const val REACTION_MAX = 50.0
    const val REACTION_PER_SEC = 25.0
    const val FAST = 1.5

    /** Reaction bonus = max(0, 50 − 25 Δt) (design `scoring.reaction`). */
    fun reactionBonus(dt: Double): Int = max(0.0, REACTION_MAX - REACTION_PER_SEC * max(0.0, dt)).roundToInt()

    /** Combo multiplier for a combo (after this round) of [combo]: ≥ 5 ×2, ≥ 3 ×1.5. */
    fun multiplier(combo: Int): Double = if (combo >= 5) 2.0 else if (combo >= 3) 1.5 else 1.0

    /**
     * Judges a 「走」 at [tGo] (null = timed out) against [round] with the round's [checks]; [comboBefore] = combo going in.
     */
    fun judge(round: Round, index: Int, checks: CheckJudge, tGo: Double?, comboBefore: Int): RoundResult {
        val dir = round.dir
        val shoulderTime = checks.shoulderFirst?.let { max(0.0, it - GO_LIT) }
        if (tGo == null) {
            return RoundResult(index, round.situation, round.kind, Outcome.TIMEOUT, 0, 0, 0, 1.0, null, null, checks.mirrorFresh(round.window), checks.shoulderFresh(round.window), trap = round.kind == RoundKind.TRAP, dir = dir, shoulderTime = shoulderTime, mode = checks.mode, shoulderPeak = checks.shoulderPeak, threshold = checks.threshold, saw = checks.saw)
        }
        val conflict = round.conflictAt(tGo)
        val mirrorOk = checks.mirrorFresh(tGo)
        val shoulderOk = checks.shoulderFresh(tGo)
        if (conflict != null) {
            return RoundResult(index, round.situation, round.kind, Outcome.MISS, 0, 0, 0, 1.0, null, tGo, mirrorOk, shoulderOk, conflict.actorId, conflict.tau, round.kind == RoundKind.TRAP, dir, shoulderTime, checks.mode, checks.shoulderPeak, checks.threshold, checks.saw)
        }
        if (!mirrorOk || !shoulderOk) {
            return RoundResult(index, round.situation, round.kind, Outcome.LUCKY, LUCKY_POINTS, 1, 0, 1.0, null, tGo, mirrorOk, shoulderOk, trap = round.kind == RoundKind.TRAP, dir = dir, shoulderTime = shoulderTime, mode = checks.mode, shoulderPeak = checks.shoulderPeak, threshold = checks.threshold, saw = checks.saw)
        }
        val waited = round.dangerBefore(tGo) && checks.sawDuringDanger
        val gapStart = round.gapAround(tGo)?.first ?: tGo
        val from = listOfNotNull(gapStart, GO_LIT, checks.lastCheckEnd()).max()
        val reactionSec = max(0.0, tGo - from)
        val bonus = reactionBonus(reactionSec)
        val base = if (waited) WAITED_POINTS else SAFE_POINTS
        val combo = comboBefore + 1
        val mult = multiplier(combo)
        val points = ((base + bonus) * mult).roundToInt()
        val stars = if (reactionSec <= FAST) 3 else 2
        return RoundResult(index, round.situation, round.kind, if (waited) Outcome.WAITED else Outcome.SAFE, points, stars, combo, mult, reactionSec, tGo, true, true, trap = round.kind == RoundKind.TRAP, dir = dir, shoulderTime = shoulderTime, mode = checks.mode, shoulderPeak = checks.shoulderPeak, threshold = checks.threshold, saw = checks.saw)
    }
}

/** The end-of-game numbers (design `panels.P1.pages.S20`). */
data class GameSummary(
    val level: Int,
    val daily: Boolean,
    val total: Int,
    val stars: Int,
    val clear: Int,
    val rounds: Int,
    val avgShoulder: Double?,
    /** Side you missed most (shoulder not fresh on a non-clear round), null = none. */
    val missedMost: Dir?,
    val misses: Int,
    val best: Int,
    val newBest: Boolean,
    val unlockedNext: Boolean,
)

/** A game in progress: 8 results, the combo, the total. */
class Session(val level: Level, val daily: Boolean, val seed: Long) {
    val results = ArrayList<RoundResult>()

    val combo: Int
        get() = results.lastOrNull()?.combo ?: 0

    val total: Int
        get() = results.sumOf { it.points }

    val index: Int
        get() = results.size

    val finished: Boolean
        get() = results.size >= GamePlan.ROUNDS

    fun record(r: RoundResult) {
        if (!finished) results += r
    }

    /** Average stars rounded (max 3). */
    val stars: Int
        get() = if (results.isEmpty()) 0 else (results.sumOf { it.stars }.toDouble() / results.size).roundToInt().coerceIn(0, 3)

    /** Rounds to replay at the end (close calls and lucky ones). */
    val missed: List<RoundResult>
        get() = results.filter { it.outcome == Outcome.MISS || it.outcome == Outcome.LUCKY }

    fun summary(previousBest: Int): GameSummary {
        val clear = results.count { it.outcome.clear }
        val sh = results.mapNotNull { it.shoulderTime }
        val avg = if (sh.isEmpty()) null else sh.average()
        val missedSides = results.filter { !it.outcome.clear && !it.shoulderOk }.groupingBy { it.dir }.eachCount()
        val missedMost = missedSides.maxByOrNull { it.value }?.key
        val t = total
        return GameSummary(level.n, daily, t, stars, clear, results.size, avg, missedMost, missed.size, max(previousBest, t), t > previousBest, !daily && stars >= UNLOCK_STARS)
    }

    companion object {
        const val UNLOCK_STARS = 2
    }
}
