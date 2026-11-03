package io.embodit.shouldercheck.app

import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Dir
import io.embodit.shouldercheck.domain.Hand
import io.embodit.shouldercheck.domain.HazardSpec
import io.embodit.shouldercheck.domain.JudgeMode
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Lessons
import io.embodit.shouldercheck.domain.Signal
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.domain.VolumeLevel
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.ui.components.DebugProbe

/**
 * Debug broadcast hooks (design `qaHooks`), debug builds only (the receiver lives in src/debug):
 *
 *   adb shell "am broadcast -a io.embodit.shouldercheck.DEBUG -p io.embodit.shouldercheck --es cmd 'goto S06 scene=lane-left done=I,L'"
 *
 * Rule: a hook changes the INPUT the frame system reads (head / gaze / door / stick / block-reason overrides, A / B
 * presses) or sends the [Ev] a panel button sends; it never writes a state. `goto` reaches a state through its normal
 * entry path (lesson, scene, set, scripted samples into the real judge, `blocked=` for S19); `goto S00` only answers
 * with the relaunch command (S00 is entered by launching the app). Full list: AGENTS.md "Debug broadcast".
 */
object DebugCommands {
    private val c = AppController

    /** The scene registers its dump (`panels`, `pose`, `scene`). */
    var sceneDump: ((String) -> String)? = null

    fun handle(name: String, args: Map<String, String>, positional: List<String>): String {
        // any command releases a previous `goto` hold unless it sets one again
        val before = c.dbgHold
        if (name != "state" && name != "hold" && name != "panels" && name != "pose" && name != "sceneinfo" && name != "tap") c.dbgHold = false
        val out = run(name, args, positional)
        if (c.dbgHold != before) ScLog.i("DEBUG hold=${c.dbgHold}")
        return out
    }

    private fun parseHazard(args: Map<String, String>, defaultSide: Dir): HazardSpec? {
        val h = args["hazard"] ?: return null
        if (h == "none") return null
        val kind = ActorKind.parse(h) ?: return null
        val side = when (args["side"]?.uppercase()) {
            "L" -> Dir.LEFT
            "R" -> Dir.RIGHT
            else -> defaultSide
        }
        return HazardSpec(kind, side, args["z"]?.toDoubleOrNull() ?: 3.0, args["v"]?.toDoubleOrNull() ?: 0.0)
    }

    private fun situationOf(args: Map<String, String>, fallback: Situation): Situation =
        args["scene"]?.let { Situation.byId(it) } ?: args["lesson"]?.toIntOrNull()?.let { Lessons.byN(it).situation } ?: fallback

    private fun startScene(args: Map<String, String>, fallback: Situation, why: String): QuestionRun {
        val sideArg = args["side"]?.uppercase()
        val fb = if (args["scene"] == null && sideArg == "R" && fallback == Situation.LANE_LEFT) Situation.LANE_RIGHT else fallback
        val sit = situationOf(args, fb)
        val hazard = parseHazard(args, sit.dir(c.progress.settings.traffic))
        return c.startDebugScene(sit, hazard, why)
    }

    /** Leaves S05 through its normal 2 s exit, without waiting (the run clock reaches 0 on the next frame). */
    private fun skipReady(r: QuestionRun) {
        r.t = -0.0001
    }

