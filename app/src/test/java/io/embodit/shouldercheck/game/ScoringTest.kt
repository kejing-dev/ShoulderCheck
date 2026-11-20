package io.embodit.shouldercheck.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** design `scoring`: outcomes, points, stars, combo, the 8-round game, unlock, today's run, persistence. */
class ScoringTest {
    private val side = TrafficSide.RIGHT_HAND

    private fun checked(r: Round, mirrorAt: Double, shoulderAt: Double, mode: JudgeMode = JudgeMode.HEAD): CheckJudge {
        val j = CheckJudge(r, mode, Thresholds())
        val mid = (j.band.start + j.band.endInclusive) / 2
        var t = 0.0
        while (t < shoulderAt + 0.3) {
            val yaw = when {
                t in mirrorAt..(mirrorAt + 0.3) -> mid
                t in shoulderAt..(shoulderAt + 0.25) -> r.dir.sign * 70.0
                else -> 0.0
            }
            j.update(CheckSample(t, yaw, 0.0, Vec3.ZERO, null))
            t += 1.0 / 90
        }
        return j
    }

    @Test
    fun safeWithReactionBonusAndStars() {
        val r = RoundGenerator.debugRound(Levels.of(1), Situation.LANE_LEFT, side, null, 0.0, 0.0, trap = false)
        val j = checked(r, 1.2, 1.8)
        val tGo = 2.6
        val res = Scoring.judge(r, 0, j, tGo, 0)
        assertEquals(Outcome.SAFE, res.outcome)
        // Δt from the end of the shoulder check (~2.05) → ~0.55 s → bonus ≈ 36
        assertTrue(res.reaction!! in 0.4..0.7)
        assertEquals(100 + Scoring.reactionBonus(res.reaction!!), res.points)
        assertEquals(3, res.stars)
        assertEquals(1, res.combo)
        val slow = Scoring.judge(r, 0, j, 4.6, 0)
        assertEquals(2, slow.stars)
        assertEquals(0, Scoring.reactionBonus(2.5))
        assertEquals(50, Scoring.reactionBonus(0.0))
    }

    @Test
    fun luckyMissTimeoutAndComboMultiplier() {
        val r = RoundGenerator.debugRound(Levels.of(1), Situation.LANE_LEFT, side, null, 0.0, 0.0, trap = false)
        val noShoulder = CheckJudge(r, JudgeMode.HEAD, Thresholds())
        val lucky = Scoring.judge(r, 0, noShoulder, 3.0, 4)
        assertEquals(Outcome.LUCKY, lucky.outcome)
        assertEquals(30, lucky.points)
        assertEquals(1, lucky.stars)
        assertEquals(0, lucky.combo)
        val trap = RoundGenerator.debugRound(Levels.of(1), Situation.LANE_LEFT, side, ActorKind.SEDAN, 3.6, 0.0, trap = true)
        val j = checked(trap, 1.1, 1.6)
        val miss = Scoring.judge(trap, 0, j, 2.0, 3)
        assertEquals(Outcome.MISS, miss.outcome)
        assertEquals("hazard", miss.conflictActor)
        assertEquals(0, miss.points)
        val timeout = Scoring.judge(r, 0, j, null, 3)
        assertEquals(Outcome.TIMEOUT, timeout.outcome)
        assertEquals(0, timeout.combo)
        assertEquals(1.0, Scoring.multiplier(2), 1e-9)
        assertEquals(1.5, Scoring.multiplier(3), 1e-9)
        assertEquals(2.0, Scoring.multiplier(5), 1e-9)
        val good = Scoring.judge(r, 0, checked(r, 1.2, 1.8), 2.4, 4)
        assertEquals(5, good.combo)
        assertEquals(2.0, good.multiplier, 1e-9)
    }

