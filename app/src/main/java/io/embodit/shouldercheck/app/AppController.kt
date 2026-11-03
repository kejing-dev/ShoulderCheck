package io.embodit.shouldercheck.app

import android.content.Context
import io.embodit.shouldercheck.domain.ActionKind
import io.embodit.shouldercheck.domain.Actor
import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.ActorRole
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.BlindSpot
import io.embodit.shouldercheck.domain.Box
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.DoorModel
import io.embodit.shouldercheck.domain.Excursion
import io.embodit.shouldercheck.domain.FreeModeSet
import io.embodit.shouldercheck.domain.GazeRay
import io.embodit.shouldercheck.domain.GhostPose
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.HazardSpec
import io.embodit.shouldercheck.domain.JudgeEvent
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.JudgeSample
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Layout
import io.embodit.shouldercheck.domain.Lessons
import io.embodit.shouldercheck.domain.MirrorAims
import io.embodit.shouldercheck.domain.MirrorSpec
import io.embodit.shouldercheck.domain.NoHazardReason
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.Phase
import io.embodit.shouldercheck.domain.Progress
import io.embodit.shouldercheck.domain.QuestionJudge
import io.embodit.shouldercheck.domain.QuestionResult
import io.embodit.shouldercheck.domain.Scenario
import io.embodit.shouldercheck.domain.Scenes
import io.embodit.shouldercheck.domain.Scorecard
import io.embodit.shouldercheck.domain.Seat
import io.embodit.shouldercheck.domain.Settings
import io.embodit.shouldercheck.domain.Signal
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Step
import io.embodit.shouldercheck.domain.StepState
import io.embodit.shouldercheck.domain.TrafficGenerator
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.TurnProfile
import io.embodit.shouldercheck.domain.TurnRange
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.domain.VolumeLevel
import io.embodit.shouldercheck.platform.Audio
import io.embodit.shouldercheck.platform.EyePermissionActivity
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.platform.Store
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every user action on P1 / P2 (and the debug hooks that stand in for them). */
sealed interface Ev {
    data object Sit : Ev
    data object SkipRange : Ev
    data class RangeMeasured(val left: Double, val right: Double) : Ev
    data class OpenLessons(val focus: Int = 0) : Ev
    data object OpenRandom : Ev
    data object OpenScores : Ev
    data object OpenSettings : Ev
    data object Back : Ev
    data class ChooseLesson(val n: Int) : Ev
    data object ToggleReplayDemo : Ev
    data object Pause : Ev
    data object Resume : Ev
    data object SkipQuestion : Ev
    data object RestartQuestion : Ev
    data object Home : Ev
    data object Next : Ev
    data object Retry : Ev
    data object Replay : Ev
    data object EndReplay : Ev
    data class ReplaySpeed(val speed: Double) : Ev
    data object ToggleMute : Ev
    data class SetLanguage(val language: Language) : Ev
    data class SetTraffic(val side: TrafficSide) : Ev
    data class SetShowGaze(val on: Boolean) : Ev
    data class SetHintsLessons(val on: Boolean) : Ev
    data class SetHintsFree(val on: Boolean) : Ev
    data class SetVolume(val level: VolumeLevel) : Ev
    data object Remeasure : Ev
    data object ReadjustMirrors : Ev
    data object Recentre : Ev
    data object ClearScores : Ev
    data object ConfirmClear : Ev
    data object CancelConfirm : Ev
    data object ToggleAbout : Ev
    data object Exit : Ev
    data object ConfirmExit : Ev
    data object Execute : Ev
    data object CloseDoor : Ev
    data object Confirm : Ev
    data object SkipDemo : Ev
    data object RetryLesson : Ev
    data object LessonList : Ev
    data object SetAgain : Ev
    data object SetResult : Ev
    data object EnableEyes : Ev
    data object StartRecommended : Ev
}

/**
 * All behaviour of the app (design `states`, `interactions`, `content.lessonFlow`, `persistence`): one process-wide
 * controller on the main thread. The ECS frame system calls [onFrame] with the tracked input converted to the car frame;
 * panels and debug hooks call [onEvent]. Debug hooks only change the INPUT (head / gaze / door / signal overrides,
 * injected presses) or send the same [Ev] a panel would; `goto` reaches a state through the normal entry path.
 */
object AppController {
    private lateinit var app: Context
    private lateinit var store: Store
    var audio: Audio? = null
        private set
    private var started = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    var progress = Progress()
        private set
    private var profile = TurnProfile(TurnRange.SKIPPED)

    // ------------------------------------------------------------------ what the scene reads
    /** Changes when the car must be (re)seated at the current head pose (S01 坐好了, 回正). */
    var placeEpoch = 0
        private set

    /** True once 坐好了 was pressed (before that the scene keeps the car in front of the head). */
    var seated = false
        private set

    /** Changes when street and cabin must be rebuilt (traffic side). */
    var sceneEpoch = 0
        private set
    var built = false
        private set
    var run: QuestionRun? = null
        private set
    var course: Course? = null
        private set
    var replay: ReplayState? = null
        private set
    var demo: DemoState? = null
        private set
    var ambient: Scenario = Ambient.scenario(TrafficSide.RIGHT_HAND)
        private set
    private var ambientT = 0.0
    var alertUntilMs = 0L
        private set
    var alertStartMs = 0L
        private set
    val hoodColour: Int = Layout.HOOD_COLOURS[(System.nanoTime() % Layout.HOOD_COLOURS.size).toInt().let { if (it < 0) -it else it }]

    /** Effective head / gaze this frame (debug overrides applied), car frame: gaze dot, arcs. */
    var effHead: HeadIn? = null
        private set
    var effGaze: GazeRay? = null
        private set

    // ------------------------------------------------------------------ debug input overrides (same paths as the trackers)
    var dbgHeadYaw: Double? = null
    var dbgHeadPitch: Double = 0.0
    var dbgGaze: Pair<Double, Double>? = null
    var dbgDoor: Pair<Hand, Double>? = null
    var dbgPendingA = 0
    var dbgPendingB = 0
    var dbgStick: Pair<Hand, Float>? = null
    var dbgHold = false
    var speed = 1.0
    var testTex = false
    var mirrorHz = 20.0

    /** Debug `lineup front|side`: the five models on start markers (ui-audit model views); null = off. */
    var dbgLineup: String? = null

    // ------------------------------------------------------------------ private state
    private var stateSinceMs = 0L
    private var nowMs = 0L
    private var settingsFrom: Base = Base.S02
    private var recentring = false
    private var blockedSinceMs = -1L
    private var autoUnavailable = false
    private var backgroundScenario: Scenario? = null
    private var lastResult: Pair<QuestionResult, QuestionJudge>? = null
    private var messageUntilMs = 0L
    private var eyeOkLast = false
    private var eyeDropSinceMs = -1L
    private var setSeed = 0L

    // S01B
    private var rangeSide = Dir.LEFT
    private var rangeLeft: Double? = null
    private var rangeRight: Double? = null
    private var rangeHoldStartMs = -1L
    private var rangeHoldYaw = 0.0
    private var rangePeak = 0.0
    private var rangeSmallTries = 0
    private var rangeTooSmall = false

    // ------------------------------------------------------------------ lifecycle

    fun start(context: Context) {
        if (started) {
            // the Stage was disposed (audio released) and composed again: new player, same progress
            if (audio == null) {
                audio = Audio(app)
                applyGain()
            }
            return
        }
        started = true
        app = context.applicationContext
        store = Store(app)
        progress = store.load()
        profile = TurnProfile(progress.range ?: TurnRange.SKIPPED)
        audio = Audio(app)
        applyGain()
        ambient = Ambient.scenario(progress.settings.traffic)
        update { it.copy(progress = progress, language = progress.settings.language, traffic = progress.settings.traffic, muted = progress.settings.muted, firstRun = progress.range == null) }
        ScLog.i("APP start records=${progress.records.size} range=${progress.range} traffic=${progress.settings.traffic} lang=${progress.settings.language}")
        EyePermissionActivity.onResult = { ok -> ScLog.i("EYE permission result=$ok") }
    }

