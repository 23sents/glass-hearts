package com.example.glasshearts

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens to the microphone (nothing is stored or sent anywhere) and classifies each ~32 ms frame:
 *  - clap: sudden loud broadband onset (high zero-crossing rate, little low-frequency energy)
 *  - blow: sustained turbulent low-frequency noise that is NOT periodic (voiced speech is periodic)
 * Thresholds were checked against synthetic blow / vowel / clap / hiss signals.
 */
class MicListener(private val engine: HeartEngine) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mic-listener").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        try { thread?.join(400) } catch (e: InterruptedException) { }
        thread = null
        engine.micBlow = 0f
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 4096)
            )
        } catch (e: Exception) {
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        val buf = ShortArray(FRAME)
        val x = FloatArray(FRAME)
        val lpA = 1f - exp(-2f * PI.toFloat() * 300f / RATE)
        var lp = 0f
        var avg = 0.01f
        var cooldown = 0
        var blowFrames = 0
        var blowSmooth = 0f
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, FRAME)
                if (n < 128) continue
                var sum2 = 0.0
                var lpE = 0.0
                var zc = 0
                var prev = 0f
                for (i in 0 until n) {
                    val v = buf[i] / 32768f
                    x[i] = v
                    sum2 += v * v
                    lp += lpA * (v - lp)
                    lpE += lp * lp
                    if (i > 0 && (v > 0f) != (prev > 0f)) zc++
                    prev = v
                }
                val rms = sqrt(sum2 / n).toFloat()
                val zcr = zc.toFloat() / n
                val low = (lpE / (sum2 + 1e-12)).toFloat()

                if (cooldown > 0) cooldown--
                val onset = rms > 0.12f && rms > avg * 4f
                if (onset && zcr > 0.18f && low < 0.35f && cooldown == 0) {
                    engine.onClap()
                    cooldown = 16
                    blowFrames = 0
                }
                avg += (rms - avg) * (if (rms > avg) 0.02f else 0.1f)

                val blowLike = rms > 0.04f && zcr < 0.25f && low > 0.30f && periodicity(x, n) < 0.45f
                blowFrames = if (blowLike) blowFrames + 1 else max(0, blowFrames - 2)
                val intensity = if (blowFrames >= 3 && cooldown < 8) ((rms - 0.03f) / 0.25f).coerceIn(0f, 1f) else 0f
                blowSmooth += (intensity - blowSmooth) * 0.5f
                engine.micBlow = blowSmooth
            }
        } catch (e: Exception) {
            // recording failed (permission revoked, device busy)
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
            engine.micBlow = 0f
        }
    }

    /** Peak of the normalised autocorrelation over pitch lags (80..400 Hz): ~0.8 for a voice, ~0.2 for wind. */
    private fun periodicity(x: FloatArray, n: Int): Float {
        var r0 = 1e-9f
        for (i in 0 until n) r0 += x[i] * x[i]
        var best = 0f
        for (lag in 40..200) {
            var r = 0f
            for (i in 0 until n - lag) r += x[i] * x[i + lag]
            best = max(best, r / r0)
        }
        return best
    }

    private companion object {
        const val RATE = 16000
        const val FRAME = 512
    }
}
