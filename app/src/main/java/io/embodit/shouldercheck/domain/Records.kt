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

/** Master volume steps (design: 低 / 中 / 高). */
enum class VolumeLevel(val gain: Double) {
    LOW(0.35),
    MID(0.65),
    HIGH(1.0),
}

/** Settings (design `settings`). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val traffic: TrafficSide = TrafficSide.RIGHT_HAND,
    val showGaze: Boolean = true,
    /** Step hints: on in lessons, off in random traffic by default. */
    val hintsLessons: Boolean = true,
    val hintsFree: Boolean = false,
    val volume: VolumeLevel = VolumeLevel.MID,
    val muted: Boolean = false,
)

/** One stored question (design `persistence`: situation, outcome, steps, shoulder angle, time, saw the hazard). */
data class QuestionRecord(
    val atMs: Long,
    val situation: Situation,
    val outcome: Outcome,
    val lesson: Int?,
    val mode: JudgeMode,
    val shoulderDeg: Double,
    val thresholdDeg: Double,
    val checkSec: Double?,
    val saw: SawHazard,
    val steps: Map<Step, Boolean>,
    val leftHandTraffic: Boolean = false,
)

/** Everything kept on the device (SharedPreferences, one string). No account, no cloud. */
data class Progress(
    val settings: Settings = Settings(),
    val range: TurnRange? = null,
    val aims: MirrorAims = MirrorAims(),
    val blindSpotLeft: Double? = null,
    val blindSpotRight: Double? = null,
    val demoSeen: Set<Int> = emptySet(),
    val passed: Set<Int> = emptySet(),
    val records: List<QuestionRecord> = emptyList(),
) {
    fun withRecord(r: QuestionRecord): Progress = copy(records = (records + r).takeLast(MAX_RECORDS))

    /** "清除成绩": records, lesson passes and the measured numbers go; settings stay. */
    fun cleared(): Progress = Progress(settings = settings)

    companion object {
        const val MAX_RECORDS = 300
    }
}

/**
 * Progress ⇄ String (JSON) for SharedPreferences. [decode] never throws: unknown / broken fields fall back to defaults,
 * so a damaged store only loses what is damaged.
 */
object ProgressCodec {
    const val VERSION = 1

    fun encode(p: Progress): String {
        val s = p.settings
        val root = LinkedHashMap<String, Any?>()
        root["v"] = VERSION
        root["settings"] =
            mapOf(
                "language" to s.language.name,
                "traffic" to s.traffic.name,
                "showGaze" to s.showGaze,
                "hintsLessons" to s.hintsLessons,
                "hintsFree" to s.hintsFree,
                "volume" to s.volume.name,
                "muted" to s.muted,
            )
        root["range"] = p.range?.let { mapOf("l" to it.maxLeft, "r" to it.maxRight, "skipped" to it.skipped) }
        root["aims"] = mapOf("l" to p.aims.left, "r" to p.aims.right)
        root["blindL"] = p.blindSpotLeft
        root["blindR"] = p.blindSpotRight
        root["demoSeen"] = p.demoSeen.sorted()
        root["passed"] = p.passed.sorted()
        root["records"] =
            p.records.map { r ->
                mapOf(
                    "at" to r.atMs,
                    "sit" to r.situation.id,
                    "out" to r.outcome.name,
                    "lesson" to r.lesson,
                    "mode" to r.mode.name,
                    "deg" to round1(r.shoulderDeg),
                    "th" to round1(r.thresholdDeg),
                    "check" to r.checkSec?.let { round2(it) },
                    "saw" to r.saw.name,
                    "steps" to r.steps.entries.associate { it.key.name to it.value },
                    "lht" to r.leftHandTraffic,
                )
            }
        return MiniJson.write(root)
    }

    fun decode(text: String?): Progress {
        if (text.isNullOrBlank()) return Progress()
        val root = runCatching { MiniJson.parse(text) as? Map<*, *> }.getOrNull() ?: return Progress()
        val d = Settings()
        val s = root.obj("settings")
        val settings =
            if (s == null) d
            else
                Settings(
                    language = enumOr(s.str("language"), d.language),
                    traffic = enumOr(s.str("traffic"), d.traffic),
                    showGaze = s.bool("showGaze") ?: d.showGaze,
                    hintsLessons = s.bool("hintsLessons") ?: d.hintsLessons,
                    hintsFree = s.bool("hintsFree") ?: d.hintsFree,
                    volume = enumOr(s.str("volume"), d.volume),
                    muted = s.bool("muted") ?: d.muted,
                )
        val range = root.obj("range")?.let { r ->
            val l = r.num("l")
            val rr = r.num("r")
            if (l == null || rr == null) null else TurnRange(l, rr, r.bool("skipped") ?: false)
        }
        val aims = root.obj("aims")?.let { MirrorAims(it.num("l") ?: MirrorSpec.AIM_DEFAULT, it.num("r") ?: MirrorSpec.AIM_DEFAULT) } ?: MirrorAims()
        val records =
            root.list("records")?.mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                runCatching {
                    QuestionRecord(
                        atMs = m.num("at")!!.toLong(),
                        situation = Situation.byId(m.str("sit")!!)!!,
                        outcome = Outcome.valueOf(m.str("out")!!),
                        lesson = m.num("lesson")?.toInt(),
                        mode = enumOr(m.str("mode"), JudgeMode.HEAD),
                        shoulderDeg = m.num("deg") ?: 0.0,
                        thresholdDeg = m.num("th") ?: 0.0,
                        checkSec = m.num("check"),
                        saw = enumOr(m.str("saw"), SawHazard.NO_HAZARD),
                        steps = (m.obj("steps") ?: emptyMap<String, Any?>()).entries.mapNotNull { (k, v) ->
                            val st = runCatching { Step.valueOf(k.toString()) }.getOrNull() ?: return@mapNotNull null
                            st to (v as? Boolean ?: false)
                        }.toMap(),
                        leftHandTraffic = m.bool("lht") ?: false,
                    )
                }.getOrNull()
            } ?: emptyList()
        return Progress(
            settings = settings,
            range = range,
            aims = aims,
            blindSpotLeft = root.num("blindL"),
            blindSpotRight = root.num("blindR"),
            demoSeen = ints(root.list("demoSeen")),
            passed = ints(root.list("passed")),
            records = records.takeLast(Progress.MAX_RECORDS),
        )
    }

    private fun ints(l: List<*>?): Set<Int> = l?.mapNotNull { (it as? Number)?.toInt() }?.toSet() ?: emptySet()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = name?.let { n -> runCatching { enumValueOf<E>(n) }.getOrNull() } ?: fallback

    private fun round1(d: Double) = Math.round(d * 10) / 10.0

    private fun round2(d: Double) = Math.round(d * 100) / 100.0
}
