package io.embodit.shouldercheck.domain

import io.embodit.shouldercheck.domain.MiniJson.bool
import io.embodit.shouldercheck.domain.MiniJson.list
import io.embodit.shouldercheck.domain.MiniJson.num
import io.embodit.shouldercheck.domain.MiniJson.obj
import io.embodit.shouldercheck.domain.MiniJson.str

/** App language (design `i18n`): first run follows the system. */
enum class Language {
    SYSTEM,
    ZH,
    EN,
}

/** Master volume steps (design `audio.playback`: three steps + mute, default middle). */
enum class VolumeLevel(val gain: Double) {
    LOW(0.5),
    MID(0.9),
    HIGH(1.0),
}

/** Settings (design `settings` S40). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val traffic: TrafficSide = TrafficSide.RIGHT_HAND,
    /** Comfort mode: your car does not move, the traffic moves around you (gate B Q2: default off). */
    val comfort: Boolean = false,
    val volume: VolumeLevel = VolumeLevel.MID,
    val muted: Boolean = false,
)

/** Best of one level. */
data class LevelBest(val best: Int = 0, val stars: Int = 0, val plays: Int = 0)

/** One finished game (the last 50 are kept). */
data class GameRecord(val atMs: Long, val level: Int, val daily: Boolean, val total: Int, val stars: Int, val clear: Int)

/**
 * Everything kept on the device (design `persistence`: SharedPreferences, one string; no account, no cloud): settings,
 * eye tracking asked, the adaptive shoulder thresholds, best score / stars / plays per level, the level unlocked, today's
 * run (date, today's best, streak) and the last 50 games.
 */