    /** The scene finished building road, cabin and street (S00 → S01 once the head is valid). */
    fun onBuilt() {
        built = true
        ScLog.i("SCENE built")
    }

    fun onBackground() {
        ScLog.i("LIFECYCLE background base=${state.value.base.code}")
        audio?.stopAll()
        val r = run
        if (r != null && (state.value.base.practising || state.value.lost || state.value.base == Base.S16)) backgroundScenario = r.scenario
    }

    fun onForeground() {
        ScLog.i("LIFECYCLE foreground")
        val sc = backgroundScenario ?: return
        backgroundScenario = null
        // design container.lifecycle: back to S05 of the same question, nothing judged
        startQuestion(sc, why = "foreground")
    }

    /** D26: release everything; the screen finishes the task. */
    fun release() {
        audio?.release()
        audio = null
        ScLog.i("APP released")
    }

    var onQuit: (() -> Unit)? = null

    // ------------------------------------------------------------------ helpers

    private inline fun update(f: (UiState) -> UiState) {
        _state.value = f(_state.value)
    }

    private fun go(base: Base, why: String) {
        val old = state.value.base
        stateSinceMs = nowMs
        val page =
            when (base) {
                Base.S00 -> P1Page.LOADING
                Base.S01 -> P1Page.SIT
                Base.S01B -> P1Page.RANGE
                Base.S02 -> P1Page.HOME
                Base.S03 -> P1Page.LESSONS
                Base.S10 -> P1Page.RESULT
                Base.S12 -> P1Page.SCORECARD
                Base.S14 -> P1Page.SET_RESULT
                Base.S15 -> P1Page.SETTINGS
                Base.S16 -> P1Page.PAUSED
                Base.S19 -> P1Page.UNAVAILABLE
                else -> state.value.page
            }
        update { it.copy(base = base, page = page, confirmExit = false, confirmClear = false, seat = seatNow()) }
        val s = state.value
        val mods = buildList {
            if (s.eyeOff) add("S17")
            if (s.lost) add("S18")
            if (isEnglish()) add("S20")
            if (s.traffic == TrafficSide.LEFT_HAND) add("S21")
        }
        ScLog.i("STATE ${base.code}${if (mods.isEmpty()) "" else "+" + mods.joinToString("+")} from=${old.code} why=$why")
        audio?.bed(base.practising || base == Base.S11)
        if (base != Base.S06) audio?.indicator(false)
    }

    private fun isEnglish(): Boolean =
        when (progress.settings.language) {
            Language.EN -> true
            Language.ZH -> false
            Language.SYSTEM -> app.resources.getString(io.embodit.shouldercheck.R.string.lang_code) != "zh"
        }

    private fun seatNow(): Seat = run?.scenario?.seat ?: replay?.scenario?.seat ?: demo?.scenario?.seat ?: Seat.DRIVER

    private fun saveProgress(p: Progress) {
        progress = p
        store.save(p)
        update { it.copy(progress = p, language = p.settings.language, traffic = p.settings.traffic, muted = p.settings.muted) }
    }

    private fun settings(f: (Settings) -> Settings) {
        saveProgress(progress.copy(settings = f(progress.settings)))
        applyGain()
    }

    private fun applyGain() {
        val s = progress.settings
        audio?.setGain(if (s.muted) 0f else s.volume.gain.toFloat())
    }

    private fun range(): TurnRange = progress.range ?: TurnRange.SKIPPED

    private fun hintsOn(): Boolean = if (course is Course.FreeCourse) progress.settings.hintsFree else progress.settings.hintsLessons

    // ------------------------------------------------------------------ events

    fun onEvent(e: Ev) {
        ScLog.i("EVENT $e base=${state.value.base.code}")
        val base = state.value.base
        when (e) {
            Ev.Sit -> if (base == Base.S01) sit()
            Ev.SkipRange -> if (base == Base.S01B) finishRange(TurnRange.SKIPPED, "skip")
            is Ev.RangeMeasured -> finishRange(TurnRange(e.left, e.right), "measured")
            is Ev.OpenLessons -> {
                update { it.copy(lessonFocus = e.focus) }
                go(Base.S03, "lessons")
            }
            Ev.OpenRandom -> startSet(System.currentTimeMillis() % 100000, 1)
            Ev.OpenScores -> go(Base.S12, "scores")
            Ev.OpenSettings -> {
                if (base != Base.S15) settingsFrom = base
                update { it.copy(showAbout = false) }
                go(Base.S15, "settings")
            }
            Ev.Back ->
                when (base) {
                    Base.S15 -> go(settingsFrom, "back")
                    Base.S03, Base.S12, Base.S14 -> go(Base.S02, "back")
                    else -> Unit
                }
            is Ev.ChooseLesson -> startLesson(e.n)
            Ev.StartRecommended -> startLesson(Scorecard.recommendedLesson(progress))
            Ev.ToggleReplayDemo -> update { it.copy(replayDemo = !it.replayDemo) }
            Ev.Pause -> if (base.practising || state.value.lost) pause()
            Ev.Resume -> if (base == Base.S16) resume()
            Ev.SkipQuestion -> if (base == Base.S16) skipQuestion()
            Ev.RestartQuestion -> if (base == Base.S16) currentScenario()?.let { startQuestion(it, "restart") }
            Ev.Home -> goHome("home")
            Ev.Next -> if (base == Base.S10) next()
            Ev.Retry -> if (base == Base.S10) run?.scenario?.let { startQuestion(it, "retry") }
            Ev.Replay -> if (base == Base.S10) startReplay(null)
            Ev.EndReplay -> if (base == Base.S11) {
                replay = null
                go(Base.S10, "replay end")
            }
            is Ev.ReplaySpeed -> replay?.let {
                it.speed = e.speed
                updateHud()
            }
            Ev.ToggleMute -> settings { it.copy(muted = !it.muted) }
            is Ev.SetLanguage -> settings { it.copy(language = e.language) }.also { go(base, "language") }
            is Ev.SetTraffic -> if (e.side != progress.settings.traffic) {
                settings { it.copy(traffic = e.side) }
                ambient = Ambient.scenario(e.side)
                sceneEpoch++
                course = null
                run = null
                replay = null
                demo = null
                ScLog.i("SCENE regenerate traffic=${e.side}")
                go(Base.S02, "traffic side")
            }
            is Ev.SetShowGaze -> settings { it.copy(showGaze = e.on) }
            is Ev.SetHintsLessons -> settings { it.copy(hintsLessons = e.on) }
            is Ev.SetHintsFree -> settings { it.copy(hintsFree = e.on) }
            is Ev.SetVolume -> settings { it.copy(volume = e.level, muted = false) }
            Ev.Remeasure -> enterRange("remeasure")
            Ev.ReadjustMirrors -> startLesson(1, skipDemo = true)
            Ev.Recentre -> {
                recentring = true
                go(Base.S01, "recentre")
            }
            Ev.ClearScores -> update { it.copy(confirmClear = true) }
            Ev.ConfirmClear -> {
                saveProgress(progress.cleared())
                profile = TurnProfile(range())
                update { it.copy(confirmClear = false, firstRun = true) }
                ScLog.i("SCORES cleared")
            }
            Ev.CancelConfirm -> update { it.copy(confirmClear = false, confirmExit = false) }
            Ev.ToggleAbout -> update { it.copy(showAbout = !it.showAbout) }
            Ev.Exit -> update { it.copy(confirmExit = true) }
            Ev.ConfirmExit -> quit()
            Ev.Execute -> run?.let { if (base == Base.S06) it.execPending = true }
            Ev.CloseDoor -> run?.let { closeDoor(it) }
            Ev.Confirm -> run?.let { if (base == Base.S06) confirmMirror(it) }
            Ev.SkipDemo -> if (base == Base.S04) endDemo("skip")
            Ev.RetryLesson -> (course as? Course.LessonCourse)?.let { startLesson(it.lesson.n, skipDemo = true) }
            Ev.LessonList -> go(Base.S03, "lesson list")
            Ev.SetAgain -> startSet(System.currentTimeMillis() % 100000, 1)
            Ev.SetResult -> if (base == Base.S10 && (course as? Course.FreeCourse)?.set?.finished == true) go(Base.S14, "set done")
            Ev.EnableEyes -> {
                store.eyeAsked = true
                EyePermissionActivity.open(app)
            }
        }
    }