    private fun run(name: String, args: Map<String, String>, pos: List<String>): String {
        when (name) {
            "state", "dump" -> return c.dump()
            "panels", "pose" -> return sceneDump?.invoke(name) ?: "no scene"
            "sceneinfo" -> return sceneDump?.invoke(pos.firstOrNull() ?: args["sceneinfo"] ?: "scene") ?: "no scene"
            "lineup" -> {
                val v = pos.firstOrNull() ?: args["lineup"] ?: "front"
                c.dbgLineup = if (v == "off") null else v
                return "lineup=${c.dbgLineup}"
            }
            "hold" -> {
                c.dbgHold = (args["hold"] ?: pos.firstOrNull() ?: "1") != "0"
                return "hold=${c.dbgHold}"
            }
            "quit" -> {
                c.onEvent(Ev.ConfirmExit)
                return "quitting"
            }
            "testtex" -> {
                c.testTex = (args["testtex"] ?: pos.firstOrNull() ?: "1") != "0"
                return "testtex=${c.testTex}"
            }
            "tap" -> {
                val target = pos.firstOrNull() ?: args["tap"] ?: return "tap <name>; known=${DebugProbe.names()}"
                return if (DebugProbe.press(target)) "tapped $target" else "no button '$target'; known=${DebugProbe.names()}"
            }
            "sit" -> {
                c.onEvent(Ev.Sit)
                return "sit"
            }
            "goto" -> return goto(pos.firstOrNull() ?: args["goto"] ?: return "goto <S>", args)
            "lesson" -> {
                val n = args["lesson"]?.toIntOrNull() ?: return "lesson=<1..8>"
                c.onEvent(Ev.ChooseLesson(n))
                args["step"]?.toIntOrNull()?.let { c.debugStartDemo(n, it) }
                return "lesson $n"
            }
            "scene" -> {
                val r = startScene(args, Situation.LANE_LEFT, "debug scene")
                return "scene ${r.scenario.situation.id} hazard=${r.scenario.hazard?.kind}"
            }
            "hazard" -> {
                val sit = c.run?.scenario?.situation ?: Situation.LANE_LEFT
                val r = c.startDebugScene(sit, parseHazard(args, sit.dir(c.progress.settings.traffic)), "debug hazard")
                c.dbgHold = true
                return "hazard ${r.scenario.hazard?.kind} rearZ=${args["z"]} v=${args["v"]} (S05 held; `hold 0` to run)"
            }
            "gaze" -> {
                val v = args["gaze"] ?: return "gaze=<yaw>,<pitch>|off"
                c.dbgGaze = if (v == "off") null else v.split(",").let { (it[0].toDouble()) to (it.getOrNull(1)?.toDouble() ?: 0.0) }
                return "gaze=${c.dbgGaze}"
            }
            "eye" -> {
                val off = (args["eye"] ?: "off") == "off"
                io.embodit.shouldercheck.platform.tracking.TrackerForce.eyeOff = off
                if (off) c.dbgGaze = null
                return "eye off=$off"
            }
            "head" -> {
                val v = args["head"] ?: return "head=<yaw>|off"
                c.dbgHeadYaw = if (v == "off") null else v.toDouble()
                c.dbgHeadPitch = args["pitch"]?.toDoubleOrNull() ?: 0.0
                return "head=${c.dbgHeadYaw} pitch=${c.dbgHeadPitch}"
            }
            "range" -> {
                val v = (args["range"] ?: return "range=<L>,<R>").split(",")
                c.onEvent(Ev.RangeMeasured(v[0].toDouble(), v.getOrNull(1)?.toDouble() ?: v[0].toDouble()))
                return "range ${c.progress.range}"
            }
            "mirrorAim" -> {
                val v = (args["mirrorAim"] ?: return "mirrorAim=L|R,<deg>").split(",")
                val dir = if (v[0].uppercase() == "R") Dir.RIGHT else Dir.LEFT
                c.setAim(dir, v.getOrNull(1)?.toDouble() ?: 5.0)
                args["z"]?.let { z ->
                    val kind = ActorKind.parse(args["hazard"] ?: "car") ?: ActorKind.SEDAN
                    c.startDebugScene(c.run?.scenario?.situation ?: Situation.LANE_LEFT, HazardSpec(kind, dir, z.toDouble(), args["v"]?.toDoubleOrNull() ?: 0.0), "debug mirrorAim")
                    c.dbgHold = true
                }
                return "aims=${c.aims()}"
            }
            "signal" -> {
                val sig = when ((args["signal"] ?: "off").uppercase()) {
                    "L" -> Signal.LEFT
                    "R" -> Signal.RIGHT
                    else -> Signal.OFF
                }
                c.setSignal(sig)
                return "signal=${c.run?.signal}"
            }
            "stick" -> {
                // one stick push (left stick; `hand=R` = right stick) through the same edge detector as the controller
                val dir = if ((args["stick"] ?: "L").uppercase() == "R") 1f else -1f
                val hand = if ((args["hand"] ?: "L").uppercase() == "R") Hand.RIGHT else Hand.LEFT
                c.dbgStick = hand to dir
                return "stick $hand $dir"
            }
            "exec", "a" -> {
                c.dbgPendingA++
                return "A press queued"
            }
            "b" -> {
                c.dbgPendingB++
                return "B press queued"
            }
            "door" -> {
                val d = args["door"] ?: "right"
                if (d == "off") {
                    c.dbgDoor = null
                    return "door off"
                }
                val hand = if (d == "left") Hand.LEFT else Hand.RIGHT
                c.dbgDoor = hand to (args["push"]?.toDoubleOrNull() ?: 0.10)
                return "door hand=$hand push=${c.dbgDoor?.second}"
            }
            "result" -> {
                val outcome = args["outcome"] ?: "clear"
                val mode = if (args["mode"] == "eye") JudgeMode.EYE else JudgeMode.HEAD
                val (sc, judge) = c.scriptedResult(outcome, mode)
                c.finishScripted(sc, judge, "debug result $outcome")
                return "result ${judge.result().outcome} mode=$mode"
            }
            "replay" -> {
                c.debugReplay(args["t"]?.toDoubleOrNull())
                if (args["t"] != null) c.dbgHold = true
                return "replay t=${args["t"]}"
            }
            "seed" -> {
                c.debugStartSet(args["seed"]?.toLongOrNull() ?: 42L, args["q"]?.toIntOrNull() ?: 1)
                return "set seed=${args["seed"]} q=${args["q"]}"
            }
            "seed-scores" -> {
                c.debugSeedScores()
                return "seeded"
            }
            "blocked" -> {
                val v = args["blocked"] ?: pos.firstOrNull() ?: "not-in-full-space"
                io.embodit.shouldercheck.platform.tracking.TrackerForce.blocked = if (v == "off") null else v
                return "blocked=${io.embodit.shouldercheck.platform.tracking.TrackerForce.blocked} (S19 after 2 s; off = S01 again)"
            }
            "lost" -> {
                io.embodit.shouldercheck.platform.tracking.TrackerForce.headLost = (args["lost"] ?: "1") != "0"
                return "lost=${io.embodit.shouldercheck.platform.tracking.TrackerForce.headLost}"
            }
            "speed" -> {
                c.speed = args["speed"]?.toDoubleOrNull()?.coerceIn(0.05, 8.0) ?: 1.0
                return "speed=${c.speed}"
            }
            "lang" -> {
                val l = when (args["lang"]) {
                    "zh" -> Language.ZH
                    "en" -> Language.EN
                    else -> Language.SYSTEM
                }
                c.onEvent(Ev.SetLanguage(l))
                return "lang=$l"
            }
            "traffic" -> {
                c.onEvent(Ev.SetTraffic(if (args["traffic"] == "left") TrafficSide.LEFT_HAND else TrafficSide.RIGHT_HAND))
                return "traffic=${c.progress.settings.traffic}"
            }
            "mute" -> {
                c.onEvent(Ev.ToggleMute)
                return "muted=${c.progress.settings.muted}"
            }
            "volume" -> {
                c.onEvent(Ev.SetVolume(VolumeLevel.valueOf((args["volume"] ?: "mid").uppercase())))
                return "volume=${c.progress.settings.volume}"
            }
            "mirrorHz" -> {
                c.mirrorHz = args["mirrorHz"]?.toDoubleOrNull()?.coerceIn(2.0, 60.0) ?: 20.0
                return "mirrorHz=${c.mirrorHz}"
            }
            "pause" -> {
                c.onEvent(Ev.Pause)
                return "pause"
            }
            "ev" -> return "use tap <button>"
        }
        return "unknown command '$name'"
    }

