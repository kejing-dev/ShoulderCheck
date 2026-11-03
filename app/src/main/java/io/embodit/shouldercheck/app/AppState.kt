package io.embodit.shouldercheck.app

import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Progress
import io.embodit.shouldercheck.domain.QuestionResult
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.SetSummary
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Step
import io.embodit.shouldercheck.domain.StepState
import io.embodit.shouldercheck.domain.TrafficSide

/**
 * Base states of the design (`states`, rev 4). Modifiers S17 (eye off), S18 (tracking lost), S20 (English) and S21
 * (left-hand traffic) are flags in [UiState]. S13 is the random-traffic entry: it sets up the set and goes on to S05.
 */
enum class Base(val code: String) {
    S00("S00"),
    S01("S01"),
    S01B("S01B"),
    S02("S02"),
    S03("S03"),
    S04("S04"),
    S05("S05"),
    S06("S06"),
    S07("S07"),
    S08("S08"),
    S09("S09"),
    S10("S10"),
    S11("S11"),
    S12("S12"),
    S13("S13"),
    S14("S14"),
    S15("S15"),
    S16("S16"),
    S19("S19"),
    ;

    /** S04–S09: a question (or its demo) is on; P2 shows, P1 hides; pause / lost apply. */
    val practising: Boolean
        get() = this in PRACTICE

    companion object {
        val PRACTICE = setOf(S04, S05, S06, S07, S08, S09)

        fun parse(s: String): Base? = values().firstOrNull { it.code.equals(s, ignoreCase = true) }
    }
}

/** Which P1 content shows (one at a time). */
enum class P1Page {
    LOADING,
    SIT,
    RANGE,
    HOME,
    LESSONS,
    RESULT,
    SCORECARD,
    SET_RESULT,
    SETTINGS,
    PAUSED,
    UNAVAILABLE,
}

/** One step block of P2. */
data class StepChip(val step: Step, val state: StepState, val byHead: Boolean, val progress: Double, val highlight: Boolean = false)

/** The action button at the end of P2 row 2. */
enum class HudAction {
    NONE,
    EXECUTE,
    CLOSE_DOOR,
    CONFIRM_LEFT,
    CONFIRM_RIGHT,
    SKIP_DEMO,
}

/** Short-lived HUD messages (row 1 of P2 for ~2.5 s). */
sealed interface HudMessage {
    data object Stale : HudMessage

    data class TurnMore(val dir: Dir, val deg: Int) : HudMessage

    data class Relaxed(val dir: Dir, val deg: Int) : HudMessage

    data class NearMiss(val dir: Dir, val kind: ActorKind?) : HudMessage

    data object Lost : HudMessage

    data class MirrorGone(val seconds: Int) : HudMessage
}

/** P2 / ANG content while a question (or demo / replay) runs. */
data class HudState(
    val situation: Situation? = null,
    val lesson: Int? = null,
    /** Random traffic: "3/10 · 难度 2". */
    val setIndex: Int? = null,
    val setLevel: Int? = null,
    val mode: JudgeMode? = null,
    val steps: List<StepChip> = emptyList(),
    val showSteps: Boolean = true,
    val action: HudAction = HudAction.NONE,
    val message: HudMessage? = null,
    val demoLine: Step? = null,
    val demoDone: Boolean = false,
    /** Lesson 4 with a small range: the rear-note sentence instead of a hazard (content.lessons[3].note). */
    val rearNoteDeg: Int? = null,
    /** Lesson 1: live blind-spot lengths. */
    val blindLeft: Double? = null,
    val blindRight: Double? = null,
    val replaySpeed: Float = 1f,
    val replayT: Double = 0.0,
)

/**
 * B-pillar readout content: S01B "左 72°" ([range] = true, [deg] live or recorded), practice "64° / 60°" ([threshold]).
 */
data class AngState(val deg: Int = 0, val threshold: Int? = null, val range: Boolean = false, val lit: Boolean = false, val visible: Boolean = false)

/** S01B progress. */
data class RangeUi(val side: Dir, val liveDeg: Int, val left: Int?, val right: Int?, val tooSmall: Boolean)

/** S10 extra context: where the question came from and what the buttons do. */
data class ResultUi(
    val result: QuestionResult,
    val lesson: Int?,
    val lessonEnd: Boolean,
    val lessonPassed: Boolean,
    val setEnd: Boolean,
    val trialNo: Int,
    val trialCount: Int,
    val rearNoteDeg: Int?,
    val blindLeft: Double?,
    val blindRight: Double?,
    val lessonCorrect: Int = 0,
)

data class UiState(
    val base: Base = Base.S00,
    val page: P1Page = P1Page.LOADING,
    val language: Language = Language.SYSTEM,
    val progress: Progress = Progress(),
    val traffic: TrafficSide = TrafficSide.RIGHT_HAND,
    val seat: Seat = Seat.DRIVER,
    /** S17: mirror looks judged by head direction; [eyeReason] = permission / unsupported / no-data / off. */
    val eyeOff: Boolean = true,
    val eyeReason: String? = null,
    /** S18. */
    val lost: Boolean = false,
    val hud: HudState = HudState(),
    val angLeft: AngState = AngState(),
    val angRight: AngState = AngState(),
    val range: RangeUi? = null,
    val result: ResultUi? = null,
    val setSummary: SetSummary? = null,
    val lessonFocus: Int = 0,
    val replayDemo: Boolean = false,
    val confirmExit: Boolean = false,
    val confirmClear: Boolean = false,
    val showAbout: Boolean = false,
    val muted: Boolean = false,
    val controllersTracked: Boolean = false,
    val pausedFrom: Base? = null,
    val firstRun: Boolean = true,
)