    fun quit() {
        ScLog.i("QUIT confirmed")
        audio?.stopAll()
        onQuit?.invoke()
    }

    // ------------------------------------------------------------------ S01 / S01B

    private fun sit() {
        placeEpoch++
        seated = true
        ScLog.i("SEAT sit epoch=$placeEpoch recentre=$recentring")
        if (!store.eyeAsked && !EyePermissionActivity.granted(app)) {
            store.eyeAsked = true
            EyePermissionActivity.open(app)
        }
        if (recentring || progress.range != null) {
            recentring = false
            go(Base.S02, "seated")
        } else {
            enterRange("first run")
        }
    }

    private fun enterRange(why: String) {
        rangeSide = Dir.LEFT
        rangeLeft = null
        rangeRight = null
        rangeHoldStartMs = -1L
        rangePeak = 0.0
        rangeSmallTries = 0
        rangeTooSmall = false
        update { it.copy(range = RangeUi(Dir.LEFT, 0, null, null, false)) }
        go(Base.S01B, why)
    }

    private fun finishRange(r: TurnRange, why: String) {
        saveProgress(progress.copy(range = r))
        profile = TurnProfile(r)
        ScLog.i("RANGE done L=${r.maxLeft} R=${r.maxRight} skipped=${r.skipped} T_L=${r.threshold(Dir.LEFT)} T_R=${r.threshold(Dir.RIGHT)} why=$why")
        update { it.copy(range = null, firstRun = false) }
        go(Base.S02, "range $why")
    }

    private fun stepRange(head: HeadIn?) {
        val h = head ?: return
        val toward = h.yaw * rangeSide.sign
        rangePeak = max(rangePeak, toward)
        // "转到最远处停 1 s": the head stays within 3° for 1 s while turned > 20° toward the side
        if (toward > 20.0) {
            if (rangeHoldStartMs < 0 || abs(h.yaw - rangeHoldYaw) > 3.0) {
                rangeHoldStartMs = nowMs
                rangeHoldYaw = h.yaw
            } else if (nowMs - rangeHoldStartMs >= 1000L) {
                recordRange(toward)
            }
        } else {
            rangeHoldStartMs = -1L
        }
        updateRangeUi(toward)
    }

    private fun recordRange(deg: Double) {
        audio?.cue("turn_tick")
        val value = deg
        if (value < TurnRange.REMEASURE_BELOW && rangeSmallTries < 1) {
            rangeSmallTries++
            rangeTooSmall = true
            rangeHoldStartMs = -1L
            ScLog.i("RANGE ${rangeSide} small=${value.roundToInt()} -> remeasure")
            return
        }
        ScLog.i("RANGE record ${rangeSide} ${value.roundToInt()}")
        rangeTooSmall = false
        rangeSmallTries = 0
        rangeHoldStartMs = -1L
        if (rangeSide == Dir.LEFT) {
            rangeLeft = value
            rangeSide = Dir.RIGHT
            rangePeak = 0.0
        } else {
            rangeRight = value
            finishRange(TurnRange(rangeLeft ?: value, value), "measured")
        }
    }

    private fun updateRangeUi(toward: Double) {
        val live = max(0.0, toward).roundToInt()
        update { it.copy(range = RangeUi(rangeSide, live, rangeLeft?.roundToInt(), rangeRight?.roundToInt(), rangeTooSmall)) }
    }

    // ------------------------------------------------------------------ lessons / sets / questions

    private fun startLesson(n: Int, skipDemo: Boolean = false) {
        val lesson = Lessons.byN(n)
        val trials = TrafficGenerator.lessonTrials(n, System.currentTimeMillis(), progress.settings.traffic, range(), progress.aims)
        course = Course.LessonCourse(lesson, trials)
        ScLog.i("LESSON start n=$n trials=${trials.size} hazards=${trials.map { it.hazard?.kind }} noHazard=${trials.map { it.noHazardReason }}")
        val wantDemo = !skipDemo && (n !in progress.demoSeen || state.value.replayDemo)
        if (wantDemo) startDemo(lesson.n, trials.first()) else startQuestion(trials.first(), "lesson $n")
    }

    private fun startDemo(n: Int, scenario: Scenario, holdStep: Int? = null) {
        val lesson = Lessons.byN(n)
        run = null
        replay = null
        demo = DemoState(lesson, scenario, scenario.requiredSteps).also { it.holdStep = holdStep }
        update { it.copy(replayDemo = false) }
        updateHud()
        go(Base.S04, "demo lesson $n")
    }

    private fun endDemo(why: String) {
        val d = demo ?: return
        demo = null
        saveProgress(progress.copy(demoSeen = progress.demoSeen + d.lesson.n))
        val c = course as? Course.LessonCourse
        startQuestion(c?.trials?.getOrNull(c.index) ?: d.scenario, "demo $why")
    }

    private fun startSet(seed: Long, q: Int) {
        setSeed = seed
        val set = FreeModeSet(seed, progress.settings.traffic, range(), progress.aims, startIndex = (q - 1).coerceIn(0, FreeModeSet.SIZE - 1))
        course = Course.FreeCourse(set)
        update { it.copy(setSummary = null) }
        go(Base.S13, "random seed=$seed q=$q")
        startQuestion(set.current(), "set q=${set.index + 1}")
    }

    fun startDebugScene(situation: Situation, hazard: HazardSpec?, why: String): QuestionRun {
        val s = Scenes.build(situation, progress.settings.traffic, hazard)
        course = Course.DebugCourse(s)
        startQuestion(s, why)
        return run!!
    }

    /** S05: scene in place, judge mode locked (design judging.mode). */
    fun startQuestion(scenario: Scenario, why: String) {
        demo = null
        replay = null
        val eyeUsable = dbgGaze != null || eyeOkLast
        val mode = if (eyeUsable) JudgeMode.EYE else JudgeMode.HEAD
        val aims = progress.aims
        val judge = QuestionJudge(scenario, mode, profile, aims)
        val r = QuestionRun(scenario, judge)
        r.stateSinceMs = nowMs
        if (scenario.situation == Situation.BLIND_SPOT) r.mirrorExit = Scenes.mirrorExitTime(scenario, aims)
        run = r
        dbgDoor = null
        ScLog.i(
            String.format(
                Locale.US,
                "QUESTION start sit=%s seat=%s mode=%s hazard=%s seed=%d level=%s limit=%s noHazard=%s entry=%s pass=%s why=%s",
                scenario.situation.id, scenario.seat, mode, scenario.hazard?.let { "${it.kind}@x=%.2f".format(Locale.US, it.x) }, scenario.seed,
                scenario.difficulty, scenario.timeLimit, scenario.noHazardReason, scenario.hazardEntryTime, scenario.hazardPassTime, why,
            )
        )
        // a sticky message of the previous run (Lost / NearMiss, held with messageUntilMs = MAX) must not carry over
        messageUntilMs = 0L
        update { it.copy(lost = false, eyeOff = mode == JudgeMode.HEAD, hud = it.hud.copy(message = null)) }
        updateHud()
        go(Base.S05, why)
    }

