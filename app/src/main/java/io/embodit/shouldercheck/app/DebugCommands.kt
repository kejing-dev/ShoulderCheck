package io.embodit.shouldercheck.app

import io.embodit.shouldercheck.domain.ActorKind
import io.embodit.shouldercheck.domain.Language
import io.embodit.shouldercheck.domain.Levels
import io.embodit.shouldercheck.domain.Outcome
import io.embodit.shouldercheck.domain.RoundGenerator
import io.embodit.shouldercheck.domain.Situation
import io.embodit.shouldercheck.domain.Target
import io.embodit.shouldercheck.domain.TrafficSide
import io.embodit.shouldercheck.platform.ScLog
import io.embodit.shouldercheck.platform.tracking.TrackerForce
import io.embodit.shouldercheck.ui.components.DebugProbe
import io.embodit.shouldercheck.ui.window.WindowCtl

/**
 * Debug broadcast hooks (design `qaHooks`), debug builds only (the receiver lives in src/debug):
 *
 *   adb shell "am broadcast -a io.embodit.shouldercheck.DEBUG -p io.embodit.shouldercheck --es cmd 'goto S11 scene=LC side=L'"
 *
 * Rule: a hook changes the INPUT the frame system reads (head / gaze overrides, the A / Y keys, tracking lost) or sends
 * the [Ev] a button / 3D tap sends; 3D taps (`tap btn:go`) replay a hit on the real entity through the board-input
 * listener. `goto` reaches a state through its normal entry path. Full list: AGENTS.md 「Debug broadcast」.
 */
object DebugCommands {
    private val c = AppController

    /** The scene registers its dump (`panels`, `pose`, `scene`, `instances`). */
    var sceneDump: ((String) -> String)? = null

    /** The screen registers the 3D tap replay: real entity → resolveUp → the board-input listener. */
    var tapReplay: ((Target, String) -> String)? = null

    fun handle(name: String, args: Map<String, String>, positional: List<String>): String {
        if (name !in setOf("state", "dump", "hold", "panels", "pose", "sceneinfo", "head", "gaze")) c.dbgHold = false
        return run(name, args, positional)
    }

    /** Called by the controller after each frame (nothing pending yet; kept for scripted inputs). */
    fun afterFrame() = Unit

    /** `scene=LC|PO|MG|DR side=L|R hazard=none|car|suv|ebike|bike|moto z=<rear m> v=<m/s> trap=0|1 level=n`. */
    fun startRoundFromArgs(args: Map<String, String>, why: String) {
        val traffic = c.progress.settings.traffic
        val sit = Situation.parse(args["scene"] ?: "LC", args["side"]) ?: Situation.LANE_LEFT
        val level = Levels.of(args["level"]?.toIntOrNull() ?: c.state.value.level.coerceAtLeast(1))
        val kind = args["hazard"]?.takeIf { it != "none" }?.let { ActorKind.parse(it) }
        val trap = args["trap"] == "1"
        val z = args["z"]?.toDoubleOrNull() ?: if (trap) 3.6 else 18.0
        val v = args["v"]?.toDoubleOrNull() ?: if (trap) 0.0 else 5.0
        val round =
            if (kind == null && args.containsKey("hazard")) RoundGenerator.debugRound(level, sit, traffic, null, 0.0, 0.0, false)
            else if (kind != null) RoundGenerator.debugRound(level, sit, traffic, kind, z, v, trap)
            else RoundGenerator.debugRound(level, sit, traffic, null, 0.0, 0.0, false)
        c.startDebugRound(round, why)
    }

