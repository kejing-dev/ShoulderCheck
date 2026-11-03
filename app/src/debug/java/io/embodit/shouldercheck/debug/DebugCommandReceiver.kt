package io.embodit.shouldercheck.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.shouldercheck.platform.DebugHooks
import io.embodit.shouldercheck.platform.ScLog

/**
 * Debug builds only. Forwards adb broadcasts (action `io.embodit.shouldercheck.DEBUG`) to the running app through
 * [DebugHooks.handler]; design `qaHooks`:
 *
 *   adb shell "am broadcast -a io.embodit.shouldercheck.DEBUG -p io.embodit.shouldercheck --es cmd 'goto S06 scene=lane-left done=I,L'"
 *
 * The `cmd` extra is `<name> [positional…] [key=value…]`; extras other than `cmd` are passed as key/value too
 * (`--es z 12`). Commands of the design: goto <S>, lesson=n, scene=…, hazard=… side=L|R z=<m> v=<m/s>,
 * gaze=<yaw>,<pitch>, eye=off, head=<yaw>, range=<L>,<R>, mirrorAim=L|R,<deg>, signal=L|R|off, exec,
 * door=left|right push=<m>, result outcome=… mode=eye|head, replay t=<s>, seed=<n> q=<n>, seed-scores=1, lost=1,
 * speed=<x>. A bare `key=value` as the first token is also a command (`--es cmd "head=-64"` → name `head`).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return
        val first = tokens[0]
        val args = LinkedHashMap<String, String>()
        val positional = ArrayList<String>()
        val name: String
        if (first.contains('=')) {
            name = first.substringBefore('=')
            args[name] = first.substringAfter('=')
        } else {
            name = first
        }
        for (t in tokens.drop(1)) {
            if (t.contains('=')) args[t.substringBefore('=')] = t.substringAfter('=') else positional += t
        }
        intent.extras?.keySet()?.filter { it != "cmd" }?.forEach { k -> intent.getStringExtra(k)?.let { args[k] = it } }
        ScLog.i("CMD recv '$name' args=$args pos=$positional")
        // broadcasts arrive on the main thread, where the controller and the scene live
        val result = DebugHooks.handler?.let { h -> runCatching { h(name, args, positional) }.getOrElse { "error: $it" } } ?: "no handler (app not started)"
        ScLog.i("CMD $name -> $result")
    }
}