    private fun currentScenario(): Scenario? = run?.scenario ?: demo?.scenario

    private fun pause() {
        val from = state.value.base
        update { it.copy(pausedFrom = from) }
        audio?.indicator(false)
        go(Base.S16, "pause")
    }

    private fun resume() {
        val from = state.value.pausedFrom ?: Base.S05
        update { it.copy(pausedFrom = null) }
        if (state.value.lost) {
            update { it.copy(lost = false) }
            currentScenario()?.let { startQuestion(it, "resume after lost") }
            return
        }
        if (from == Base.S04 && demo != null) go(Base.S04, "resume") else if (run != null) go(from, "resume") else goHome("resume")
    }

    private fun goHome(why: String) {
        run = null
        demo = null
        replay = null
        course = null
        messageUntilMs = 0L
        update { it.copy(lost = false, pausedFrom = null, hud = it.hud.copy(message = null)) }
        go(Base.S02, why)
    }

    private fun skipQuestion() {
        val r = run
        val result =
            if (r != null) r.judge.skip(max(0.0, r.t))
            else {
                val sc = currentScenario() ?: return goHome("skip none")
                QuestionJudge(sc, JudgeMode.HEAD, profile, progress.aims).skip(0.0)
            }
        ScLog.i("QUESTION skipped")
        if (r == null) {
            demo?.let { saveProgress(progress.copy(demoSeen = progress.demoSeen + it.lesson.n)) }
            demo = null
        }
        record(result, r?.judge)
        val c = course
        when (c) {
            is Course.LessonCourse -> {
                c.outcomes[c.index] = Outcome.SKIPPED
                if (c.last) showResult(result, r?.judge ?: QuestionJudge(currentScenario()!!, JudgeMode.HEAD, profile), "skip last") else {
                    c.index++
                    startQuestion(c.trials[c.index], "skip")
                }
            }
            is Course.FreeCourse -> {
                c.set.record(result)
                if (c.set.finished) {
                    update { it.copy(setSummary = c.set.summary()) }
                    go(Base.S14, "skip last")
                } else startQuestion(c.set.current(), "skip")
            }
            else -> goHome("skip")
        }
    }

    private fun next() {
        when (val c = course) {
            is Course.LessonCourse -> if (!c.last) {
                c.index++
                startQuestion(c.trials[c.index], "next")
            } else go(Base.S03, "lesson done")
            is Course.FreeCourse -> if (c.set.finished) {
                update { it.copy(setSummary = c.set.summary()) }
                go(Base.S14, "set done")
            } else startQuestion(c.set.current(), "next")
            is Course.DebugCourse -> startQuestion(c.scenario, "next debug")
            null -> goHome("next")
        }
    }

    /** Persists one result (design persistence: the last 300 records). */
    private fun record(result: QuestionResult, judge: QuestionJudge?) {
        var p = progress.withRecord(result.record(System.currentTimeMillis()))
        if (result.situation == Situation.MIRRORS && result.outcome != Outcome.SKIPPED && judge != null) {
            val cabin = io.embodit.shouldercheck.domain.Cabin(Seat.DRIVER, result.side)
            val aims = judge.aims
            val l = BlindSpot.length(Dir.LEFT, aims.left, cabin)
            val r = BlindSpot.length(Dir.RIGHT, aims.right, cabin)
            p = p.copy(aims = aims, blindSpotLeft = l, blindSpotRight = r)
            ScLog.i(String.format(Locale.US, "MIRRORS saved aimL=%.0f aimR=%.0f blindL=%.2f blindR=%.2f", aims.left, aims.right, l, r))
        }
        saveProgress(p)
        ScLog.i("RESULT ${result.situation.id} outcome=${result.outcome} mode=${result.mode} peak=${result.shoulderPeak.roundToInt()} th=${result.threshold.roundToInt()} check=${result.checkTime} saw=${result.saw} via=${result.seenVia} steps=${result.steps.map { "${it.step}:${if (it.done) "ok" else "x"}" }}")
    }

    /** Question finished → S08 (1 s), S09 or straight to S10 (time out). */
    private fun onFinished(r: QuestionRun, outcome: Outcome) {
        val result = r.judge.result()
        r.result = result
        r.stateSinceMs = nowMs
        when (outcome) {
            Outcome.NEAR_MISS -> {
                r.frozenAt = r.t
                r.conflictActor = result.conflictActor
                alertStartMs = nowMs
                alertUntilMs = nowMs + 1200
                nearMissSounds(r)
                val hazard = r.scenario.actors.firstOrNull { it.id == result.conflictActor }
                update { it.copy(hud = it.hud.copy(message = HudMessage.NearMiss(Dir.ofSign(hazard?.x?.let { x -> x - r.scenario.road.egoX } ?: -1.0), hazard?.kind))) }
                messageUntilMs = Long.MAX_VALUE
                go(Base.S09, "conflict ${result.conflictActor}")
            }
            Outcome.TIMEOUT -> showResult(result, r.judge, "timeout")
            else -> {
                if (outcome.correct) audio?.cue("success")
                go(Base.S08, "finished $outcome")
            }
        }
    }

    private fun nearMissSounds(r: QuestionRun) {
        val a = r.scenario.actors.firstOrNull { it.id == r.conflictActor } ?: r.scenario.hazard
        val head = effHead
        val pan =
            if (a == null) 0f
            else {
                val d = a.box(r.actorTime).centre - (head?.pos ?: Vec3.ZERO)
                val rel = Angles.wrap(Angles.yawOf(d) - (head?.yaw ?: 0.0))
                kotlin.math.sin(Math.toRadians(rel)).toFloat()
            }
        val au = audio ?: return
        au.cue("near_miss", pan)
        when (a?.kind) {
            ActorKind.BIKE -> au.cue("bike_bell", pan)
            ActorKind.EBIKE -> {
                au.cue("ebike_brake", pan)
                au.cue("bike_bell", pan, 0.8f)
            }
            null -> Unit
            else -> {
                au.cue("horn", pan)
                au.cue("screech", pan, 0.8f)
            }
        }
    }

    private fun showResult(result: QuestionResult, judge: QuestionJudge, why: String) {
        lastResult = result to judge
        messageUntilMs = 0L
        update { it.copy(hud = it.hud.copy(message = null)) }
        if (result.outcome != Outcome.SKIPPED) record(result, judge)
        var lessonEnd = false
        var passed = false
        var setEnd = false
        var trialNo = 1
        var trialCount = 1
        var correct = 0
        when (val c = course) {
            is Course.LessonCourse -> {
                if (result.outcome != Outcome.SKIPPED) c.outcomes[c.index] = result.outcome
                trialNo = c.index + 1
                trialCount = c.trials.size
                if (c.last) {
                    lessonEnd = true
                    passed = c.lesson.passed(c.outcomes.filterNotNull())
                    correct = c.outcomes.count { it?.correct == true }
                    if (passed) saveProgress(progress.copy(passed = progress.passed + c.lesson.n))
                    ScLog.i("LESSON ${c.lesson.n} end outcomes=${c.outcomes.toList()} passed=$passed")
                }
            }
            is Course.FreeCourse -> {
                if (result.outcome != Outcome.SKIPPED) c.set.record(result)
                trialNo = c.set.index
                trialCount = FreeModeSet.SIZE
                setEnd = c.set.finished
                if (setEnd) update { it.copy(setSummary = c.set.summary()) }
            }
            else -> Unit
        }
        val sc = run?.scenario
        val rear = if (sc?.noHazardReason == NoHazardReason.RANGE) range().max(sc.dir).roundToInt() else null
        update {
            it.copy(
                result = ResultUi(result, result.lesson, lessonEnd, passed, setEnd, trialNo, trialCount, rear, progress.blindSpotLeft, progress.blindSpotRight, correct),
            )
        }
        go(Base.S10, why)
    }

