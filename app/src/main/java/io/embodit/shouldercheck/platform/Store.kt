package io.embodit.shouldercheck.platform

import android.content.Context
import io.embodit.shouldercheck.domain.Progress
import io.embodit.shouldercheck.domain.ProgressCodec

/**
 * Local storage (design `persistence`): one SharedPreferences string through [ProgressCodec] (language, traffic side,
 * switches, volume, mute, demos seen, lessons passed, the last 300 question records). No account, no cloud.
 */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): Progress = ProgressCodec.decode(prefs.getString(KEY, null))

    fun save(p: Progress) {
        prefs.edit().putString(KEY, ProgressCodec.encode(p)).apply()
    }

    /** The eye-tracking permission dialog is shown once, on the first S01 (later from S17 "去开启"). */
    var eyeAsked: Boolean
        get() = prefs.getBoolean(EYE_ASKED, false)
        set(v) {
            prefs.edit().putBoolean(EYE_ASKED, v).apply()
        }

    companion object {
        private const val FILE = "shouldercheck"
        private const val KEY = "progress"
        private const val EYE_ASKED = "eyeAsked"
    }
}
