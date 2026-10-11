package com.example.glasshearts

import kotlin.math.exp

/** Critically-tunable damped spring integrated with fixed sub-steps (stable for any frame time). */
class Spring(
    var value: Float = 0f,
    var target: Float = 0f,
    private val stiffness: Float = 140f,
    private val damping: Float = 12f,
) {
    var velocity = 0f

    fun update(dt: Float) {
        var remaining = dt
        while (remaining > 0f) {
            val h = minOf(remaining, STEP)
            velocity += (stiffness * (target - value) - damping * velocity) * h
            value += velocity * h
            remaining -= h
        }
    }

    private companion object {
        const val STEP = 1f / 240f
    }
}

/** A "lub-dub" cardiac rhythm built from two gaussian pulses per period. */
object Heartbeat {
    const val PERIOD = 1.4f
    const val FIRST_BEAT = 0.10f

    fun phase(t: Float): Float = (t % PERIOD) / PERIOD

    /** Pulse strength in 0..1 for the given time in seconds. */
    fun pulse(t: Float): Float {
        val p = phase(t)
        return gauss(p, FIRST_BEAT, 0.045f) + 0.62f * gauss(p, 0.30f, 0.05f)
    }

    private fun gauss(x: Float, center: Float, width: Float): Float {
        val d = (x - center) / width
        return exp(-d * d)
    }
}