    private fun startReplay(t: Double?) {
        val (result, judge) = lastResult ?: return
        val sc = run?.scenario ?: return
        replay = ReplayState(sc, judge, result).also { if (t != null) it.t = t.coerceIn(0.0, it.end) }
        updateHud()
        go(Base.S11, "replay")
    }

    // ------------------------------------------------------------------ input actions

    private fun closeDoor(r: QuestionRun) {
        if (r.scenario.situation.action != ActionKind.DOOR) return
        r.judge.closeDoor()
        r.grabHand = null
        r.grabPoint = null
        r.push = 0.0
        dbgDoor = null
        audio?.cue("door_close")
        ScLog.i("DOOR close")
    }

    private fun confirmMirror(r: QuestionRun) {
        if (r.scenario.situation != Situation.MIRRORS) return
        val near = Dir.LEFT.physical(r.scenario.side)
        val dir = if (near !in r.confirmed) near else near.flip()
        if (dir in r.confirmed) return
        r.confirmed += dir
        r.judge.confirmMirror(dir, r.aims.of(io.embodit.shouldercheck.domain.MirrorId.side(dir)), max(0.0, r.t))
        audio?.cue("turn_tick")
        ScLog.i("MIRROR confirm $dir aim=${r.aims.of(io.embodit.shouldercheck.domain.MirrorId.side(dir))}")
        updateHud()
    }

    /** Stalk: push toward a side = that signal on; again the same way = off (T5). Lesson 1: ±1° mirror aim (T15). */
    fun stalk(dir: Dir, hand: Hand = Hand.LEFT) {
        val r = run ?: return
        if (r.scenario.situation == Situation.MIRRORS) {
            val mirrorDir = if (hand == Hand.LEFT) Dir.LEFT.physical(r.scenario.side) else Dir.LEFT.physical(r.scenario.side).flip()
            val id = io.embodit.shouldercheck.domain.MirrorId.side(mirrorDir)
            val now = r.aims.of(id)
            // outward = pushing the stick toward the mirror's own side
            val delta = if (dir == mirrorDir) 1.0 else -1.0
            setAim(mirrorDir, now + delta)
            return
        }
        r.signal = if (r.signal.dir == dir) Signal.OFF else Signal.of(dir)
        audio?.indicator(r.signal != Signal.OFF)
        ScLog.i("SIGNAL ${r.signal}")
    }

    fun setSignal(sig: Signal) {
        val r = run ?: return
        if (r.signal == sig) return
        if (sig == Signal.OFF) stalk(r.signal.dir ?: return) else stalk(sig.dir!!)
    }

    fun setAim(dir: Dir, aim: Double) {
        val a = aim.coerceIn(MirrorSpec.AIM_MIN, MirrorSpec.AIM_MAX)
        val r = run
        if (r != null && r.scenario.situation == Situation.MIRRORS) r.judge.setAim(dir, a)
        else saveProgress(progress.copy(aims = progress.aims.with(dir, a)))
        ScLog.i(String.format(Locale.US, "MIRROR aim %s=%.0f blind=%.2f", dir, a, BlindSpot.length(dir, a, io.embodit.shouldercheck.domain.Cabin(Seat.DRIVER, progress.settings.traffic))))
        updateHud()
    }

    /** Mirror aims to draw with (lesson 1 live, else the saved ones). */
    fun aims(): MirrorAims = run?.takeIf { it.scenario.situation == Situation.MIRRORS }?.aims ?: progress.aims

    // ------------------------------------------------------------------ frame

    fun onFrame(input: FrameInput) {
        nowMs = input.nowMs
        val head = applyHeadOverride(input.head)
        effHead = head
        val gaze = applyGazeOverride(input.gaze, head)
        effGaze = gaze
        val base = state.value.base
        update { if (it.controllersTracked != input.controllersTracked) it.copy(controllersTracked = input.controllersTracked) else it }
        trackEye(input, gaze)

        // S00 → S01 / S19
        if (input.blockReason != null) {
            if (blockedSinceMs < 0) blockedSinceMs = nowMs
            if (nowMs - blockedSinceMs > 2000 && base != Base.S19) {
                ScLog.i("UNAVAILABLE reason=${input.blockReason}")
                autoUnavailable = true
                go(Base.S19, input.blockReason)
                return
            }
        } else {
            blockedSinceMs = -1L
            if (base == Base.S19 && autoUnavailable) {
                autoUnavailable = false
                go(Base.S01, "full space again")
            }
        }
        when (base) {
            Base.S00 -> if (built && head != null) go(Base.S01, "built")
            Base.S01B -> stepRange(head)
            else -> Unit
        }

        ambientT += input.dt
        val presses = Presses(input.aPresses + dbgPendingA, input.bPresses + dbgPendingB, input.yPresses)
        dbgPendingA = 0
        dbgPendingB = 0
        if (presses.y > 0 && (base.practising || state.value.lost)) {
            onEvent(Ev.Pause)
            return
        }
        when (base) {
            Base.S04 -> stepDemo(input)
            Base.S05, Base.S06, Base.S07, Base.S08, Base.S09 -> stepRun(input, head, gaze, presses)
            Base.S11 -> stepReplay(input)
            else -> Unit
        }
        if (state.value.hud.message != null && nowMs > messageUntilMs) update { it.copy(hud = it.hud.copy(message = null)) }
        updateAng(head)
        DebugCommands.afterFrame()
    }

    private data class Presses(val a: Int, val b: Int, val y: Int)

    private fun applyHeadOverride(h: HeadIn?): HeadIn? {
        val yaw = dbgHeadYaw ?: return h
        return HeadIn(yaw, dbgHeadPitch, h?.pos ?: Vec3.ZERO)
    }

    private fun applyGazeOverride(g: GazeRay?, head: HeadIn?): GazeRay? {
        val o = dbgGaze ?: return g
        return GazeRay(head?.pos ?: Vec3.ZERO, Angles.dir(o.first, o.second))
    }

    private fun trackEye(input: FrameInput, gaze: GazeRay?) {
        val ok = gaze != null
        if (ok) {
            eyeDropSinceMs = -1L
            if (!eyeOkLast) ScLog.i("EYE available")
        } else if (eyeOkLast) {
            eyeDropSinceMs = nowMs
            ScLog.i("EYE dropped reason=${input.eyeReason}")
        }
        eyeOkLast = ok
        val s = state.value
        val r = run
        val inEyeQuestion = r != null && r.judge.mode == JudgeMode.EYE && s.base.practising
        // S17: eyes not usable; inside an eye-mode question only after 5 s without samples
        val off = if (inEyeQuestion) (!ok && eyeDropSinceMs >= 0 && nowMs - eyeDropSinceMs >= 5000) else !ok
        if (off != s.eyeOff || (s.eyeReason != input.eyeReason && !ok)) {
            update { it.copy(eyeOff = off, eyeReason = if (ok) null else input.eyeReason) }
            ScLog.i("EYE off=$off reason=${input.eyeReason}")
        }
    }

    private fun stepDemo(input: FrameInput) {
        val d = demo ?: return
        if (!dbgHold) d.t += input.dt * speed
        val before = state.value.hud.demoLine
        updateHud()
        if (state.value.hud.demoLine != before) ScLog.i("DEMO step=${d.stepIndex} line=${state.value.hud.demoLine}")
        if (d.done) endDemo("done")
    }

