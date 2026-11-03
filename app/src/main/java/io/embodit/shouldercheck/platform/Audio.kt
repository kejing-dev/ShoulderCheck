package io.embodit.shouldercheck.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper

/**
 * Sounds (design `audio`): native SoundPool, all files pre-rendered by tools/make_audio.py (peaks within −1 dBFS, cues
 * RMS −18 … −22, bed −30; B20). Master volume = the 低 / 中 / 高 setting × mute. The turn signal ticks at 1.5 Hz while
 * it is on; the engine / road bed loops quietly during practice; the near-miss cues are panned to the hazard's side.
 * There is no warning sound before a hazard (an e-bike is nearly silent: that is the lesson).
 *
 * Main thread only. [release] frees the pool (exit, D26).
 */
class Audio(context: Context) {
    private val app = context.applicationContext
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attributes).build()
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val main = Handler(Looper.getMainLooper())
    private var gain = 0.65f
    private var bedStream = 0
    private var bedWanted = false
    private var indicatorOn = false
    private var released = false

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) loaded += id
            if (loaded.size == ids.size) ScLog.i("AUDIO loaded ${loaded.size}/${ids.size}")
            if (bedWanted && bedStream == 0 && id == ids[BED]) startBed()
        }
        for (name in NAMES) {
            runCatching { app.assets.openFd("audio/$name.wav").use { fd -> ids[name] = pool.load(fd, 1) } }.onFailure { ScLog.e("AUDIO load $name failed: $it") }
        }
    }

    /** Effective master gain (0 = muted). */
    fun setGain(g: Float) {
        gain = g.coerceIn(0f, 1f)
        if (bedStream != 0) pool.setVolume(bedStream, gain * BED_GAIN, gain * BED_GAIN)
    }

    /** Engine idle / road bed on or off (practice states). */
    fun bed(on: Boolean) {
        bedWanted = on
        if (on && bedStream == 0) startBed()
        if (!on && bedStream != 0) {
            pool.stop(bedStream)
            bedStream = 0
        }
    }

    private fun startBed() {
        val id = ids[BED] ?: return
        if (id !in loaded) return
        bedStream = pool.play(id, gain * BED_GAIN, gain * BED_GAIN, 1, -1, 1f)
    }

    /** Turn signal ticking ("嗒—嗒", 1.5 Hz). */
    fun indicator(on: Boolean) {
        if (on == indicatorOn) return
        indicatorOn = on
        main.removeCallbacks(tick)
        if (on) main.post(tick)
    }

    private val tick =
        object : Runnable {
            override fun run() {
                if (!indicatorOn || released) return
                cue("indicator")
                main.postDelayed(this, INDICATOR_MS)
            }
        }

    /** One cue; [pan] −1 = left … +1 = right. */
    fun cue(name: String, pan: Float = 0f, level: Float = 1f) {
        if (released) return
        val id = ids[name] ?: return
        if (gain <= 0f) return
        val p = pan.coerceIn(-1f, 1f)
        val l = gain * level * (if (p > 0) 1f - p * 0.7f else 1f)
        val r = gain * level * (if (p < 0) 1f + p * 0.7f else 1f)
        pool.play(id, l, r, 2, 0, 1f)
    }

    fun stopAll() {
        indicator(false)
        bed(false)
        pool.autoPause()
        pool.autoResume()
    }

    fun release() {
        stopAll()
        released = true
        main.removeCallbacksAndMessages(null)
        pool.release()
        ScLog.i("AUDIO released")
    }

    companion object {
        const val BED = "idle_bed"
        private const val BED_GAIN = 0.6f
        private const val INDICATOR_MS = 667L
        val NAMES = listOf("indicator", "turn_tick", BED, "horn", "screech", "bike_bell", "ebike_brake", "door_open", "door_close", "success", "near_miss")
    }
}