    @Test
    fun waitedNeedsDangerSeenThenAGap() {
        // a car comes up the left lane, passes; you look (the shoulder check sees it), wait, check again, go in the gap
        val r = RoundGenerator.debugRound(Levels.of(1), Situation.LANE_LEFT, side, ActorKind.SEDAN, 16.0, 5.0, trap = false)
        assertFalse(r.freeAt(GO_LIT))
        val gap = r.firstGap()!!
        val j = CheckJudge(r, JudgeMode.HEAD, Thresholds())
        val mid = (j.band.start + j.band.endInclusive) / 2
        var t = 0.0
        val tGo = gap.first + 0.4
        while (t < tGo) {
            val yaw = when {
                t in 1.2..1.6 -> mid
                t in 1.8..2.3 -> -75.0
                t in (tGo - 2.0)..(tGo - 1.6) -> mid
                t in (tGo - 1.2)..(tGo - 0.8) -> -70.0
                else -> 0.0
            }
            j.update(CheckSample(t, yaw, 0.0, Vec3.ZERO, null))
            t += 1.0 / 90
        }
        assertTrue("saw it while it was dangerous", j.sawDuringDanger)
        val res = Scoring.judge(r, 0, j, tGo, 0)
        assertEquals(Outcome.WAITED, res.outcome)
        assertTrue(res.points >= 120)
    }

    @Test
    fun eightRoundsGiveASummaryUnlockAndBest() {
        val s = Session(Levels.of(1), daily = false, seed = 1)
        val outcomes = listOf(Outcome.SAFE, Outcome.SAFE, Outcome.WAITED, Outcome.SAFE, Outcome.LUCKY, Outcome.SAFE, Outcome.SAFE, Outcome.MISS)
        var combo = 0
        for ((i, o) in outcomes.withIndex()) {
            combo = if (o.clear) combo + 1 else 0
            val stars = when (o) { Outcome.SAFE, Outcome.WAITED -> 3; Outcome.LUCKY -> 1; else -> 0 }
            s.record(RoundResult(i, Situation.LANE_LEFT, RoundKind.HAZARD, o, if (o.clear) 140 else if (o == Outcome.LUCKY) 30 else 0, stars, combo, Scoring.multiplier(combo), 0.5, 3.0, o.clear, o.clear, dir = if (i % 2 == 0) Dir.LEFT else Dir.RIGHT, shoulderTime = 0.8))
        }
        assertTrue(s.finished)
        val sum = s.summary(previousBest = 500)
        assertEquals(6, sum.clear)
        assertEquals(2, sum.stars) // (6×3 + 1) / 8 = 2.4 → 2
        assertTrue(sum.newBest)
        assertTrue(sum.unlockedNext)
        assertEquals(2, sum.misses)
        val p = Progress().withGame(sum, 0L, 20261119)
        assertEquals(2, p.unlocked)
        assertEquals(sum.total, p.best(1).best)
        assertEquals(1, p.best(1).plays)
    }

    @Test
    fun dailyBestAndStreak() {
        val sum = GameSummary(2, true, 900, 2, 6, 8, 0.8, null, 1, 900, true, false)
        var p = Progress().withGame(sum, 0, 20261118)
        assertEquals(900, p.dailyBestFor(20261118))
        assertEquals(1, p.dailyStreak)
        p = p.withGame(sum.copy(total = 700), 0, 20261119)
        assertEquals(700, p.dailyBestFor(20261119))
        assertEquals(0, p.dailyBestFor(20261120))
        assertEquals(2, p.dailyStreak)
        assertEquals(20261130, Progress.previousDay(20261201))
        assertEquals(20251231, Progress.previousDay(20260101))
        assertEquals(1, p.unlocked) // today's run never unlocks levels
    }

    @Test
    fun progressRoundTripAndBadData() {
        val p = Progress(settings = Settings(language = Language.EN, traffic = TrafficSide.LEFT_HAND, comfort = true, volume = VolumeLevel.HIGH, muted = true), eyeAsked = true, thresholds = listOf(60.0, 80.0, 75.0, 95.0), levels = mapOf(1 to LevelBest(1240, 3, 5)), unlocked = 3, dailyDate = 20261119, dailyBest = 999, dailyStreak = 4, gamesPlayed = 9, recent = listOf(GameRecord(5L, 1, false, 1240, 3, 7)))
        assertEquals(p, ProgressCodec.decode(ProgressCodec.encode(p)))
        assertEquals(Progress(), ProgressCodec.decode("{not json"))
        assertEquals(Progress(), ProgressCodec.decode(null))
        // v0.1.x progress (no "v": 2) starts fresh
        assertEquals(Progress(), ProgressCodec.decode("{\"settings\":{\"language\":\"EN\"}}"))
        val cleared = p.cleared()
        assertEquals(p.settings, cleared.settings)
        assertEquals(1, cleared.unlocked)
        assertTrue(cleared.levels.isEmpty())
    }
}