    private fun stepRun(input: FrameInput, head: HeadIn?, gaze: GazeRay?, presses: Presses) {
        val r = run ?: return
        val base = state.value.base
        // S18 tracking lost (S04–S09)
        if (input.headLost) {
            if (!state.value.lost) {
                r.lostSinceMs = nowMs
                update { it.copy(lost = true, hud = it.hud.copy(message = HudMessage.Lost)) }
                messageUntilMs = Long.MAX_VALUE
                audio?.indicator(false)
                ScLog.i("STATE ${base.code}+S18 why=tracking lost")
            } else if (nowMs - r.lostSinceMs > 10_000) {
                pause()
            }
            return
        } else if (state.value.lost) {
            update { it.copy(lost = false, hud = it.hud.copy(message = null)) }
            ScLog.i("TRACK recovered -> restart question")
            startQuestion(r.scenario, "tracking back")
            return
        }
        val dt = input.dt * speed
        when (base) {
            Base.S05 -> {
                if (!dbgHold) r.t += dt
                if (r.t >= 0.0) {
                    r.t = 0.0
                    go(Base.S06, "ready 2 s")
                }
                return
            }
            Base.S08 -> {
                if (!dbgHold && nowMs - r.stateSinceMs >= 1000) showResult(r.result!!, r.judge, "clear 1 s")
                return
            }
            Base.S09 -> {
                val elapsed = nowMs - r.stateSinceMs
                val target = r.scenario.actors.firstOrNull { it.id == r.conflictActor }?.box(r.actorTime)?.centre
                val looked =
                    if (target != null && head != null && elapsed > 1200) {
                        val yaw = Angles.yawOf(target + Vec3(0.0, 0.8, 0.0) - head.pos)
                        abs(Angles.wrap(yaw - head.yaw)) < 25.0
                    } else false
                if (!dbgHold && (looked || elapsed >= 4200)) showResult(r.result!!, r.judge, if (looked) "looked at conflict" else "3 s")
                return
            }
            else -> Unit
        }
        // debug hold (goto S06 done=… / S07 t=…): the run clock stands still until the next command
        if (dbgHold && (base == Base.S06 || base == Base.S07)) return
        r.t += dt
        // inputs → judge sample (debug overrides feed the same fields)
        if (presses.a > 0) {
            when (r.scenario.situation.action) {
                ActionKind.MIRRORS_LESSON -> repeat(presses.a) { confirmMirror(r) }
                ActionKind.LANE, ActionKind.TURN -> r.execPending = true
                else -> Unit
            }
        }
        if (presses.b > 0) closeDoor(r)
        handleSticks(r, input)
        val (grab, push) = doorInput(r, input)
        val exec = r.execPending
        r.execPending = false
        val h = head ?: HeadIn(0.0, 0.0, Vec3.ZERO)
        feedSample(r, JudgeSample(r.t, h.yaw, h.pitch, h.pos, if (r.judge.mode == JudgeMode.EYE) gaze else null, r.signal, grab, push, exec))
    }

    /**
     * One judge sample and everything that follows from it (sounds, HUD, S06 → S07 → S08 / S09 / S10). The frame
     * system and the debug scripts (`goto S06 done=…`, `goto S08`) both come through here.
     */
    internal fun feedSample(r: QuestionRun, sample: JudgeSample) {
        if (r.judge.phase == Phase.DONE) return
        r.t = sample.t
        val live = r.judge.update(sample)
        r.live = live
        doorSounds(r, live.doorAngle)
        var finished: Outcome? = null
        for (e in live.events) {
            when (e) {
                is JudgeEvent.TurnReached -> audio?.cue("turn_tick")
                is JudgeEvent.StepStale -> message(HudMessage.Stale)
                is JudgeEvent.TurnEnded ->
                    when (val x = e.excursion) {
                        is Excursion.NearMiss -> message(HudMessage.TurnMore(x.dir, x.missing.roundToInt().coerceAtLeast(1)))
                        is Excursion.Relaxed -> message(HudMessage.Relaxed(x.dir, x.newThreshold.roundToInt()))
                        else -> Unit
                    }
                is JudgeEvent.Executed -> {
                    ScLog.i(String.format(Locale.US, "JUDGE executed t=%.2f", e.t))
                    if (state.value.base == Base.S06) go(Base.S07, "execute")
                }
                is JudgeEvent.SignalAutoCancel -> {
                    r.signal = Signal.OFF
                    audio?.indicator(false)
                }
                is JudgeEvent.Conflict -> ScLog.i(String.format(Locale.US, "JUDGE conflict t=%.2f actor=%s", e.t, e.actorId))
                is JudgeEvent.HazardSeen -> ScLog.i(String.format(Locale.US, "JUDGE saw t=%.2f via=%s", e.t, e.via))
                is JudgeEvent.StepLit -> ScLog.i(String.format(Locale.US, "STEP lit %s t=%.2f head=%s", e.step, e.t, e.byHead))
                is JudgeEvent.StepRevoked -> ScLog.i("STEP revoked ${e.step}")
                is JudgeEvent.Finished -> finished = e.outcome
            }
        }
        updateHud()
        finished?.let { onFinished(r, it) }
    }

    private fun message(m: HudMessage) {
        update { it.copy(hud = it.hud.copy(message = m)) }
        messageUntilMs = nowMs + 2500
    }

    private fun handleSticks(r: QuestionRun, input: FrameInput) {
        val list = input.controllers.map { it.hand to it.stickX }.toMutableList()
        dbgStick?.let { list += it }
        dbgStick = null
        for ((hand, x) in list) {
            // lesson 1: right stick = right mirror; elsewhere only the left stick is the stalk
            if (hand == Hand.RIGHT && r.scenario.situation != Situation.MIRRORS) continue
            val armed = r.stickArmed.getValue(hand)
            if (armed && abs(x) > 0.7f) {
                r.stickArmed[hand] = false
                stalk(if (x < 0) Dir.LEFT else Dir.RIGHT, hand)
            } else if (!armed && abs(x) < 0.3f) {
                r.stickArmed[hand] = true
            }
        }
    }

    /** Door grab (T7): grip inside the handle's reach sphere, or a hand pinch within 0.15 m (gesture mode), or debug. */
    private fun doorInput(r: QuestionRun, input: FrameInput): Pair<Hand?, Double> {
        if (r.scenario.situation.action != ActionKind.DOOR) return null to 0.0
        dbgDoor?.let { (hand, push) -> return hand to push }
        val cabin = r.scenario.cabin
        val held = r.grabHand
        if (held != null) {
            val c = input.controllers.firstOrNull { it.hand == held }
            val p = input.pinches.firstOrNull { it.first == held }
            val pos = c?.takeIf { it.grip }?.pos ?: p?.takeIf { it.third > PINCH_HOLD }?.second
            if (pos == null) {
                r.grabHand = null
                r.grabPoint = null
                return null to r.push
            }
            r.push = DoorModel.pushDistance(r.grabPoint ?: pos, pos, cabin.doorNormal)
            return held to r.push
        }
        for (c in input.controllers) {
            val pos = c.pos ?: continue
            if (c.grip && DoorModel.inReach(cabin, pos)) {
                r.grabHand = c.hand
                r.grabPoint = pos
                r.push = 0.0
                ScLog.i("DOOR grab hand=${c.hand} via=grip")
                return c.hand to 0.0
            }
        }
        for ((hand, pos, value) in input.pinches) {
            if (value > PINCH_HOLD && DoorModel.inReach(cabin, pos)) {
                r.grabHand = hand
                r.grabPoint = pos
                r.push = 0.0
                ScLog.i("DOOR grab hand=$hand via=pinch")
                return hand to 0.0
            }
        }
        return null to 0.0
    }

