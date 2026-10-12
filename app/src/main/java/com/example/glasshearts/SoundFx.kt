package com.example.glasshearts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class Fx { LUB, DUB, CHIME, WHOOSH, FIREWORK }

/**
 * Procedurally synthesised sound effects - no audio files. Samples are rendered once on a background thread
 * into small static AudioTracks (a few voices each, round-robin). Silent when the phone is on silent/vibrate.
 */
class SoundFx(context: Context) {
    private class Sample(val tracks: Array<AudioTrack>) {
        var next = 0
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val samples = HashMap<Fx, List<Sample>>()
    private val rnd = Random(11)

    @Volatile private var ready = false
    @Volatile var enabled = true

    init {
        Thread {
            try {
                build()
                ready = true
            } catch (e: Exception) {
                // no audio on this device: stay silent
            }
        }.start()
    }

    fun play(fx: Fx, volume: Float = 1f) {
        if (!ready || !enabled || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val variants = samples[fx] ?: return
        val s = variants[rnd.nextInt(variants.size)]
        val track = s.tracks[s.next]
        s.next = (s.next + 1) % s.tracks.size
        try {
            track.stop()
            track.reloadStaticData()
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
        } catch (e: IllegalStateException) {
            // track in a bad state: skip this sound
        }
    }

    fun release() {
        enabled = false
        for (list in samples.values) for (s in list) for (t in s.tracks) {
            try { t.release() } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------------ rendering
    private fun build() {
        samples[Fx.LUB] = listOf(sample(thump(95f, 42f, 16f, 0.28f, 0.95f)))
        samples[Fx.DUB] = listOf(sample(thump(120f, 55f, 22f, 0.22f, 0.65f)))
        samples[Fx.CHIME] = listOf(1046.5f, 1174.7f, 1318.5f, 1568f, 1760f).map { sample(bell(it)) }
        samples[Fx.WHOOSH] = listOf(sample(whoosh()))
        samples[Fx.FIREWORK] = listOf(sample(firework()))
    }

    private fun sample(data: ShortArray) = Sample(Array(2) { track(data) })

    private fun track(data: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        return t
    }

    private fun synth(seconds: Float, gen: (Float) -> Float): ShortArray {
        val n = (seconds * RATE).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = gen(i / RATE.toFloat()).coerceIn(-1f, 1f)
            out[i] = (v * 30000f).toInt().toShort()
        }
        return out
    }

    /** Soft low "thud" with a pitch drop; the 2nd harmonic keeps it audible on phone speakers. */
    private fun thump(f0: Float, f1: Float, decay: Float, dur: Float, amp: Float): ShortArray {
        var phase = 0.0
        return synth(dur) { t ->
            val f = f1 + (f0 - f1) * exp(-t * 28f)
            phase += 2.0 * PI * f / RATE
            val env = exp(-t * decay) * (1f - exp(-t * 600f))
            ((sin(phase) + 0.45 * sin(2.0 * phase)) * env * amp).toFloat()
        }
    }

    /** Glassy bell: inharmonic partials with different decay times. */
    private fun bell(f0: Float): ShortArray = synth(1.3f) { t ->
        val a = 1f - exp(-t * 900f)
        val w = 2f * PI.toFloat() * f0 * t
        val v = sin(w) * exp(-t * 3.2f) +
            0.50f * sin(w * 2f) * exp(-t * 4.5f) +
            0.30f * sin(w * 2.76f) * exp(-t * 6f) +
            0.12f * sin(w * 5.4f) * exp(-t * 10f)
        v * a * 0.35f
    }

    /** Rustle: low-passed noise whose cut-off swells and fades. */
    private fun whoosh(): ShortArray {
        val r = Random(3)
        var y1 = 0f
        var y2 = 0f
        val dur = 1.0f
        return synth(dur) { t ->
            val env = max(0f, sin(PI.toFloat() * t / dur)).pow(1.5f)
            val a = 0.04f + 0.45f * env
            val x = r.nextFloat() * 2f - 1f
            y1 += a * (x - y1)
            y2 += a * (y1 - y2)
            y2 * env * 3.2f
        }
    }

    /** Rising whistle, then a burst of noise with a low thump and a crackle of tiny sparks. */
    private fun firework(): ShortArray {
        val r = Random(5)
        val n = (1.5f * RATE).toInt()
        val buf = FloatArray(n)
        var phase = 0.0
        val whistle = (0.34f * RATE).toInt()
        for (i in 0 until whistle) {
            val t = i / RATE.toFloat()
            val f = 500f + 1500f * (t / 0.34f)
            phase += 2.0 * PI * f / RATE
            buf[i] += (sin(phase) * 0.18 * min(1f, t / 0.05f)).toFloat()
        }
        val burst = (0.36f * RATE).toInt()
        var lp = 0f
        for (i in burst until n) {
            val t = (i - burst) / RATE.toFloat()
            lp += 0.35f * ((r.nextFloat() * 2f - 1f) - lp)
            buf[i] += lp * exp(-t * 7f) * 1.1f + sin(2f * PI.toFloat() * 70f * t) * exp(-t * 14f) * 0.7f
        }
        repeat(45) {
            val start = burst + ((0.05f + r.nextFloat() * 0.9f) * RATE).toInt()
            val amp = 0.15f + r.nextFloat() * 0.25f
            val len = (0.012f * RATE).toInt()
            for (k in 0 until len) {
                if (start + k < n) buf[start + k] += (r.nextFloat() * 2f - 1f) * amp * (1f - k.toFloat() / len)
            }
        }
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = (buf[i].coerceIn(-1f, 1f) * 28000f).toInt().toShort()
        return out
    }

    private companion object {
        const val RATE = 22050
    }
}
