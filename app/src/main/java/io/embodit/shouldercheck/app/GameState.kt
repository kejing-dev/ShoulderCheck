package io.embodit.shouldercheck.app

import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.GameSummary
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.Progress
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3

/**
 * Base states of the design (`states`, v0.2.0). S51 (eye off) and S52 (tracking lost) are flags in [UiState]
 * ([UiState.eyeOff], [UiState.lost]); English / left-hand traffic are settings.
 */
enum class Screen(val code: String) {
    S00("S00"), // loading
    S10("S10"), // round intro: the scenario card
    S11("S11"), // checking: 「走」 lit, timer ring running
    S12("S12"), // go: your car moves / the door opens
    S13("S13"), // clear (安全 / 等对了)
    S14("S14"), // lucky
    S15("S15"), // close call replay
    S16("S16"), // too late
    S20("S20"), // game over (main window)
    S21("S21"), // missed replay
    S30("S30"), // practice page
    S32("S32"), // records page
    S40("S40"), // settings
    S41("S41"), // how to play
    S42("S42"), // quit confirm
    S43("S43"), // clear confirm
    S50("S50"), // paused
    S53("S53"), // not in Full Space
    S54("S54"), // eye tracking ask (3D card)
    ;

    /** A round is on screen (the 3D HUD shows, the main window is closed). */
    val inRound: Boolean
        get() = this in ROUND

    /** The main window is wanted. */
    val windowed: Boolean
        get() = this in WINDOW

    companion object {
        val ROUND = setOf(S10, S11, S12, S13, S14, S15, S16, S21, S54)
        val WINDOW = setOf(S20, S30, S32, S40, S41, S42, S43, S50, S53)

        fun parse(s: String): Screen? = values().firstOrNull { it.code.equals(s, ignoreCase = true) }
    }
}

/** Main-window page (TabBar: practice · records · settings). */
enum class Page(val id: String) {
    PLAY("play"),
    RECORDS("records"),
    SETTINGS("settings"),
}

/** What the result pop says (3D, over the hood). */
data class ResultPop(val outcome: Outcome, val points: Int, val stars: Int, val combo: Int, val mirrorOk: Boolean, val shoulderOk: Boolean, val reaction: Double?, val trapSide: Dir?, val seenInMirror: Boolean, val door: Boolean, val sinceMs: Long)

/** The 3D HUD of a round (design `hud3d`), rebuilt each frame by the controller. */
data class HudState(
    val situation: Situation? = null,
    val dir: Dir = Dir.LEFT,
    /** Card: big over the hood (first second) or shrunk above the button. */
    val cardBig: Boolean = false,
    val cardVisible: Boolean = false,
    val firstHint: Boolean = false,
    val eyeOffLine: Boolean = false,
    /** 「走」 button: lit (checking), its label, timer fraction left (1 → 0). */
    val goVisible: Boolean = false,
    val goLit: Boolean = false,
    val goLabel: GoLabel = GoLabel.GO,
    val timerLeft: Double = 1.0,
    val goShakeMs: Long = 0L,
    val lookForwardUntilMs: Long = 0L,
    val chipsVisible: Boolean = false,
    val mirrorLit: Boolean = false,
    val shoulderLit: Boolean = false,
    val combo: Int = 0,
    val score: Int = 0,
    val round: Int = 0,
    val pop: ResultPop? = null,
    val replay: Boolean = false,
    val replayIndex: Int = 0,
    val replayCount: Int = 0,
    val pauseVisible: Boolean = false,
    /** Between games with the window closed: the chip reads 「菜单」 and opens the window. */
    val menuChip: Boolean = false,
    val askVisible: Boolean = false,
    val mergeMetres: Int? = null,
    val summary: GameSummary? = null,
)

enum class GoLabel { GO, CONTINUE, SKIP }

data class UiState(
    val screen: Screen = Screen.S00,
    val page: Page = Page.PLAY,
    val language: Language = Language.SYSTEM,
    val progress: Progress = Progress(),
    val traffic: TrafficSide = TrafficSide.RIGHT_HAND,
    /** S51: mirror looks judged by head direction. */
    val eyeOff: Boolean = true,
    val eyeReason: String? = null,
    /** S52. */
    val lost: Boolean = false,
    val hud: HudState = HudState(),
    /** Practice page: the level on show (◀ ▶). */
    val practiceLevel: Int = 1,
    val summary: GameSummary? = null,
    val missedCount: Int = 0,
    val pausedDaily: Boolean = false,
    val confirm: Confirm? = null,
    val muted: Boolean = false,
    val firstVisit: Boolean = true,
    val inGame: Boolean = false,
    val daily: Boolean = false,
    val level: Int = 1,
    val eyeGranted: Boolean = false,
)

/** A confirmation the window shows in place (D44: entries that lose progress ask first; D26 quit). */
enum class Confirm { QUIT, CLEAR, RESTART, LEAVE }

/** Head in the car frame. */
data class HeadIn(val yaw: Double, val pitch: Double, val pos: Vec3)

/** One controller this frame, car frame. */
data class CtrlIn(val hand: Hand, val pos: Vec3?, val trigger: Boolean, val aim: Vec3?)

/** Everything the frame system read this frame, converted to the car frame. */
data class FrameInput(
    val nowMs: Long,
    val dt: Double,
    val head: HeadIn?,
    val headLost: Boolean,
    val blockReason: String?,
    val gaze: io.embodit.shouldercheck.domain.GazeRay?,
    val eyeReason: String?,
    val controllers: List<CtrlIn>,
    val aPresses: Int,
    val bPresses: Int,
    val yPresses: Int,
    /** Raw head pitch (degrees, + up) for the window level gate. */
    val headPitchWorld: Double?,
)

/** Judge mode helper for logs. */
fun JudgeMode.short(): String = if (this == JudgeMode.EYE) "eye" else "head"