    private fun doorSounds(r: QuestionRun, angle: Double) {
        if (angle > 1.0 && r.doorSoundAngle <= 1.0) audio?.cue("door_open")
        if (abs(angle - r.doorSoundAngle) > 0.5) ScLog.i(String.format(Locale.US, "DOOR angle=%.1f push=%.3f hand=%s", angle, r.push, r.grabHand ?: dbgDoor?.first))
        r.doorSoundAngle = angle
    }

    private fun stepReplay(input: FrameInput) {
        val rp = replay ?: return
        if (!dbgHold) rp.t = (rp.t + input.dt * rp.speed).coerceAtMost(rp.end)
        updateHud()
    }

    // ------------------------------------------------------------------ HUD / ANG

    private fun updateHud() {
        val s = state.value
        val r = run
        val d = demo
        val rp = replay
        val sc = d?.scenario ?: r?.scenario ?: rp?.scenario
        if (sc == null) {
            if (s.hud.situation != null) update { it.copy(hud = HudState()) }
            return
        }
        val free = (course as? Course.FreeCourse)?.set
        val steps: List<StepChip> =
            when {
                d != null -> sc.requiredSteps.mapIndexed { i, st -> StepChip(st, if (i < d.stepIndex) StepState.DONE else StepState.PENDING, false, 0.0, highlight = i == d.stepIndex) }
                r != null -> (r.live?.steps?.map { StepChip(it.step, it.state, it.judgedByHead, it.progress) } ?: sc.requiredSteps.map { StepChip(it, StepState.PENDING, false, 0.0) })
                else -> rp!!.result.steps.map { StepChip(it.step, if (it.done) StepState.DONE else if (it.wrong) StepState.WRONG else StepState.PENDING, it.judgedByHead, if (it.done) 1.0 else 0.0) }
            }
        val action =
            when {
                d != null -> HudAction.SKIP_DEMO
                sc.situation == Situation.MIRRORS -> {
                    val near = Dir.LEFT.physical(sc.side)
                    val done = r?.confirmed ?: emptySet()
                    if (near !in done) (if (near == Dir.LEFT) HudAction.CONFIRM_LEFT else HudAction.CONFIRM_RIGHT)
                    else if (near.flip() !in done) (if (near.flip() == Dir.LEFT) HudAction.CONFIRM_LEFT else HudAction.CONFIRM_RIGHT)
                    else HudAction.NONE
                }
                sc.situation.action == ActionKind.DOOR -> HudAction.CLOSE_DOOR
                sc.situation.action == ActionKind.LANE || sc.situation.action == ActionKind.TURN -> if (s.controllersTracked) HudAction.NONE else HudAction.EXECUTE
                else -> HudAction.NONE
            }
        val aims = aims()
        val cabin = io.embodit.shouldercheck.domain.Cabin(Seat.DRIVER, sc.side)
        val rear = if (sc.noHazardReason == NoHazardReason.RANGE) range().max(sc.dir).roundToInt() else null
        val msg =
            if (r != null && sc.situation == Situation.BLIND_SPOT && s.hud.message == null && r.mirrorExit != null && r.t > r.mirrorExit!! + 0.5 && r.live?.saw != true)
                HudMessage.MirrorGone((r.t - r.mirrorExit!!).toInt())
            else s.hud.message
        val hud =
            HudState(
                situation = sc.situation,
                lesson = sc.lesson ?: (course as? Course.LessonCourse)?.lesson?.n,
                setIndex = free?.let { it.index + 1 },
                setLevel = free?.tracker?.level,
                mode = r?.judge?.mode ?: rp?.result?.mode,
                steps = steps,
                showSteps = d != null || rp != null || hintsOn() || sc.situation.action == ActionKind.MIRRORS_LESSON || sc.situation.action == ActionKind.BLIND_SPOT_LESSON,
                action = action,
                message = msg,
                demoLine = d?.let { sc.requiredSteps.getOrNull(it.stepIndex) },
                demoDone = d?.let { it.stepIndex >= sc.requiredSteps.size } ?: false,
                rearNoteDeg = rear,
                blindLeft = if (sc.situation == Situation.MIRRORS) BlindSpot.length(Dir.LEFT, aims.left, cabin) else null,
                blindRight = if (sc.situation == Situation.MIRRORS) BlindSpot.length(Dir.RIGHT, aims.right, cabin) else null,
                replaySpeed = rp?.speed?.toFloat() ?: 1f,
                replayT = rp?.t ?: 0.0,
            )
        if (hud != s.hud) update { it.copy(hud = hud) }
    }

    private fun updateAng(head: HeadIn?) {
        val s = state.value
        val visible = s.base == Base.S01B || s.base.practising
        val yaw = head?.yaw ?: 0.0
        fun forSide(dir: Dir): AngState {
            if (!visible) return AngState()
            val toward = max(0.0, yaw * dir.sign).roundToInt()
            if (s.base == Base.S01B) {
                val rec = if (dir == Dir.LEFT) rangeLeft else rangeRight
                return AngState(deg = rec?.roundToInt() ?: toward, range = true, lit = rec != null, visible = true)
            }
            val th = profile.threshold(dir).roundToInt()
            val peak = run?.live?.let { if (dir == Dir.LEFT) it.peakLeft else it.peakRight } ?: 0.0
            return AngState(deg = toward, threshold = th, lit = peak >= th || toward >= th, visible = true)
        }
        val l = forSide(Dir.LEFT)
        val rr = forSide(Dir.RIGHT)
        if (l != s.angLeft || rr != s.angRight) update { it.copy(angLeft = l, angRight = rr) }
    }

    // ------------------------------------------------------------------ what the scene draws

    /** Scenario whose road / actors are on show. */
    fun displayScenario(): Scenario = run?.scenario ?: replay?.scenario ?: demo?.scenario ?: ambient

    /** Actor boxes now (car frame of the displayed scenario's seat). */
    fun actorBoxes(): List<Pair<Actor, Box>> {
        val rp = replay
        if (rp != null && state.value.base == Base.S11) return rp.scenario.actors.map { it to it.box(rp.t) }
        val r = run
        if (r != null) {
            val t = r.actorTime
            return r.scenario.actors.map { it to it.box(t) }
        }
        val d = demo
        if (d != null) return d.scenario.actors.filter { it.role != ActorRole.HAZARD }.map { it to it.box(-2.0) }
        val t = Ambient.time(ambientT)
        return ambient.actors.map { it to it.box(t) }
    }

    /** Ghost car pose and whether it flashes red (S09). */
    fun ghost(): Pair<GhostPose, Boolean>? {
        val base = state.value.base
        val rp = replay
        if (rp != null && base == Base.S11) {
            val e = rp.result.execTime ?: return null
            return if (rp.t < e) null else rp.judge.ghost(rp.t)?.let { it to false }
        }
        val r = run ?: return null
        if (base != Base.S07 && base != Base.S08 && base != Base.S09) return null
        val g = r.judge.ghost(r.actorTime) ?: return null
        return g to (base == Base.S09)
    }

    fun doorAngle(): Double {
        val rp = replay
        if (rp != null && state.value.base == Base.S11) return rp.judge.doorAngle
        return run?.judge?.doorAngle ?: 0.0
    }

