package io.embodit.shouldercheck.platform

import android.content.Context
import io.embodit.shouldercheck.domain.Progress
import io.embodit.shouldercheck.domain.ProgressCodec

/**
 * Local storage (design `persistence`): one SharedPreferences string through [ProgressCodec] (language, traffic side,
 * comfort mode, volume, mute, eye tracking asked, shoulder thresholds, best per level, unlock, today's run, last 50
 * games). No account, no cloud. v0.1.x lesson progress is not carried over (decode starts fresh).
 */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): Progress = ProgressCodec.decode(prefs.getString(KEY, null))

    fun save(p: Progress) {
        prefs.edit().putString(KEY, ProgressCodec.encode(p)).apply()
    }

    companion object {
        private const val FILE = "shouldercheck"
        private const val KEY = "progress"
    }
}
