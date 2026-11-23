package io.embodit.shouldercheck.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import kotlin.math.pow
import kotlin.random.Random

/**
 * Sounds (design `audio`): SoundPool, event sounds only — no engine / road / wind bed (B24). Sources are listed in the
 * factory's apps/shoulder-check/build/sound-sources.md (all CC0 recordings; v0.2.2: car horn + tyre skid from freesound). Master volume
 * = 低 / 中 / 高 × mute. One instance per event at a time (a new one stops the last, D51); real-world sounds get ±5 %
 * pitch; the 「叮」 of a clear round rises a whole tone per combo step (max 5).
 *
 * Main thread only. [release] frees the pool (exit, D26).
 */
class Audio(context: Context) {
    private val app = context.applicationContext
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attributes).build()
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val streams = HashMap<String, Int>()
    private val main = Handler(Looper.getMainLooper())
    private var gain = 0.9f
    private var indicatorOn = false
    private var released = false

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) loaded += id
            if (loaded.size == ids.size) ScLog.i("AUDIO loaded ${loaded.size}/${ids.size}")
        }
        for (name in NAMES) {
            runCatching { app.assets.openFd("audio/$name.wav").use { fd -> ids[name] = pool.load(fd, 1) } }.onFailure { ScLog.e("AUDIO load $name failed: $it") }
        }
    }

    /** Effective master gain (0 = muted). */
    fun setGain(g: Float) {
        gain = g.coerceIn(0f, 1f)
    }

    /** Turn signal relay ticking (−18 dB, 1.5 Hz) while a lane move is being prepared. */
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
                cue(INDICATOR, level = INDICATOR_LEVEL)
                main.postDelayed(this, INDICATOR_MS)
            }
        }

    /** The 「叮」 of a clear round: a whole tone higher per combo step (1 … 5). */
    fun ding(combo: Int) {
        val step = (combo - 1).coerceIn(0, 4)
        play(DING, 0f, 1f, 2.0.pow(2.0 * step / 12.0).toFloat())
    }

    /** One cue; [pan] −1 = left … +1 = right. Real-world sounds get ±5 % pitch; [VARIANTS] pick a random take. */
    fun cue(name: String, pan: Float = 0f, level: Float = 1f) {
        val take = VARIANTS[name]?.let { if (Random.nextBoolean()) it else name } ?: name
        val rate = if (name in REAL) 1f + (Random.nextFloat() - 0.5f) * 0.10f else 1f
        play(take, pan, level, rate, slot = name)
    }

    private fun play(name: String, pan: Float, level: Float, rate: Float, slot: String = name) {
        if (released) return
        val id = ids[name] ?: return
        if (gain <= 0f || id !in loaded) return
        streams[slot]?.let { pool.stop(it) }
        val p = pan.coerceIn(-1f, 1f)
        val l = gain * level * (if (p > 0) 1f - p * 0.7f else 1f)
        val r = gain * level * (if (p < 0) 1f + p * 0.7f else 1f)
        streams[slot] = pool.play(id, l, r, 2, 0, rate.coerceIn(0.5f, 2f))
    }

    fun stopAll() {
        indicator(false)
        for (s in streams.values) pool.stop(s)
        streams.clear()
    }

    fun release() {
        stopAll()
        released = true
        main.removeCallbacksAndMessages(null)
        pool.release()
        ScLog.i("AUDIO released")
    }

    companion object {
        const val INDICATOR = "indicator"
        const val TICK = "tick"
        const val DING = "ding"
        const val RECORD = "record"
        const val TIMEOUT = "timeout"
        const val LATCH = "door_latch"
        const val BELL = "bike_bell"
        /** Vehicle near miss (replaces the v0.2.1 synthesised UI alert). */
        const val HORN = "horn"
        /** Emergency stop / being run into: tyre skid. */
        const val SKID = "skid"
        private val VARIANTS = mapOf(HORN to "horn_1", SKID to "skid_1")
        private const val INDICATOR_MS = 667L
        private const val INDICATOR_LEVEL = 0.35f
        val NAMES = listOf(INDICATOR, TICK, DING, RECORD, TIMEOUT, LATCH, BELL, HORN, "horn_1", SKID, "skid_1")
        private val REAL = setOf(INDICATOR, LATCH, BELL, TICK, HORN, SKID)
    }
}
