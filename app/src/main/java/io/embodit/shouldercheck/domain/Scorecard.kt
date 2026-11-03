package io.embodit.shouldercheck.domain

object Stats {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}

enum class PracticeStatus {
    /** "稳": correct rate of the last 10 ≥ 90 %. */
    STEADY,

    /** "练". */
    PRACTICE,

    /** "还没练": no attempt yet. */
    NOT_YET,
}

/** One scorecard row (design `content.scorecard`): one practice situation, its last [WINDOW] counted attempts. */
data class ScoreRow(
    val situation: Situation,
    val attempts: Int,
    val correct: Int,
    val correctRate: Double,
    val medianCheckTime: Double?,
    val medianShoulder: Double?,
    val nearMisses: Int,
    val status: PracticeStatus,
)

/** Top of the scorecard: the numbers about you. */
data class ScoreHeader(val blindSpotLeft: Double?, val blindSpotRight: Double?, val rangeLeft: Double?, val rangeRight: Double?)

/**
 * Scorecard statistics: per practice situation, the last 10 attempts (skipped ones do not count); correct = clear or
 * well waited; steady = correct rate ≥ 90 %. Check time is shown, never a pass condition (waiting must not be
 * punished).
 */
object Scorecard {
    const val WINDOW = 10
    const val STEADY_RATE = 0.9

    fun row(situation: Situation, records: List<QuestionRecord>): ScoreRow {
        val last = records.filter { it.situation == situation && it.outcome != Outcome.SKIPPED }.sortedBy { it.atMs }.takeLast(WINDOW)
        val correct = last.count { it.outcome.correct }
        val rate = if (last.isEmpty()) 0.0 else correct.toDouble() / last.size
        val status =
            when {
                last.isEmpty() -> PracticeStatus.NOT_YET
                rate >= STEADY_RATE - 1e-9 -> PracticeStatus.STEADY
                else -> PracticeStatus.PRACTICE
            }
        return ScoreRow(
            situation = situation,
            attempts = last.size,
            correct = correct,
            correctRate = rate,
            medianCheckTime = Stats.median(last.mapNotNull { it.checkSec }),
            medianShoulder = Stats.median(last.map { it.shoulderDeg }),
            nearMisses = last.count { it.outcome == Outcome.NEAR_MISS },
            status = status,
        )
    }

    fun rows(records: List<QuestionRecord>): List<ScoreRow> = Situation.PRACTICE.map { row(it, records) }

    /** "总体" row over all practice situations (last 10 per situation pooled). */
    fun overall(records: List<QuestionRecord>): ScoreRow {
        val rows = rows(records)
        val attempts = rows.sumOf { it.attempts }
        val correct = rows.sumOf { it.correct }
        val pooled = Situation.PRACTICE.flatMap { s -> records.filter { it.situation == s && it.outcome != Outcome.SKIPPED }.sortedBy { it.atMs }.takeLast(WINDOW) }
        val rate = if (attempts == 0) 0.0 else correct.toDouble() / attempts
        return ScoreRow(
            situation = Situation.LANE_LEFT,
            attempts = attempts,
            correct = correct,
            correctRate = rate,
            medianCheckTime = Stats.median(pooled.mapNotNull { it.checkSec }),
            medianShoulder = Stats.median(pooled.map { it.shoulderDeg }),
            nearMisses = rows.sumOf { it.nearMisses },
            status = if (attempts == 0) PracticeStatus.NOT_YET else if (rate >= STEADY_RATE - 1e-9) PracticeStatus.STEADY else PracticeStatus.PRACTICE,
        )
    }

    fun header(p: Progress): ScoreHeader = ScoreHeader(p.blindSpotLeft, p.blindSpotRight, p.range?.maxLeft, p.range?.maxRight)

    /** "推荐下一课" (S02): the first lesson not passed yet, else the weakest practice situation's lesson. */
    fun recommendedLesson(p: Progress): Int {
        Lessons.ALL.firstOrNull { it.n !in p.passed }?.let { return it.n }
        val weakest = rows(p.records).minByOrNull { if (it.status == PracticeStatus.NOT_YET) -1.0 else it.correctRate }
        return weakest?.situation?.lesson ?: 1
    }
}