    private fun goto(code: String, args: Map<String, String>): String {
        val base = Base.parse(code) ?: return "unknown state $code"
        if (args["seed-scores"] == "1") c.debugSeedScores()
        when (base) {
            // S00 is only entered by launching the app: no shortcut writes it
            Base.S00 -> return "S00 = launch only: adb shell am force-stop io.embodit.shouldercheck; am start -n io.embodit.shouldercheck/.platform.LaunchActivity (S00 lasts until the scene is built)"
            Base.S01 -> c.onEvent(Ev.Recentre)
            Base.S01B -> c.debugEnterRange()
            Base.S02 -> c.onEvent(Ev.Home)
            Base.S03 -> c.onEvent(Ev.OpenLessons())
            Base.S04 -> {
                c.debugStartDemo(args["lesson"]?.toIntOrNull() ?: 5, args["step"]?.toIntOrNull())
                if (args["step"] != null) c.dbgHold = true
            }
            Base.S05 -> {
                startScene(args, Situation.LANE_LEFT, "debug goto S05")
                c.dbgHold = true
            }
            Base.S06 -> {
                val r = startScene(args, Situation.LANE_LEFT, "debug goto S06")
                skipReady(r)
                pendingAfterReady = { run ->
                    args["done"]?.let { d ->
                        DebugScripts.inject(run, d.split(",").map { it.trim().uppercase() }.toSet(), run.judge.mode == JudgeMode.EYE)
                        c.dbgHold = true
                    }
                }
            }
            Base.S07 -> {
                val r = startScene(args, Situation.LANE_LEFT, "debug goto S07")
                skipReady(r)
                val push = args["push"]?.toDoubleOrNull()
                val t = args["t"]?.toDoubleOrNull()
                pendingAfterReady = { run ->
                    if (run.scenario.situation.action == io.embodit.shouldercheck.domain.ActionKind.DOOR) {
                        c.dbgDoor = run.scenario.cabin.farHand to (push ?: 0.10)
                        // the door opens 8° → 60° in 0.5 s after crossing 12°: hold once it is open
                        pendingHoldAt = t ?: 0.8
                    } else {
                        c.dbgPendingA++
                        if (t != null) pendingHoldAt = t
                    }
                }
            }
            Base.S08 -> {
                val r = startScene(args.filterKeys { it != "hazard" }, Situation.LANE_LEFT, "debug goto S08")
                skipReady(r)
                pendingAfterReady = { run ->
                    DebugScripts.cleanRun(run, run.judge.mode == JudgeMode.EYE)
                    c.dbgHold = true
                }
            }
            Base.S09 -> {
                val a = HashMap(args)
                if (a["hazard"] == null) a["hazard"] = "ebike"
                if (a["z"] == null) a["z"] = "3"
                if (a["v"] == null) a["v"] = "3"
                val r = startScene(a, Situation.LANE_LEFT, "debug goto S09")
                skipReady(r)
                pendingAfterReady = { _ ->
                    c.dbgPendingA++
                    pendingHoldInS09 = true
                }
            }
            Base.S10 -> return run("result", args, emptyList())
            Base.S11 -> {
                run("result", args, emptyList())
                c.onEvent(Ev.Replay)
                args["t"]?.toDoubleOrNull()?.let {
                    c.debugReplay(it)
                    c.dbgHold = true
                }
            }
            Base.S12 -> c.onEvent(Ev.OpenScores)
            Base.S13 -> c.debugStartSet(args["seed"]?.toLongOrNull() ?: 42L, args["q"]?.toIntOrNull() ?: 1)
            Base.S14 -> c.debugFinishSet(args["seed"]?.toLongOrNull() ?: 42L)
            Base.S15 -> {
                if (c.state.value.base == Base.S15) c.onEvent(Ev.Back)
                c.onEvent(Ev.OpenSettings)
            }
            Base.S16 -> {
                val r = startScene(args, Situation.LANE_LEFT, "debug goto S16")
                skipReady(r)
                pendingAfterReady = { _ -> c.onEvent(Ev.Pause) }
            }
            // S19 through the real path: the head tracker reports a block reason; after 2 s the controller enters S19
            Base.S19 -> io.embodit.shouldercheck.platform.tracking.TrackerForce.blocked = args["blocked"] ?: "not-in-full-space"
        }
        return "goto ${base.code} -> ${c.state.value.base.code}"
    }