    private fun run(name: String, args: Map<String, String>, pos: List<String>): String {
        when (name) {
            "state", "dump" -> return c.dump()
            "panels", "pose" -> return sceneDump?.invoke(name) ?: "no scene"
            "sceneinfo" -> return sceneDump?.invoke(pos.firstOrNull() ?: args["sceneinfo"] ?: "scene") ?: "no scene"
            "hold" -> {
                c.dbgHold = pos.firstOrNull() != "off" && args["hold"] != "0"
                return "hold=${c.dbgHold}"
            }
            "goto" -> {
                val s = Screen.parse(pos.firstOrNull() ?: args["goto"] ?: return "goto <S>") ?: return "unknown state"
                if (args.containsKey("eye")) c.dbgEyeOff = args["eye"] == "off"
                return c.debugGoto(s, args)
            }
            "level" -> {
                val n = (args["level"] ?: pos.firstOrNull())?.toIntOrNull() ?: return "level=<n>"
                c.onEvent(Ev.Start(n))
                return "start level $n"
            }
            "daily" -> {
                c.onEvent(Ev.Daily)
                return "daily"
            }
            "seed" -> {
                val seed = (args["seed"] ?: pos.firstOrNull())?.toLongOrNull() ?: return "seed=<n> [round=<n>] [level=<n>]"
                val lv = args["level"]?.toIntOrNull() ?: c.state.value.practiceLevel
                c.startGame(lv, args["daily"] == "1", "debug seed", seed)
                val r = args["round"]?.toIntOrNull()
                return "game seed=$seed level=$lv${if (r != null) " (round $r: play through, rounds are seeded)" else ""}"
            }
            "scene" -> {
                startRoundFromArgs(args, "debug scene")
                return "scene ${args["scene"]} hazard=${args["hazard"]}"
            }
            "hazard" -> {
                startRoundFromArgs(args + ("scene" to (args["scene"] ?: "LC")), "debug hazard")
                return "hazard ${args["hazard"]}"
            }
            "gaze" -> {
                val v = args["gaze"] ?: pos.firstOrNull() ?: return "gaze=<yaw>,<pitch>|off"
                c.dbgGaze = if (v == "off") null else v.split(",").let { p -> (p.getOrNull(0)?.toDoubleOrNull() ?: 0.0) to (p.getOrNull(1)?.toDoubleOrNull() ?: 0.0) }
                return "gaze=${c.dbgGaze}"
            }
            "eye" -> {
                c.dbgEyeOff = (args["eye"] ?: pos.firstOrNull()) == "off"
                TrackerForce.eyeOff = c.dbgEyeOff
                return "eyeOff=${c.dbgEyeOff}"
            }
            "head" -> {
                val v = args["head"] ?: pos.firstOrNull() ?: return "head=<yaw>|off"
                c.dbgHeadYaw = if (v == "off") null else v.toDoubleOrNull()
                c.dbgHeadPitch = args["pitch"]?.toDoubleOrNull() ?: 0.0
                return "head=${c.dbgHeadYaw}/${c.dbgHeadPitch}"
            }
            "tap" -> {
                val what = pos.firstOrNull() ?: args["tap"] ?: return "tap <btn:go|door:handle|pause:chip|ask:on|ask:off|probeName>"
                val t = Target.parse(what)
                if (t != null) return tapReplay?.invoke(t, args["src"] ?: "controller") ?: "no scene"
                return if (DebugProbe.press(what)) "pressed $what" else "no button '$what' (have: ${DebugProbe.names().joinToString(",")})"
            }
            "exec", "a" -> {
                c.dbgPendingA++
                return "A"
            }
            "key" -> {
                return when ((pos.firstOrNull() ?: args["key"])?.lowercase()) {
                    "a" -> { c.dbgPendingA++; "A" }
                    "y" -> { c.dbgPendingY++; "Y" }
                    "b", "back" -> { c.onEvent(Ev.Back("debug")); "BACK" }
                    else -> "key a|y|b"
                }
            }
            "result" -> {
                val o = Outcome.parse(args["outcome"] ?: pos.firstOrNull() ?: "safe") ?: return "outcome=safe|waited|lucky|miss|timeout"
                c.debugResult(o, args["combo"]?.toIntOrNull() ?: 0)
                return "result $o"
            }
            "game" -> {
                val list = (args["outcomes"] ?: "safe,safe,waited,lucky,safe,miss,safe,timeout").split(",").mapNotNull { Outcome.parse(it.trim()) }
                c.debugPlayScripted(args["level"]?.toIntOrNull() ?: 1, list, args["daily"] == "1")
                return "game ${list.map { it.code }}"
            }
            "seed-scores" -> {
                c.debugSeedScores()
                return "seeded"
            }
            "lost" -> {
                c.dbgLost = (args["lost"] ?: pos.firstOrNull()) == "1"
                return "lost=${c.dbgLost}"
            }
            "comfort" -> {
                c.onEvent(Ev.SetComfort((args["comfort"] ?: pos.firstOrNull()) == "1"))
                return "comfort"
            }
            "lang" -> {
                val l = when ((args["lang"] ?: pos.firstOrNull())?.lowercase()) {
                    "en" -> Language.EN
                    "zh" -> Language.ZH
                    else -> Language.SYSTEM
                }
                c.onEvent(Ev.SetLanguage(l))
                return "lang=$l"
            }
            "traffic" -> {
                c.onEvent(Ev.SetTraffic(if ((args["traffic"] ?: pos.firstOrNull())?.uppercase() == "L") TrafficSide.LEFT_HAND else TrafficSide.RIGHT_HAND))
                return "traffic"
            }
            "window" -> {
                when (pos.firstOrNull() ?: args["window"]) {
                    "open" -> WindowCtl.open("debug")
                    "close" -> WindowCtl.close("debug")
                    "recall" -> WindowCtl.recall()
                }
                return "window=${WindowCtl.state}"
            }
            "mirrorHz" -> {
                c.mirrorHz = (args["mirrorHz"] ?: pos.firstOrNull())?.toDoubleOrNull()?.coerceIn(1.0, 60.0) ?: 20.0
                return "mirrorHz=${c.mirrorHz}"
            }
            "blocked" -> {
                TrackerForce.blocked = (args["blocked"] ?: pos.firstOrNull())?.takeIf { it != "off" && it != "0" }
                return "blocked=${TrackerForce.blocked}"
            }
            "probes" -> return DebugProbe.names().joinToString(",")
        }
        ScLog.i("DEBUG unknown command $name")
        return "unknown command $name"
    }
}
