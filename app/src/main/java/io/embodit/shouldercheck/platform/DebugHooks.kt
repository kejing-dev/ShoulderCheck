package io.embodit.shouldercheck.platform

/**
 * Bridge from the debug-only broadcast receiver (src/debug) to the running app. The controller sets [handler] when it
 * starts; the receiver parses `--es cmd "<name> k=v k=v"` into a name + key/value map and calls it on the main
 * thread. Factory rule: a handler must turn every command into the SAME events / state transitions user input uses
 * (no shortcut that writes state directly). The returned string is logged as `CMD <name> -> <result>`.
 */
object DebugHooks {
    @Volatile var handler: ((name: String, args: Map<String, String>, positional: List<String>) -> String)? = null
}
