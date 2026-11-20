package io.embodit.shouldercheck.app

import android.content.Context
import io.embodit.shouldercheck.domain.Actor
import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Angles
import io.embodit.shouldercheck.domain.Box
import io.embodit.shouldercheck.domain.CheckEvent
import io.embodit.shouldercheck.domain.CheckJudge
import io.embodit.shouldercheck.domain.CheckSample
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.DoorModel
import io.embodit.shouldercheck.domain.EgoMotion
import io.embodit.shouldercheck.domain.GO_LIT
import io.embodit.shouldercheck.domain.GamePlan
import io.embodit.shouldercheck.domain.GameSummary
import io.embodit.shouldercheck.domain.GazeRay
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Level
import io.embodit.shouldercheck.domain.Levels
import io.embodit.shouldercheck.domain.MirrorAims
import io.embodit.shouldercheck.domain.MirrorModel
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.Progress
import io.embodit.shouldercheck.domain.Round
import io.embodit.shouldercheck.domain.RoundGenerator
import io.embodit.shouldercheck.domain.RoundKind
import io.embodit.shouldercheck.domain.RoundResult
import io.embodit.shouldercheck.domain.Scoring
import io.embodit.shouldercheck.domain.Session
import io.embodit.shouldercheck.domain.Settings
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Target
import io.embodit.shouldercheck.domain.Thresholds
import io.embodit.shouldercheck.domain.TraceSample
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.Vec3
import io.embodit.shouldercheck.domain.VolumeLevel
import io.embodit.shouldercheck.platform.Audio
import io.embodit.shouldercheck.platform.EyePermissionActivity
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.platform.Store
import io.embodit.shouldercheck.ui.input.BoardInputLog
import io.embodit.shouldercheck.ui.window.WindowCtl
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every user action (window buttons, 3D taps, keys) and the debug hooks that stand in for them. */
sealed interface Ev {
    /** One tap on a 3D target (D50): [source] = board-input InputSource label, the A key, or debug. */
    data class Tap(val target: Target, val source: String) : Ev

    data object Pause : Ev
    data object Resume : Ev
    data object AskRestart : Ev
    data object AskLeave : Ev
    data object ConfirmYes : Ev
    data object ConfirmNo : Ev
    data object Recentre : Ev
    data object WindowFront : Ev
    data class Start(val level: Int) : Ev
    data object Daily : Ev
    data object PrevLevel : Ev
    data object NextLevel : Ev
    data object PlayAgain : Ev
    data object WatchMisses : Ev
    data object NextFromResult : Ev
    data object ToPractice : Ev
    data class OpenPage(val page: Page) : Ev
    data object HowTo : Ev
    data object HowToBack : Ev
    data class SetComfort(val on: Boolean) : Ev
    data class SetTraffic(val side: TrafficSide) : Ev
    data object EnableEyes : Ev
    data class SetVolume(val level: VolumeLevel) : Ev
    data object ToggleMute : Ev
    data class SetLanguage(val language: Language) : Ev
    data object AskClear : Ev
    data object AskQuit : Ev

    /** Controller B / system BACK (D60): window closed → pause / reopen; never quits. */
    data class Back(val where: String) : Ev
}

/** One round on screen: the generated round, its checks, the clock, the 「走」. */
class RoundRun(val round: Round, val index: Int, val checks: CheckJudge, val level: Level) {
    /** Round time: 0 = card appears. */
    var t = 0.0
    var tGo: Double? = null
    var result: RoundResult? = null

    /** Close call: the move freezes this long after 「走」 (≤ 0.6 s: the horn comes within 0.3 s). */
    var stopTau: Double? = null
    var endMs = 0L
    var hornPlayed = false
    var mirrorLitShown = false
    var shoulderLitShown = false

    val sinceGo: Double?
        get() = tGo?.let { t - it }

    /** How far the move has gone (s after 「走」, frozen at a close call). */
    fun moveTau(): Double? {
        val s = sinceGo ?: return null
        return stopTau?.let { min(s, it) } ?: s
    }
}

/** A replay (S15 after a close call, S21 the missed rounds at the end): the road stands still, traffic runs at 0.3×. */
class ReplayRun(val round: Round, val result: RoundResult, val trace: List<TraceSample>, val index: Int, val count: Int) {
    val start: Double = max(0.0, (result.tGo ?: GO_LIT) - 1.5)
    val end: Double = (result.tGo ?: (round.window - 1.0)) + min(result.conflictTau ?: 1.0, 1.5) + 0.2
    var t = start
    var doneMs = 0L

    val finished: Boolean
        get() = t >= end - 1e-6

    val speed = SPEED

    companion object {
        const val SPEED = 0.3
        const val AUTO_CONTINUE_MS = 4000L
    }
}