    /** Guide ring targets (car frame): demo step, hints in S06, the conflict point in S09. */
    fun guideTargets(): List<Vec3> {
        val base = state.value.base
        val d = demo
        if (d != null && base == Base.S04) {
            val st = d.scenario.requiredSteps.getOrNull(d.stepIndex) ?: return emptyList()
            return listOfNotNull(Layout.guideTarget(st, d.scenario.cabin))
        }
        val r = run ?: return emptyList()
        if (base == Base.S09) {
            val a = r.scenario.actors.firstOrNull { it.id == r.conflictActor } ?: return emptyList()
            return listOf(a.box(r.actorTime).centre + Vec3(0.0, 1.0, 0.0))
        }
        if (base == Base.S06 && hintsOn() && course is Course.LessonCourse) {
            val pending = r.live?.steps?.firstOrNull { it.state != StepState.DONE && it.step != Step.SIGNAL_LEFT && it.step != Step.SIGNAL_RIGHT && it.step != Step.CONFIRM_LEFT && it.step != Step.CONFIRM_RIGHT } ?: return emptyList()
            return listOfNotNull(Layout.guideTarget(pending.step, r.scenario.cabin))
        }
        return emptyList()
    }

    /** Demo hand position (lessons 3 / 4, the far-hand step), car frame, or null. */
    fun demoHand(): Vec3? {
        val d = demo ?: return null
        if (state.value.base != Base.S04) return null
        val i = d.scenario.requiredSteps.indexOf(Step.FAR_HAND)
        if (i < 0 || d.stepIndex != i) return null
        val u = if (d.holdStep != null) 0.7 else ((d.t - i * DemoState.STEP_SEC) / (DemoState.STEP_SEC * 0.8)).coerceIn(0.0, 1.0)
        val cabin = d.scenario.cabin
        val start = Vec3(-cabin.doorSide.sign * 0.25, -0.50, -0.30)
        return start.lerp(cabin.doorHandle, u * u * (3 - 2 * u))
    }

    /** Hazard drawn highlighted in its mirror (S09). */
    fun mirrorHighlight(): String? = if (state.value.base == Base.S09) run?.conflictActor else null

    /** Gaze-dot source: in eye mode the gaze ray, else the head direction (S17). */
    fun gazeDot(): GazeRay? {
        val s = state.value
        if (!progress.settings.showGaze) return null
        if (s.base != Base.S05 && s.base != Base.S06 && s.base != Base.S07) return null
        val h = effHead ?: return null
        val mode = run?.judge?.mode ?: JudgeMode.HEAD
        return if (mode == JudgeMode.EYE) effGaze else GazeRay(h.pos, Angles.dir(h.yaw, h.pitch))
    }

    /** Arc of the turn (S01B: the measured / live angle per side; S11: the head yaw on the trace). */
    fun arcs(): List<Pair<Dir, Double>> {
        val s = state.value
        if (s.base == Base.S01B) {
            val live = effHead?.yaw ?: 0.0
            val l = rangeLeft ?: if (rangeSide == Dir.LEFT) max(0.0, -live) else 0.0
            val r = rangeRight ?: if (rangeSide == Dir.RIGHT) max(0.0, live) else 0.0
            return listOf(Dir.LEFT to l, Dir.RIGHT to r)
        }
        val rp = replay
        if (s.base == Base.S11 && rp != null) {
            val sample = rp.result.trace.lastOrNull { it.t <= rp.t } ?: return emptyList()
            val y = sample.headYaw
            return listOf(Dir.ofSign(y) to abs(y))
        }
        return emptyList()
    }

    /** Replay gaze trail up to now: car-frame directions (yaw, pitch) of the 10 Hz trace, newest last. */
    fun replayTrail(): List<Pair<Double, Double>> {
        val rp = replay ?: return emptyList()
        if (state.value.base != Base.S11) return emptyList()
        return rp.result.trace.filter { it.t <= rp.t }.map { it.yaw to it.pitch }
    }

    // ------------------------------------------------------------------ debug support (see DebugCommands)

    /** Runs a judge offline over scripted samples (debug `result outcome=…`), returns the finished judge. */
    internal fun scriptedResult(outcome: String, mode: JudgeMode): Pair<Scenario, QuestionJudge> = DebugScripts.result(outcome, mode, progress.settings.traffic, progress.aims)

    /** Enters S10 with a judge that already finished (debug `result`): the normal result path (record, course, card). */
    internal fun finishScripted(scenario: Scenario, judge: QuestionJudge, why: String) {
        course = Course.DebugCourse(scenario)
        val r = QuestionRun(scenario, judge)
        r.result = judge.result()
        run = r
        showResult(r.result!!, judge, why)
    }

    internal fun debugStartDemo(n: Int, step: Int?) {
        startLesson(n)
        val c = course as? Course.LessonCourse ?: return
        if (demo == null) startDemo(n, c.trials.first(), step?.let { it - 1 })
        else demo?.holdStep = step?.let { it - 1 }
        updateHud()
    }

    internal fun debugStartSet(seed: Long, q: Int) = startSet(seed, q)

    internal fun debugEnterRange() = enterRange("debug")

    internal fun debugReplay(t: Double?) {
        if (state.value.base != Base.S10 && state.value.base != Base.S11) return
        if (state.value.base == Base.S11) replay?.let { it.t = (t ?: 0.0).coerceIn(0.0, it.end) } else startReplay(t)
    }

    internal fun debugSeedScores() {
        saveProgress(DebugScripts.sampleScores(progress))
        ScLog.i("SCORES seeded records=${progress.records.size}")
    }

    internal fun debugFinishSet(seed: Long) {
        val set = FreeModeSet(seed, progress.settings.traffic, range(), progress.aims)
        course = Course.FreeCourse(set)
        val outcomes = listOf("clear", "waited", "lucky", "clear", "miss", "clear", "clear", "waited", "timeout", "clear")
        var last: Pair<Scenario, QuestionJudge>? = null
        for (o in outcomes) {
            val (sc, j) = scriptedResult(o, JudgeMode.HEAD)
            val res = j.result()
            set.record(res)
            saveProgress(progress.withRecord(res.record(System.currentTimeMillis())))
            last = sc to j
        }
        last?.let { (sc, j) ->
            run = QuestionRun(sc, j).also { it.result = j.result() }
            lastResult = j.result() to j
        }
        update { it.copy(setSummary = set.summary()) }
        go(Base.S14, "debug set")
    }

    fun dump(): String {
        val s = state.value
        val r = run
        return buildString {
            append("base=").append(s.base.code).append(" page=").append(s.page)
            append(" eyeOff=").append(s.eyeOff).append("(").append(s.eyeReason).append(")")
            append(" lost=").append(s.lost).append(" seat=").append(s.seat).append(" traffic=").append(s.traffic)
            append(" lang=").append(s.language).append(" muted=").append(s.muted).append(" vol=").append(progress.settings.volume)
            append(" range=").append(progress.range).append(" T=").append(profile.threshold(Dir.LEFT)).append("/").append(profile.threshold(Dir.RIGHT))
            append(" aims=").append(aims())
            append(" course=").append(course?.javaClass?.simpleName)
            if (r != null) {
                append(String.format(Locale.US, " q=%s t=%.2f mode=%s phase=%s signal=%s door=%.1f", r.scenario.situation.id, r.t, r.judge.mode, r.judge.phase, r.signal, r.judge.doorAngle))
                append(" steps=").append(r.live?.steps?.joinToString(",") { "${it.step}:${it.state}" })
                append(" hazard=").append(r.scenario.hazard?.let { "${it.kind} z=${"%.1f".format(Locale.US, it.rearZ(r.actorTime))}" })
            }
            append(" head=").append(effHead?.let { String.format(Locale.US, "%.1f/%.1f", it.yaw, it.pitch) })
            append(" gaze=").append(effGaze?.let { String.format(Locale.US, "%.1f/%.1f", Angles.yawOf(it.dir), Angles.pitchOf(it.dir)) })
            append(" hold=").append(dbgHold).append(" speed=").append(speed)
            append(" records=").append(progress.records.size).append(" passed=").append(progress.passed.sorted())
        }
    }
}

private const val PINCH_HOLD = 0.8f