data class Progress(
    val settings: Settings = Settings(),
    val eyeAsked: Boolean = false,
    val thresholds: List<Double> = listOf(ShoulderBounds.HEAD_NEAR_DEFAULT, ShoulderBounds.HEAD_FAR_DEFAULT, ShoulderBounds.EYE_NEAR_DEFAULT, ShoulderBounds.EYE_FAR_DEFAULT),
    val levels: Map<Int, LevelBest> = emptyMap(),
    val unlocked: Int = 1,
    val dailyDate: Int = 0,
    val dailyBest: Int = 0,
    val dailyStreak: Int = 0,
    val gamesPlayed: Int = 0,
    val recent: List<GameRecord> = emptyList(),
) {
    fun best(level: Int): LevelBest = levels[level] ?: LevelBest()

    fun thresholdsObject(): Thresholds = Thresholds(thresholds[0], thresholds[1], thresholds[2], thresholds[3])

    fun withThresholds(t: Thresholds): Progress = copy(thresholds = listOf(t.headNear, t.headFar, t.eyeNear, t.eyeFar))

    /** Today's best for [today] (yyyymmdd): 0 when the stored day is another one. */
    fun dailyBestFor(today: Int): Int = if (dailyDate == today) dailyBest else 0

    /** Records a finished game; returns the new progress (best, unlock, daily, recent). */
    fun withGame(s: GameSummary, atMs: Long, today: Int): Progress {
        var p = copy(gamesPlayed = gamesPlayed + 1, recent = (recent + GameRecord(atMs, s.level, s.daily, s.total, s.stars, s.clear)).takeLast(MAX_RECENT))
        if (s.daily) {
            val streak = when (dailyDate) {
                today -> dailyStreak
                previousDay(today) -> dailyStreak + 1
                else -> 1
            }
            p = p.copy(dailyDate = today, dailyBest = maxOf(dailyBestFor(today), s.total), dailyStreak = streak)
        } else {
            val old = best(s.level)
            val lb = LevelBest(maxOf(old.best, s.total), maxOf(old.stars, s.stars), old.plays + 1)
            p = p.copy(levels = levels + (s.level to lb))
            if (s.unlockedNext && s.level >= unlocked && s.level < Levels.COUNT) p = p.copy(unlocked = s.level + 1)
        }
        return p
    }

    /** 清除成绩: scores, unlock, today's run and the thresholds go; settings and the eye question stay. */
    fun cleared(): Progress = Progress(settings = settings, eyeAsked = eyeAsked)

    companion object {
        const val MAX_RECENT = 50

        /** yyyymmdd of the day before [d] (handles month / year ends). */
        fun previousDay(d: Int): Int {
            val c = java.util.Calendar.getInstance()
            c.clear()
            c.set(d / 10000, (d / 100) % 100 - 1, d % 100)
            c.add(java.util.Calendar.DAY_OF_MONTH, -1)
            return c.get(java.util.Calendar.YEAR) * 10000 + (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
        }
    }
}

/** Progress ⇄ JSON string; [decode] never throws (bad / old data → defaults). */
object ProgressCodec {
    fun encode(p: Progress): String =
        MiniJson.write(
            linkedMapOf(
                "v" to 2,
                "settings" to linkedMapOf(
                    "language" to p.settings.language.name,
                    "traffic" to p.settings.traffic.name,
                    "comfort" to p.settings.comfort,
                    "volume" to p.settings.volume.name,
                    "muted" to p.settings.muted,
                ),
                "eyeAsked" to p.eyeAsked,
                "thresholds" to p.thresholds,
                "levels" to p.levels.entries.associate { (k, v) -> k.toString() to linkedMapOf("best" to v.best, "stars" to v.stars, "plays" to v.plays) },
                "unlocked" to p.unlocked,
                "dailyDate" to p.dailyDate,
                "dailyBest" to p.dailyBest,
                "dailyStreak" to p.dailyStreak,
                "games" to p.gamesPlayed,
                "recent" to p.recent.map { linkedMapOf("at" to it.atMs, "level" to it.level, "daily" to it.daily, "total" to it.total, "stars" to it.stars, "clear" to it.clear) },
            )
        )

    fun decode(text: String?): Progress {
        if (text.isNullOrBlank()) return Progress()
        return runCatching {
            val root = MiniJson.parse(text) as Map<*, *>
            if ((root.num("v") ?: 0.0) < 2.0) return Progress() // v0.1.x lesson progress: not carried over
            val s = root.obj("settings")
            val settings =
                Settings(
                    language = enumOr(s?.str("language"), Language.SYSTEM),
                    traffic = enumOr(s?.str("traffic"), TrafficSide.RIGHT_HAND),
                    comfort = s?.bool("comfort") ?: false,
                    volume = enumOr(s?.str("volume"), VolumeLevel.MID),
                    muted = s?.bool("muted") ?: false,
                )
            val th = root.list("thresholds")?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 } ?: Progress().thresholds
            val levels = root.obj("levels")?.entries?.mapNotNull { (k, v) ->
                val n = k.toString().toIntOrNull() ?: return@mapNotNull null
                val m = v as? Map<*, *> ?: return@mapNotNull null
                n to LevelBest(m.num("best")?.toInt() ?: 0, m.num("stars")?.toInt() ?: 0, m.num("plays")?.toInt() ?: 0)
            }?.toMap() ?: emptyMap()
            val recent = root.list("recent")?.mapNotNull { r ->
                val m = r as? Map<*, *> ?: return@mapNotNull null
                GameRecord(m.num("at")?.toLong() ?: 0L, m.num("level")?.toInt() ?: 1, m.bool("daily") ?: false, m.num("total")?.toInt() ?: 0, m.num("stars")?.toInt() ?: 0, m.num("clear")?.toInt() ?: 0)
            } ?: emptyList()
            Progress(
                settings = settings,
                eyeAsked = root.bool("eyeAsked") ?: false,
                thresholds = th,
                levels = levels,
                unlocked = (root.num("unlocked")?.toInt() ?: 1).coerceIn(1, Levels.COUNT),
                dailyDate = root.num("dailyDate")?.toInt() ?: 0,
                dailyBest = root.num("dailyBest")?.toInt() ?: 0,
                dailyStreak = root.num("dailyStreak")?.toInt() ?: 0,
                gamesPlayed = root.num("games")?.toInt() ?: 0,
                recent = recent.takeLast(Progress.MAX_RECENT),
            )
        }.getOrElse { Progress() }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = name?.let { n -> runCatching { enumValueOf<E>(n) }.getOrNull() } ?: fallback
}