/**
 * All behaviour (design `states`, `game`, `rounds`, `scoring`, `judging`, `interactions`): one process-wide controller on
 * the main thread. The ECS frame system calls [onFrame] with tracked input in the car frame; the main window and the 3D
 * targets call [onEvent]. Debug hooks change the INPUT (head / gaze overrides, presses) or send the same [Ev] a button
 * sends; `goto` reaches a state through its normal entry path.
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
    private var thresholds = Thresholds()

    // ------------------------------------------------------------------ what the scene reads
    /** Changes when the car must be (re)seated at the current head pose (auto placement, 回正). */
    var placeEpoch = 0
        private set
    var built = false
        private set
    var seated = false
        private set

    var plan: GamePlan? = null
        private set
    var session: Session? = null
        private set
    var run: RoundRun? = null
        private set
    var replay: ReplayRun? = null
        private set
    private var replayQueue: List<RoundResult> = emptyList()
    private val roundTraces = HashMap<Int, Pair<Round, List<TraceSample>>>()

    /** The scenery between games (a quiet lane scene). */
    var idleRound: Round = idle(TrafficSide.RIGHT_HAND)
        private set
    private var idleT = 0.0
    val hoodColour: Int = io.embodit.shouldercheck.domain.Layout.HOOD_COLOURS[((System.nanoTime() / 7) % 4).toInt().let { if (it < 0) -it else it }]

    /** Effective head / gaze this frame (debug overrides applied), car frame. */
    var effHead: HeadIn? = null
        private set
    var effGaze: GazeRay? = null
        private set

    // ------------------------------------------------------------------ debug input overrides (same paths as the trackers)
    var dbgHeadYaw: Double? = null
    var dbgHeadPitch: Double = 0.0
    var dbgGaze: Pair<Double, Double>? = null
    var dbgEyeOff = false
    var dbgPendingA = 0
    var dbgPendingY = 0
    var dbgHold = false
    var dbgLost = false
    var mirrorHz = 20.0
    var testTex = false

    // ------------------------------------------------------------------ private state
    private var nowMs = 0L
    private var screenSinceMs = 0L
    private var eyeOkLast = false
    private var blockedSinceMs = -1L
    private var lostSinceMs = -1L
    private var pausedRun = false
    private var lastTapMs = HashMap<Target, Long>()
    private var levelFromStart = 1
    private var daily = false
    private var pausedFromBackground = false

    // ------------------------------------------------------------------ lifecycle

    fun start(context: Context) {
        if (started) {
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
        thresholds = progress.thresholdsObject()
        audio = Audio(app)
        applyGain()
        idleRound = idle(progress.settings.traffic)
        val granted = EyePermissionActivity.granted(app)
        update { it.copy(progress = progress, language = progress.settings.language, traffic = progress.settings.traffic, muted = progress.settings.muted, practiceLevel = lastLevel(), eyeGranted = granted, firstVisit = progress.gamesPlayed == 0) }
        ScLog.i("APP start v0.2.0 games=${progress.gamesPlayed} unlocked=${progress.unlocked} traffic=${progress.settings.traffic} comfort=${progress.settings.comfort} lang=${progress.settings.language} eyeGranted=$granted T=${progress.thresholds}")
        EyePermissionActivity.onResult = { ok ->
            ScLog.i("EYE permission result=$ok")
            update { it.copy(eyeGranted = ok) }
        }
    }

    fun onBuilt() {
        built = true
        ScLog.i("SCENE built")
    }

    /** The scene placed the car at a settled head pose (design `container.placement`). */
    fun onSeated(why: String) {
        if (!seated) ScLog.i("SEAT auto placed ($why)")
        seated = true
    }

    fun onBackground() {
        ScLog.i("LIFECYCLE background screen=${state.value.screen.code}")
        audio?.stopAll()
        if (state.value.screen.inRound && run != null) {
            pausedFromBackground = true
            pause("background")
        }
    }

    fun onForeground() {
        ScLog.i("LIFECYCLE foreground screen=${state.value.screen.code}")
        // design container.lifecycle: back to the pause card, nothing judged
        pausedFromBackground = false
    }

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

    private fun go(screen: Screen, why: String) {
        val old = state.value.screen
        screenSinceMs = nowMs
        val page =
            when (screen) {
                Screen.S32 -> Page.RECORDS
                Screen.S40, Screen.S41, Screen.S42, Screen.S43 -> Page.SETTINGS
                Screen.S20, Screen.S30, Screen.S50, Screen.S53 -> Page.PLAY
                else -> state.value.page
            }
        val confirm = when (screen) {
            Screen.S42 -> Confirm.QUIT
            Screen.S43 -> Confirm.CLEAR
            else -> if (state.value.confirm == Confirm.RESTART || state.value.confirm == Confirm.LEAVE) state.value.confirm.takeIf { screen == Screen.S50 } else null
        }
        update { it.copy(screen = screen, page = page, confirm = confirm, inGame = session != null && session?.finished != true, daily = daily, level = levelFromStart) }
        val s = state.value
        val mods = buildList {
            if (s.eyeOff && screen.inRound) add("S51")
            if (s.lost) add("S52")
            if (isEnglish()) add("EN")
            if (s.traffic == TrafficSide.LEFT_HAND) add("LHT")
        }
        ScLog.i("STATE ${screen.code}${if (mods.isEmpty()) "" else "+" + mods.joinToString("+")} from=${old.code} why=$why")
        if (!screen.inRound || screen == Screen.S15 || screen == Screen.S21) audio?.indicator(false)
    }

    fun isEnglish(): Boolean =
        when (progress.settings.language) {
            Language.EN -> true
            Language.ZH -> false
            Language.SYSTEM -> app.resources.getString(io.embodit.shouldercheck.R.string.lang_code) != "zh"
        }

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

    private fun lastLevel(): Int = progress.recent.lastOrNull { !it.daily }?.level?.coerceIn(1, progress.unlocked) ?: 1

    fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun idle(side: TrafficSide): Round = RoundGenerator.generate(Levels.of(1), Situation.LANE_LEFT, RoundKind.CLEAR, side, 99L)

    private fun log(msg: String): String {
        BoardInputLog.write("ShoulderCheck", msg)
        return msg
    }

    // ------------------------------------------------------------------ events

    fun onEvent(e: Ev) {
        ScLog.i("EVENT $e screen=${state.value.screen.code}")
        val screen = state.value.screen
        when (e) {
            is Ev.Tap -> tap(e.target, e.source)
            Ev.Pause -> if (screen.inRound) pause("button") else openWindow("menu")
            Ev.Resume -> if (screen == Screen.S50) resume()
            Ev.AskRestart -> update { it.copy(confirm = Confirm.RESTART) }
            Ev.AskLeave -> update { it.copy(confirm = Confirm.LEAVE) }
            Ev.ConfirmNo ->
                when (screen) {
                    Screen.S42, Screen.S43 -> go(Screen.S40, "cancel")
                    else -> update { it.copy(confirm = null) }
                }
            Ev.ConfirmYes ->
                when (state.value.confirm) {
                    Confirm.QUIT -> quit()
                    Confirm.CLEAR -> {
                        saveProgress(progress.cleared())
                        thresholds = progress.thresholdsObject()
                        update { it.copy(practiceLevel = 1, summary = null) }
                        ScLog.i("SCORES cleared")
                        go(Screen.S40, "cleared")
                    }
                    Confirm.RESTART -> startGame(levelFromStart, daily, "restart")
                    Confirm.LEAVE -> {
                        endGame("leave")
                        go(Screen.S30, "back to practice")
                    }
                    null -> Unit
                }
            Ev.Recentre -> {
                placeEpoch++
                ScLog.i("SEAT recentre epoch=$placeEpoch")
            }
            Ev.WindowFront -> WindowCtl.recall()
            is Ev.Start -> startGame(e.level.coerceIn(1, progress.unlocked), false, "start")
            Ev.Daily -> startGame(Levels.DAILY_LEVEL, true, "daily")
            Ev.PrevLevel -> update { it.copy(practiceLevel = (it.practiceLevel - 1).coerceAtLeast(1)) }
            Ev.NextLevel -> update { it.copy(practiceLevel = (it.practiceLevel + 1).coerceAtMost(Levels.COUNT)) }
            Ev.PlayAgain -> if (screen == Screen.S20) startGame(levelFromStart, daily, "again")
            Ev.WatchMisses -> if (screen == Screen.S20) startMissedReplay()
            Ev.ToPractice -> {
                if (session?.finished == false) endGame("to practice")
                go(Screen.S30, "to practice")
            }
            Ev.NextFromResult -> if (screen == Screen.S20 && levelFromStart < progress.unlocked) startGame(levelFromStart + 1, false, "next level")
            is Ev.OpenPage ->
                when (e.page) {
                    Page.PLAY -> if (pausedRun) go(Screen.S50, "tab") else if (screen == Screen.S20 || state.value.summary != null && screen != Screen.S30 && session?.finished == true) go(Screen.S20, "tab") else go(Screen.S30, "tab")
                    Page.RECORDS -> go(Screen.S32, "tab")
                    Page.SETTINGS -> go(Screen.S40, "tab")
                }
            Ev.HowTo -> go(Screen.S41, "how to")
            Ev.HowToBack -> if (screen == Screen.S41) go(Screen.S40, "how to back")
            is Ev.SetComfort -> settings { it.copy(comfort = e.on) }
            is Ev.SetTraffic -> if (e.side != progress.settings.traffic) {
                settings { it.copy(traffic = e.side) }
                idleRound = idle(e.side)
                if (session != null) endGame("traffic side")
                ScLog.i("SCENE regenerate traffic=${e.side}")
                go(screen, "traffic side")
            }
            Ev.EnableEyes -> {
                saveProgress(progress.copy(eyeAsked = true))
                EyePermissionActivity.open(app)
            }
            is Ev.SetVolume -> settings { it.copy(volume = e.level, muted = false) }
            Ev.ToggleMute -> settings { it.copy(muted = !it.muted) }
            is Ev.SetLanguage -> {
                settings { it.copy(language = e.language) }
                go(screen, "language")
            }
            Ev.AskClear -> go(Screen.S43, "clear?")
            Ev.AskQuit -> go(Screen.S42, "quit?")
            is Ev.Back -> back(e.where)
        }
    }

    fun quit() {
        ScLog.i("QUIT confirmed")
        audio?.stopAll()
        onQuit?.invoke()
    }

    /**
     * D60: controller B / BACK. A round on screen (window closed) → pause (the pause card is the control window); the
     * window was closed by its × between games → open it again; how-to → settings; a confirm → cancel. Never quits,
     * never sends the Stage to the background.
     */
    private fun back(where: String) {
        val s = state.value
        log("BACK $where screen=${s.screen.code} window=${WindowCtl.state}")
        when {
            s.screen.inRound && s.screen != Screen.S54 -> pause("back")
            s.screen.windowed && !WindowCtl.isOpen -> openWindow("back")
            s.screen == Screen.S41 -> go(Screen.S40, "back")
            s.confirm != null -> onEvent(Ev.ConfirmNo)
            else -> Unit
        }
    }

    private fun openWindow(why: String) {
        if (!state.value.screen.windowed) {
            // between games with nothing on: the practice page
            go(Screen.S30, why)
        }
        WindowCtl.open(why)
    }

    // ------------------------------------------------------------------ games and rounds

    /** Starts a game: [level] (or today's run), seeded; the first round opens at once (S10). */
    fun startGame(level: Int, isDaily: Boolean, why: String, seed: Long? = null) {
        daily = isDaily
        levelFromStart = if (isDaily) Levels.DAILY_LEVEL else level
        val lv = if (isDaily) Levels.daily() else Levels.of(level)
        val s = seed ?: if (isDaily) today().toLong() else System.currentTimeMillis() % 1_000_000L
        val p = GamePlan(lv, s, progress.settings.traffic, isDaily, firstEver = progress.gamesPlayed == 0 && !isDaily)
        plan = p
        session = Session(lv, isDaily, s)
        roundTraces.clear()
        replay = null
        pausedRun = false
        update { it.copy(summary = null, confirm = null, lost = false, pausedDaily = false, practiceLevel = levelFromStart) }
        ScLog.i("GAME start level=${lv.n} daily=$isDaily seed=$s kinds=${p.kinds} situations=${p.situations.map { it.id }} why=$why")
        startRound(0, "game start")
    }

    /** Debug `scene=… hazard=…`: one hand-made round inside the current (or a new) game. */
    fun startDebugRound(round: Round, why: String) {
        if (session == null) {
            daily = false
            levelFromStart = round.levelN
            session = Session(Levels.of(round.levelN), false, round.seed)
            plan = GamePlan(Levels.of(round.levelN), round.seed, round.side, false, false)
        }
        val idx = session!!.index.coerceAtMost(GamePlan.ROUNDS - 1)
        beginRun(round, idx, why)
    }

    private fun startRound(i: Int, why: String) {
        val p = plan ?: return
        beginRun(p.round(i), i, why)
    }

    private fun beginRun(round: Round, index: Int, why: String) {
        val mode = if (eyeOkLast && !dbgEyeOff) JudgeMode.EYE else JudgeMode.HEAD
        val checks = CheckJudge(round, mode, thresholds)
        val lv = session?.level ?: Levels.of(round.levelN)
        run = RoundRun(round, index, checks, lv)
        replay = null
        pausedRun = false
        WindowCtl.close("round")
        ScLog.i(
            String.format(
                Locale.US,
                "ROUND start i=%d/%d sit=%s dir=%s kind=%s mode=%s T=%.0f window=%.0f hazard=%s gap=%s trapDwell=%.1f why=%s",
                index + 1, GamePlan.ROUNDS, round.situation.id, round.dir, round.kind, mode, checks.threshold, round.window,
                round.hazard?.let { "${it.kind}@x=%.2f".format(Locale.US, it.x) }, round.firstGap()?.let { "%.1f–%.1f".format(Locale.US, it.first, it.second) }, round.trapDwell, why,
            )
        )
        update { it.copy(eyeOff = mode == JudgeMode.HEAD, lost = false) }
        audio?.indicator(round.situation.lane)
        go(Screen.S10, why)
    }

    private fun pause(why: String) {
        val r = run ?: return
        if (state.value.screen == Screen.S50) return
        pausedRun = true
        update { it.copy(pausedDaily = daily) }
        audio?.indicator(false)
        ScLog.i("PAUSE round=${r.index + 1} t=${"%.2f".format(Locale.US, r.t)} why=$why")
        go(Screen.S50, "pause $why")
        WindowCtl.open("pause")
    }

    /**
     * 继续 (design S50): the round starts again from its card (practice: not judged); in today's run the paused round
     * counts as played (too late) and the next one starts.
     */
    private fun resume() {
        val r = run
        pausedRun = false
        update { it.copy(lost = false, confirm = null) }
        if (r == null) {
            go(Screen.S30, "resume none")
            return
        }
        if (r.result != null) {
            // paused after 「走」 (moving, result pop, replay): that round is already scored — go on to the next one
            replay = null
            nextRound("resume after result")
            return
        }
        if (daily) {
            val res = Scoring.judge(r.round, r.index, r.checks, null, session?.combo ?: 0)
            session?.record(res)
            ScLog.i("ROUND daily paused -> counted too late i=${r.index + 1}")
            nextRound("daily pause")
            return
        }
        beginRun(r.round, r.index, "resume")
    }

    private fun endGame(why: String) {
        ScLog.i("GAME end (not recorded) why=$why")
        session = null
        plan = null
        run = null
        replay = null
        pausedRun = false
        audio?.indicator(false)
    }

    private fun nextRound(why: String) {
        val s = session ?: return
        val r = run
        if (r != null) roundTraces[r.index] = r.round to r.checks.trace.toList()
        if (s.finished) {
            finishGame()
            return
        }
        // S54: the eye-tracking question comes once, after the first round of the first game
        if (r != null && r.index == 0 && !progress.eyeAsked && !state.value.eyeGranted && !EyePermissionActivity.granted(app) && !daily) {
            run = null
            go(Screen.S54, "ask eyes")
            return
        }
        startRound(s.index, why)
    }

    private fun finishGame() {
        val s = session ?: return
        val prev = if (s.daily) progress.dailyBestFor(today()) else progress.best(s.level.n).best
        val sum = s.summary(prev)
        saveProgress(progress.withGame(sum, System.currentTimeMillis(), today()).withThresholds(thresholds))
        run = null
        replayQueue = s.missed
        if (sum.newBest) audio?.cue(Audio.RECORD)
        update { it.copy(summary = sum, missedCount = s.missed.size, practiceLevel = levelFromStart) }
        ScLog.i("GAME over level=${sum.level} daily=${sum.daily} total=${sum.total} stars=${sum.stars} clear=${sum.clear}/8 avgShoulder=${sum.avgShoulder} missedMost=${sum.missedMost} newBest=${sum.newBest} unlocked=${progress.unlocked} results=${s.results.map { it.outcome.code }}")
        go(Screen.S20, "8 rounds")
        WindowCtl.open("game over")
    }

    private fun startMissedReplay() {
        if (replayQueue.isEmpty()) return
        showReplay(0)
    }

    private fun showReplay(i: Int) {
        val res = replayQueue.getOrNull(i) ?: return go(Screen.S20, "replays done")
        val (round, trace) = roundTraces[res.index] ?: return showReplay(i + 1)
        replay = ReplayRun(round, res, trace, i, replayQueue.size)
        WindowCtl.close("replay")
        go(Screen.S21, "missed replay ${i + 1}/${replayQueue.size}")
    }

    // ------------------------------------------------------------------ taps

    /**
     * One tap on a 3D target (D50; board-input listener, the controller-ray fallback, the A key and the debug `tap` all
     * come here). A system tap and the fallback for the same press count once (800 ms).
     */
    fun tap(target: Target, source: String): String {
        val last = lastTapMs[target] ?: -10_000L
        if (source != "key-a" && nowMs - last < io.embodit.shouldercheck.domain.DoorTargets.DEDUPE_MS) return log("TAP ${target.id} src=$source ignored: same press")
        lastTapMs[target] = nowMs
        val screen = state.value.screen
        log("TAP ${target.id} src=$source screen=${screen.code}")
        when (target) {
            Target.GO -> when (screen) {
                Screen.S11 -> commitGo(source)
                Screen.S10 -> log("GO early (card) ignored")
                Screen.S15, Screen.S21 -> continueReplay(source)
                else -> log("GO ignored in ${screen.code}")
            }
            Target.DOOR -> if (screen == Screen.S11 && run?.round?.situation == Situation.DOOR) commitGo(source) else log("DOOR ignored in ${screen.code}")
            Target.PAUSE -> if (screen.inRound && screen != Screen.S54) pause("chip") else openWindow("chip")
            Target.ASK_ON -> if (screen == Screen.S54) {
                saveProgress(progress.copy(eyeAsked = true))
                EyePermissionActivity.open(app)
                ScLog.i("EYE ask -> on")
                startRound(session?.index ?: 1, "eye ask on")
            }
            Target.ASK_OFF -> if (screen == Screen.S54) {
                saveProgress(progress.copy(eyeAsked = true))
                ScLog.i("EYE ask -> no")
                startRound(session?.index ?: 1, "eye ask off")
            }
        }
        return "ok"
    }

    /** The A key: 「走」 / 「开门」 / 「继续」 without aiming (design `inputNotes`: a shortcut, not a substitute). */
    private fun keyA() {
        when (state.value.screen) {
            Screen.S11 -> tap(if (run?.round?.situation == Situation.DOOR) Target.DOOR else Target.GO, "key-a")
            Screen.S15, Screen.S21 -> tap(Target.GO, "key-a")
            else -> log("KEY A ignored in ${state.value.screen.code}")
        }
    }

    private fun commitGo(source: String) {
        val r = run ?: return
        if (r.tGo != null) return
        val head = effHead
        if (!CheckJudge.goAllowed(r.round.situation, head?.yaw)) {
            update { it.copy(hud = it.hud.copy(goShakeMs = nowMs, lookForwardUntilMs = nowMs + 1500)) }
            log(String.format(Locale.US, "GO refused: head yaw %.0f (look forward first)", head?.yaw ?: 0.0))
            return
        }
        r.tGo = r.t
        val res = Scoring.judge(r.round, r.index, r.checks, r.t, session?.combo ?: 0)
        r.result = res
        session?.record(res)
        if (res.outcome == Outcome.MISS) {
            val tau = res.conflictTau ?: 0.0
            r.stopTau = (tau - 0.3).coerceIn(0.1, 0.6)
        }
        thresholdsSave()
        ScLog.i(
            String.format(
                Locale.US,
                "JUDGE go t=%.2f src=%s outcome=%s points=%d stars=%d combo=%d x%.1f reaction=%s mirror=%s shoulder=%s saw=%s conflict=%s@%s peak=%.0f T=%.0f mode=%s",
                r.t, source, res.outcome, res.points, res.stars, res.combo, res.multiplier, res.reaction?.let { "%.2f".format(Locale.US, it) }, res.mirrorOk, res.shoulderOk, res.saw,
                res.conflictActor, res.conflictTau?.let { "%.1f".format(Locale.US, it) }, res.shoulderPeak, res.threshold, res.mode,
            )
        )
        val au = audio
        when (res.outcome) {
            Outcome.SAFE, Outcome.WAITED -> au?.ding(res.combo)
            Outcome.LUCKY -> au?.cue(Audio.TICK, level = 0.8f)
            else -> Unit
        }
        if (r.round.situation == Situation.DOOR) au?.cue(Audio.LATCH)
        audio?.indicator(false)
        go(Screen.S12, "go ${res.outcome.code}")
    }

    private fun thresholdsSave() {
        val p = progress.withThresholds(thresholds)
        if (p.thresholds != progress.thresholds) saveProgress(p)
    }

    private fun continueReplay(source: String) {
        val rp = replay ?: return
        log("REPLAY continue src=$source")
        if (state.value.screen == Screen.S21) showReplay(rp.index + 1) else {
            replay = null
            nextRound("replay continue")
        }
    }

    // ------------------------------------------------------------------ frame

    fun onFrame(input: FrameInput) {
        nowMs = input.nowMs
        val head = applyHeadOverride(input.head)
        effHead = head
        val gaze = applyGazeOverride(input.gaze, head)
        effGaze = gaze
        trackEye(input, gaze)
        WindowCtl.onFrame(nowMs)
        val screen = state.value.screen

        // S53: not in Full Space for 2 s
        if (input.blockReason != null) {
            if (blockedSinceMs < 0) blockedSinceMs = nowMs
            if (nowMs - blockedSinceMs > 2000 && screen != Screen.S53) {
                ScLog.i("UNAVAILABLE reason=${input.blockReason}")
                go(Screen.S53, input.blockReason)
                WindowCtl.open("unavailable")
                return
            }
        } else blockedSinceMs = -1L

        if (screen == Screen.S00 && built && seated && head != null) {
            startGame(lastLevel(), false, "launch")
            return
        }

        idleT += input.dt
        val a = input.aPresses + dbgPendingA
        val y = input.yPresses + dbgPendingY
        dbgPendingA = 0
        dbgPendingY = 0
        if (y > 0) {
            if (screen.inRound && screen != Screen.S54) pause("key Y") else if (!WindowCtl.isOpen) openWindow("key Y")
        }
        if (a > 0) keyA()

        // S52 tracking lost while a round runs: freeze, pause card says so; recovery → pause card
        val lostNow = input.headLost || dbgLost
        if (screen.inRound && run != null && screen != Screen.S54) {
            if (lostNow) {
                if (lostSinceMs < 0) lostSinceMs = nowMs
                if (nowMs - lostSinceMs >= 500) {
                    update { it.copy(lost = true) }
                    pause("tracking lost")
                    return
                }
            } else lostSinceMs = -1L
        } else if (screen == Screen.S50 && state.value.lost && !lostNow) {
            update { it.copy(lost = false) }
            ScLog.i("TRACK recovered -> pause card")
        }

        when (state.value.screen) {
            Screen.S10, Screen.S11, Screen.S12, Screen.S13, Screen.S14, Screen.S16 -> stepRun(input, head, gaze)
            Screen.S15, Screen.S21 -> stepReplay(input)
            else -> Unit
        }
        updateHud()
        DebugCommands.afterFrame()
    }

    private fun applyHeadOverride(h: HeadIn?): HeadIn? {
        val yaw = dbgHeadYaw ?: return h
        return HeadIn(yaw, dbgHeadPitch, h?.pos ?: Vec3.ZERO)
    }

    private fun applyGazeOverride(g: GazeRay?, head: HeadIn?): GazeRay? {
        if (dbgEyeOff) return null
        val o = dbgGaze ?: return g
        return GazeRay(head?.pos ?: Vec3.ZERO, Angles.dir(o.first, o.second))
    }

    private fun trackEye(input: FrameInput, gaze: GazeRay?) {
        val ok = gaze != null
        if (ok != eyeOkLast) ScLog.i("EYE ${if (ok) "available" else "dropped reason=${input.eyeReason}"}")
        eyeOkLast = ok
        if (state.value.eyeReason != input.eyeReason && !ok) update { it.copy(eyeReason = input.eyeReason) }
    }

    private fun stepRun(input: FrameInput, head: HeadIn?, gaze: GazeRay?) {
        val r = run ?: return
        if (dbgHold) return
        r.t += input.dt
        val h = head ?: HeadIn(0.0, 0.0, Vec3.ZERO)
        r.checks.update(CheckSample(r.t, h.yaw, h.pitch, h.pos, if (r.checks.mode == JudgeMode.EYE) gaze else null))
        for (e in r.checks.events) {
            when (e) {
                is CheckEvent.MirrorLit -> {
                    audio?.cue(Audio.TICK)
                    ScLog.i(String.format(Locale.US, "CHECK mirror lit t=%.2f head=%s", e.t, e.byHead))
                }
                is CheckEvent.ShoulderLit -> {
                    audio?.cue(Audio.TICK)
                    ScLog.i(String.format(Locale.US, "CHECK shoulder lit t=%.2f yaw=%.0f head=%s", e.t, e.yaw, e.byHead))
                }
                is CheckEvent.TurnEnded -> ScLog.i(String.format(Locale.US, "CHECK turn peak=%.0f T=%.0f change=%d nearMiss=%s", e.peak, e.threshold, e.change, e.nearMiss))
                is CheckEvent.HazardSeen -> ScLog.i(String.format(Locale.US, "CHECK saw hazard t=%.2f via=%s", e.t, e.via))
            }
        }
        val screen = state.value.screen
        when (screen) {
            Screen.S10 -> if (r.t >= GO_LIT) go(Screen.S11, "card 1 s")
            Screen.S11 -> if (r.t >= r.round.window) {
                val res = Scoring.judge(r.round, r.index, r.checks, null, session?.combo ?: 0)
                r.result = res
                session?.record(res)
                r.endMs = nowMs
                audio?.cue(Audio.TIMEOUT)
                audio?.indicator(false)
                ScLog.i("JUDGE timeout t=${"%.2f".format(Locale.US, r.t)} gap=${r.round.firstGap()}")
                go(Screen.S16, "timer ran out")
            }
            Screen.S12 -> {
                val res = r.result ?: return
                val since = r.sinceGo ?: 0.0
                if (res.outcome == Outcome.MISS) {
                    if (!r.hornPlayed && since >= HORN_DELAY) {
                        r.hornPlayed = true
                        nearMissSounds(r)
                    }
                    if (since >= (r.stopTau ?: 0.3) + FREEZE) startCloseCallReplay(r)
                } else if (since >= EgoMotion.duration(r.round.situation)) {
                    r.endMs = nowMs
                    go(if (res.outcome == Outcome.LUCKY) Screen.S14 else Screen.S13, "move done")
                }
            }
            Screen.S13 -> if (nowMs - r.endMs >= POP_CLEAR_MS) nextRound("clear pop")
            Screen.S14, Screen.S16 -> if (nowMs - r.endMs >= POP_OTHER_MS) nextRound("pop")
            else -> Unit
        }
    }

    private fun startCloseCallReplay(r: RoundRun) {
        val res = r.result ?: return
        roundTraces[r.index] = r.round to r.checks.trace.toList()
        replay = ReplayRun(r.round, res, r.checks.trace.toList(), 0, 1)
        go(Screen.S15, "close call replay")
    }

    private fun stepReplay(input: FrameInput) {
        val rp = replay ?: return
        if (dbgHold) return
        if (!rp.finished) {
            rp.t = min(rp.end, rp.t + input.dt * rp.speed)
            if (rp.finished) rp.doneMs = nowMs
        } else if (nowMs - rp.doneMs >= ReplayRun.AUTO_CONTINUE_MS) {
            continueReplay("auto")
        }
    }

    private fun nearMissSounds(r: RoundRun) {
        val res = r.result ?: return
        val a = r.round.actors.firstOrNull { it.id == res.conflictActor }
        val head = effHead
        val pan =
            if (a == null) 0f
            else {
                val d = a.box(r.t).centre - (head?.pos ?: Vec3.ZERO)
                val rel = Angles.wrap(Angles.yawOf(d) - (head?.yaw ?: 0.0))
                kotlin.math.sin(Math.toRadians(rel)).toFloat()
            }
        val au = audio ?: return
        if (a?.kind?.rider == true) au.cue(Audio.BELL, pan) else au.cue(Audio.ALERT, pan)
        ScLog.i("NEARMISS sound actor=${a?.id} kind=${a?.kind} pan=${"%.2f".format(Locale.US, pan)}")
    }

    // ------------------------------------------------------------------ HUD

    private fun updateHud() {
        val s = state.value
        val r = run
        val screen = s.screen
        val rp = replay
        val sum = s.summary
        val hud =
            if (r != null && screen.inRound && screen != Screen.S15 && screen != Screen.S21) {
                val res = r.result
                val lv = r.level
                val window = r.round.window
                val left = ((window - r.t) / (window - GO_LIT)).coerceIn(0.0, 1.0)
                HudState(
                    situation = r.round.situation,
                    dir = r.round.dir,
                    cardVisible = res == null,
                    cardBig = screen == Screen.S10,
                    firstHint = r.index == 0 && progress.gamesPlayed == 0 && !daily,
                    eyeOffLine = r.checks.mode == JudgeMode.HEAD && r.index > 0,
                    goVisible = true,
                    goLit = screen == Screen.S11,
                    goLabel = GoLabel.GO,
                    timerLeft = if (screen == Screen.S10) 1.0 else left,
                    goShakeMs = s.hud.goShakeMs,
                    lookForwardUntilMs = s.hud.lookForwardUntilMs,
                    chipsVisible = lv.checkChips && res == null,
                    mirrorLit = r.checks.mirrorFresh(r.t),
                    shoulderLit = r.checks.shoulderFresh(r.t),
                    combo = session?.combo ?: 0,
                    score = session?.total ?: 0,
                    round = r.index + 1,
                    pop = res?.let { popOf(r, it) },
                    pauseVisible = true,
                    mergeMetres = if (r.round.situation == Situation.MERGE && res == null) (80.0 * left).toInt() else null,
                )
            } else if (rp != null && (screen == Screen.S15 || screen == Screen.S21)) {
                HudState(
                    situation = rp.round.situation,
                    dir = rp.round.dir,
                    goVisible = true,
                    goLit = true,
                    goLabel = if (screen == Screen.S21) GoLabel.SKIP else GoLabel.CONTINUE,
                    replay = true,
                    replayIndex = rp.index + 1,
                    replayCount = rp.count,
                    pop = popOf(null, rp.result, rp.round),
                    pauseVisible = screen == Screen.S15,
                )
            } else if (screen == Screen.S54) {
                HudState(askVisible = true, combo = session?.combo ?: 0, score = session?.total ?: 0)
            } else {
                HudState(menuChip = screen != Screen.S00 && !WindowCtl.isOpen, summary = if (screen == Screen.S20 && !WindowCtl.isOpen) sum else null)
            }
        if (hud != s.hud) update { it.copy(hud = hud) }
    }

    private fun popOf(r: RoundRun?, res: RoundResult, round: Round? = r?.round): ResultPop {
        val rd = round ?: r!!.round
        val hazard = rd.actors.firstOrNull { it.id == res.conflictActor }
        val seen = hazard != null && res.tGo != null && MirrorModel.sees(hazard.box(res.tGo), rd.mirror, rd.cabin, MirrorAims().of(rd.mirror.id))
        return ResultPop(res.outcome, res.points, res.stars, res.combo, res.mirrorOk, res.shoulderOk, res.reaction, if (res.trap || !seen) rd.dir else null, seen, rd.situation == Situation.DOOR, screenSinceMs)
    }

    // ------------------------------------------------------------------ what the scene draws

    /** The round whose road / traffic are on show. */
    fun displayRound(): Round = replay?.takeIf { state.value.screen == Screen.S15 || state.value.screen == Screen.S21 }?.round ?: run?.round ?: idleRound

    /** Time of the displayed traffic. */
    fun displayTime(): Double {
        val screen = state.value.screen
        replay?.let { if (screen == Screen.S15 || screen == Screen.S21) return it.t }
        run?.let { return it.t }
        return idleT % 40.0
    }

    /** Actor boxes now (car frame of the round's start). */
    fun actorBoxes(): List<Pair<Actor, Box>> {
        val r = displayRound()
        val t = displayTime()
        return r.actors.map { it to it.box(t) }
    }

    /** Your car's move: (sideways x, forward z) offset of the car relative to the road at the displayed time. */
    fun egoOffset(): Pair<Double, Double> {
        val screen = state.value.screen
        val rp = replay
        if (rp != null && (screen == Screen.S15 || screen == Screen.S21)) {
            val tGo = rp.result.tGo ?: return 0.0 to 0.0
            val tau = (rp.t - tGo).coerceAtLeast(0.0).let { tt -> rp.result.conflictTau?.let { min(tt, (it - 0.3).coerceIn(0.1, 0.6)) } ?: tt }
            if (rp.t < tGo) return 0.0 to 0.0
            return EgoMotion.lateral(rp.round.situation, rp.round.side, tau) to EgoMotion.forward(rp.round.situation, tau)
        }
        val r = run ?: return 0.0 to 0.0
        val tau = r.moveTau() ?: return 0.0 to 0.0
        return EgoMotion.lateral(r.round.situation, r.round.side, tau) to EgoMotion.forward(r.round.situation, tau)
    }

    /** True while your car is moving sideways (the 25 % vignette, design `scenarios[].anim`). */
    fun egoMoving(): Boolean {
        val r = run ?: return false
        val tau = r.moveTau() ?: return false
        return r.round.situation.lane && tau < EgoMotion.LANE_TIME && state.value.screen == Screen.S12
    }

    /** Scenery scroll speed (m/s): 36 km/h while cruising, unless comfort mode (design `model.egoMotion`). */
    fun scrollSpeed(): Double {
        val screen = state.value.screen
        if (screen == Screen.S15 || screen == Screen.S21 || screen == Screen.S50 || pausedRun) return 0.0
        val r = run ?: return 0.0
        // parked scenes do not scroll (the pull-out's own move is in egoOffset); comfort mode: your car stays still
        if (progress.settings.comfort || !r.round.situation.cruising) return 0.0
        return EgoMotion.CRUISE
    }

    fun doorAngle(): Double {
        val screen = state.value.screen
        val rp = replay
        if (rp != null && (screen == Screen.S15 || screen == Screen.S21)) {
            if (rp.round.situation != Situation.DOOR) return 0.0
            val tGo = rp.result.tGo ?: return 0.0
            return DoorModel.angleAt(rp.t - tGo, if (rp.result.outcome == Outcome.MISS) DoorModel.STOP_DEG else null)
        }
        val r = run ?: return 0.0
        if (r.round.situation != Situation.DOOR) return 0.0
        return DoorModel.angleAt(r.sinceGo, if (r.result?.outcome == Outcome.MISS) DoorModel.STOP_DEG else null)
    }

    /** The door takes taps (and shows the system hover) only while a door round is checking. */
    fun doorTappable(): Boolean = state.value.screen == Screen.S11 && run?.round?.situation == Situation.DOOR

    /** Which 3D targets take taps now (their colliders are on only then). */
    fun tappable(t: Target): Boolean {
        val s = state.value
        return when (t) {
            Target.GO -> s.hud.goVisible && (s.screen == Screen.S11 || s.screen == Screen.S15 || s.screen == Screen.S21 || s.screen == Screen.S10)
            Target.DOOR -> doorTappable()
            Target.PAUSE -> s.hud.pauseVisible || s.hud.menuChip
            Target.ASK_ON, Target.ASK_OFF -> s.screen == Screen.S54
        }
    }

    /** The hazard drawn highlighted (replays): its id. */
    fun highlightId(): String? = replay?.takeIf { state.value.screen == Screen.S15 || state.value.screen == Screen.S21 }?.result?.conflictActor ?: replay?.round?.hazardId?.takeIf { state.value.screen == Screen.S15 || state.value.screen == Screen.S21 }

    /** Replay gaze / head trail up to now: car-frame (yaw, pitch), newest last. */
    fun replayTrail(): List<Pair<Double, Double>> {
        val rp = replay ?: return emptyList()
        val s = state.value.screen
        if (s != Screen.S15 && s != Screen.S21) return emptyList()
        return rp.trace.filter { it.t <= rp.t && it.t >= rp.start - 1.0 }.map { it.yaw to it.pitch }
    }

    /** Dusk lighting (level 4). */
    fun dusk(): Boolean = (run?.level ?: session?.level)?.dusk == true && (state.value.screen.inRound || state.value.screen == Screen.S50)

    // ------------------------------------------------------------------ debug support

    /** Enters S13–S16 with a scripted go (debug `result outcome=…`) on the current round, through [commitGo]. */
    internal fun debugResult(outcome: Outcome, combo: Int) {
        // build a round where the outcome is what was asked, then let the real judge decide
        val side = progress.settings.traffic
        val lv = Levels.of(levelFromStart)
        val round =
            when (outcome) {
                Outcome.MISS -> RoundGenerator.debugRound(lv, Situation.LANE_LEFT, side, ActorKind.SEDAN, 3.6, 0.0, trap = true)
                Outcome.WAITED -> RoundGenerator.debugRound(lv, Situation.LANE_LEFT, side, ActorKind.SEDAN, 16.0, 5.0, trap = false)
                else -> RoundGenerator.debugRound(lv, Situation.LANE_LEFT, side, null, 0.0, 0.0, trap = false)
            }
        startDebugRound(round, "debug result $outcome")
        val s = session!!
        // seed the combo the way real rounds would (previous clear results)
        while (s.combo < combo && !s.finished) {
            val pr = s.results.size
            s.record(RoundResult(pr, Situation.LANE_LEFT, RoundKind.CLEAR, Outcome.SAFE, 140, 3, s.combo + 1, Scoring.multiplier(s.combo + 1), 0.5, 2.0, true, true))
        }
        val r = run!!
        val j = r.checks
        val mid = (j.band.start + j.band.endInclusive) / 2
        val gap = round.firstGap()
        val tGo = when (outcome) {
            Outcome.MISS -> 1.6
            Outcome.TIMEOUT -> round.window + 0.1
            else -> gap?.first?.plus(0.3) ?: 2.5
        }
        // scripted head samples into the real check judge (look at the mirror, look back, then forward)
        var t = 0.0
        val doChecks = outcome != Outcome.LUCKY
        while (t < tGo && t < round.window) {
            val yaw = when {
                !doChecks -> 0.0
                outcome == Outcome.WAITED && t in 1.2..1.6 -> mid
                outcome == Outcome.WAITED && t in 1.8..2.3 -> r.round.dir.sign * (j.threshold + 6.0)
                t in (tGo - 1.6)..(tGo - 1.2) -> mid
                t in (tGo - 1.0)..(tGo - 0.6) -> r.round.dir.sign * (j.threshold + 6.0)
                else -> 0.0
            }
            r.t = t
            j.update(CheckSample(t, yaw, 0.0, Vec3.ZERO, null))
            t += 1.0 / 60
        }
        r.t = min(tGo, round.window)
        go(Screen.S11, "debug result")
        if (outcome == Outcome.TIMEOUT) {
            r.t = round.window
            return
        }
        commitGo("debug")
    }

    internal fun debugGoto(s: Screen, args: Map<String, String>): String {
        when (s) {
            Screen.S10, Screen.S11 -> {
                if (run == null || args.containsKey("scene") || args.containsKey("hazard")) DebugCommands.startRoundFromArgs(args, "goto ${s.code}")
                if (s == Screen.S11) run?.let { it.t = max(it.t, GO_LIT) }
            }
            Screen.S12 -> {
                if (run == null) DebugCommands.startRoundFromArgs(args, "goto S12")
                run?.let { it.t = max(it.t, (it.round.firstGap()?.first ?: GO_LIT) + 0.2) }
                go(Screen.S11, "goto S12")
                dbgHeadYaw = 0.0
                commitGo("debug goto")
            }
            Screen.S13 -> debugResult(Outcome.SAFE, args["combo"]?.toIntOrNull() ?: 0)
            Screen.S14 -> debugResult(Outcome.LUCKY, 0)
            Screen.S15 -> debugResult(Outcome.MISS, 0)
            Screen.S16 -> debugResult(Outcome.TIMEOUT, 0)
            Screen.S20 -> {
                if (session == null || session?.finished != true) debugPlayScripted(levelFromStart.coerceAtLeast(1), listOf(Outcome.SAFE, Outcome.SAFE, Outcome.WAITED, Outcome.LUCKY, Outcome.SAFE, Outcome.MISS, Outcome.SAFE, Outcome.SAFE))
            }
            Screen.S21 -> {
                if (state.value.screen != Screen.S20) debugPlayScripted(levelFromStart.coerceAtLeast(1), listOf(Outcome.SAFE, Outcome.MISS, Outcome.WAITED, Outcome.LUCKY, Outcome.SAFE, Outcome.MISS, Outcome.SAFE, Outcome.SAFE))
                startMissedReplay()
            }
            Screen.S30 -> {
                if (session != null) endGame("goto S30")
                go(Screen.S30, "goto")
                WindowCtl.open("goto")
            }
            Screen.S32 -> { go(Screen.S32, "goto"); WindowCtl.open("goto") }
            Screen.S40 -> { go(Screen.S40, "goto"); WindowCtl.open("goto") }
            Screen.S41 -> { go(Screen.S41, "goto"); WindowCtl.open("goto") }
            Screen.S42 -> { go(Screen.S42, "goto"); WindowCtl.open("goto") }
            Screen.S43 -> { go(Screen.S43, "goto"); WindowCtl.open("goto") }
            Screen.S50 -> {
                if (run == null) DebugCommands.startRoundFromArgs(args, "goto S50")
                pause("debug")
            }
            Screen.S53 -> { go(Screen.S53, "goto"); WindowCtl.open("goto") }
            Screen.S54 -> {
                run = null
                go(Screen.S54, "goto")
            }
            Screen.S00 -> return "S00 is the launch: adb shell am force-stop + am start"
        }
        return "goto ${s.code} -> ${state.value.screen.code}"
    }

    internal fun debugSeedScores() {
        var p = progress
        p = p.copy(levels = mapOf(1 to io.embodit.shouldercheck.domain.LevelBest(1320, 3, 6), 2 to io.embodit.shouldercheck.domain.LevelBest(1080, 2, 4), 3 to io.embodit.shouldercheck.domain.LevelBest(760, 2, 2)), unlocked = 4, dailyDate = today(), dailyBest = 1240, dailyStreak = 3, gamesPlayed = maxOf(p.gamesPlayed, 12))
        saveProgress(p)
        ScLog.i("SCORES seeded")
    }

    /**
     * Debug `goto S20` / `S21` / `game outcomes=…`: plays a whole game offline through the REAL check judge and scorer —
     * each round of the plan gets scripted head samples (mirror, shoulder unless the outcome skips it) and a 「走」 in its
     * gap (or in the danger for a close call, or none for too late) — then ends the game the normal way.
     */
    internal fun debugPlayScripted(level: Int, outcomes: List<Outcome>, isDaily: Boolean = false) {
        startGame(level, isDaily, "debug scripted", seed = 4242L)
        val p = plan ?: return
        val s = session ?: return
        for (i in 0 until GamePlan.ROUNDS) {
            val round = p.round(i)
            val want = outcomes.getOrElse(i) { Outcome.SAFE }
            val j = CheckJudge(round, JudgeMode.HEAD, thresholds.copy())
            val mid = (j.band.start + j.band.endInclusive) / 2
            val gap = round.firstGap()
            val tGo: Double? = when (want) {
                Outcome.TIMEOUT -> null
                Outcome.MISS -> if (!round.freeAt(GO_LIT + 0.4)) GO_LIT + 0.4 else (1..100).map { GO_LIT + it * 0.1 }.firstOrNull { !round.freeAt(it) }
                else -> gap?.first?.plus(0.4)
            }
            val end = tGo ?: round.window
            var t = 0.0
            while (t < end) {
                val yaw = when {
                    want == Outcome.LUCKY -> if (t in (end - 1.6)..(end - 1.2)) mid else 0.0
                    t in (end - 1.6)..(end - 1.2) -> mid
                    t in (end - 1.0)..(end - 0.6) -> round.dir.sign * (j.threshold + 6.0)
                    else -> 0.0
                }
                j.update(CheckSample(t, yaw, 0.0, Vec3.ZERO, null))
                t += 1.0 / 60
            }
            val res = Scoring.judge(round, i, j, tGo, s.combo)
            s.record(res)
            roundTraces[i] = round to j.trace.toList()
        }
        ScLog.i("DEBUG scripted game results=${s.results.map { it.outcome.code }}")
        finishGame()
    }

    fun dump(): String {
        val s = state.value
        val r = run
        return buildString {
            append("screen=").append(s.screen.code).append(" page=").append(s.page).append(" window=").append(WindowCtl.state)
            append(" eyeOff=").append(s.eyeOff).append(" lost=").append(s.lost).append(" traffic=").append(s.traffic)
            append(" lang=").append(s.language).append(" muted=").append(s.muted).append(" vol=").append(progress.settings.volume).append(" comfort=").append(progress.settings.comfort)
            append(" T=").append(progress.thresholds)
            append(" game=").append(session?.let { "L${it.level.n}${if (it.daily) "d" else ""} ${it.index}/8 total=${it.total} combo=${it.combo}" })
            if (r != null) append(String.format(Locale.US, " round=%d %s %s t=%.2f tGo=%s mode=%s mirror=%s shoulder=%s", r.index + 1, r.round.situation.id, r.round.kind, r.t, r.tGo, r.checks.mode, r.checks.mirrorFresh(r.t), r.checks.shoulderFresh(r.t)))
            append(" head=").append(effHead?.let { String.format(Locale.US, "%.1f/%.1f", it.yaw, it.pitch) })
            append(" gaze=").append(effGaze?.let { String.format(Locale.US, "%.1f/%.1f", Angles.yawOf(it.dir), Angles.pitchOf(it.dir)) })
            append(" hold=").append(dbgHold).append(" unlocked=").append(progress.unlocked).append(" games=").append(progress.gamesPlayed)
        }
    }

    const val HORN_DELAY = 0.25
    const val FREEZE = 0.3
    const val POP_CLEAR_MS = 1200L
    const val POP_OTHER_MS = 1500L
}