    /** Work that runs on the first frame after S05 has passed (S06 entered through the normal exit). */
    var pendingAfterReady: ((QuestionRun) -> Unit)? = null
    var pendingHoldAt: Double? = null
    var pendingHoldInS09 = false

    /** Called by the controller every frame (after the state step). */
    fun afterFrame() {
        val r = c.run ?: return
        val base = c.state.value.base
        if (base == Base.S06 && pendingAfterReady != null) {
            val f = pendingAfterReady
            pendingAfterReady = null
            f?.invoke(r)
        }
        val at = pendingHoldAt
        if (at != null && base == Base.S07 && r.live != null) {
            val e = execTimeOf(r) ?: return
            if (r.t >= e + at) {
                c.dbgHold = true
                pendingHoldAt = null
                ScLog.i("DEBUG hold S07 at exec+$at")
            }
        }
        if (pendingHoldInS09 && base == Base.S09) {
            pendingHoldInS09 = false
            c.dbgHold = true
        }
    }

    private val execAt = HashMap<QuestionRun, Double>()

    private fun execTimeOf(r: QuestionRun): Double? {
        execAt[r]?.let { return it }
        if (c.state.value.base == Base.S07) {
            execAt.clear()
            execAt[r] = r.t
            return r.t
        }
        return null
    }
}
