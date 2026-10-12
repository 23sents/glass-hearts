package com.example.glasshearts

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Rockets rise, then burst into additive sparks - either a ring or a heart-shaped pattern. */
class Fireworks(private val dens: Float, private val rnd: Random) {
    private class Shell {
        var active = false
        var delay = 0f
        var x = 0f
        var y = 0f
        var targetY = 0f
        var heart = false
        var color = 0
    }

    private val shells = Array(10) { Shell() }

    private val cap = 720
    private val sx = FloatArray(cap)
    private val sy = FloatArray(cap)
    private val svx = FloatArray(cap)
    private val svy = FloatArray(cap)
    private val slife = FloatArray(cap)
    private val smax = FloatArray(cap)
    private val ssize = FloatArray(cap)
    private val scolor = IntArray(cap)
    private val sactive = BooleanArray(cap)
    private var cursor = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        strokeCap = Paint.Cap.ROUND
    }

    /** Called when a shell bursts: (x, y). */
    var onExplode: ((Float, Float) -> Unit)? = null

    fun launch(x: Float, fromY: Float, targetY: Float, delay: Float, heart: Boolean, color: Int) {
        val s = shells.firstOrNull { !it.active } ?: return
        s.active = true; s.x = x; s.y = fromY; s.targetY = targetY
        s.delay = delay; s.heart = heart; s.color = color
    }

    fun explode(x: Float, y: Float, heart: Boolean, color: Int, scale: Float) {
        val speed = dens * 320f * scale
        if (heart) {
            for (layer in 0..1) {
                val count = if (layer == 0) 72 else 40
                val k = if (layer == 0) 1f else 0.55f
                for (i in 0 until count) {
                    val th = i.toFloat() / count * 6.2832f
                    val hx = 16f * sin(th).pow(3)
                    val hy = 13f * cos(th) - 5f * cos(2f * th) - 2f * cos(3f * th) - cos(4f * th)
                    spawnSpark(x, y, hx / 17f * speed * k, -hy / 17f * speed * k, pick(color))
                }
            }
        } else {
            val count = 56
            for (i in 0 until count) {
                val a = rnd.nextFloat(0f, 6.2832f)
                val v = speed * rnd.nextFloat(0.35f, 1f)
                spawnSpark(x, y, cos(a) * v, sin(a) * v, pick(color))
            }
        }
    }

    private fun pick(base: Int): Int =
        if (rnd.nextFloat() < 0.18f) Color.rgb(255, 245, 250) else PALETTE[base % PALETTE.size]

    private fun spawnSpark(x: Float, y: Float, vx: Float, vy: Float, color: Int) {
        for (n in 0 until cap) {
            val i = (cursor + n) % cap
            if (sactive[i]) continue
            cursor = (i + 1) % cap
            sactive[i] = true
            sx[i] = x; sy[i] = y; svx[i] = vx; svy[i] = vy
            slife[i] = 0f; smax[i] = rnd.nextFloat(1.1f, 1.7f)
            ssize[i] = dens * rnd.nextFloat(1.6f, 3.4f)
            scolor[i] = color
            return
        }
    }

    fun update(dt: Float) {
        for (s in shells) {
            if (!s.active) continue
            if (s.delay > 0f) { s.delay -= dt; continue }
            s.y += (s.targetY - s.y) * min(1f, dt * 3.2f)
            if (s.y - s.targetY < dens * 14f) {
                s.active = false
                explode(s.x, s.y, s.heart, s.color, 1f)
                onExplode?.invoke(s.x, s.y)
            }
        }
        for (i in 0 until cap) {
            if (!sactive[i]) continue
            slife[i] += dt
            if (slife[i] >= smax[i]) { sactive[i] = false; continue }
            val d = max(0f, 1f - 1.7f * dt)
            svx[i] *= d
            svy[i] = svy[i] * d + dens * 150f * dt
            sx[i] += svx[i] * dt
            sy[i] += svy[i] * dt
        }
    }

    fun draw(c: Canvas) {
        // rockets
        for (s in shells) {
            if (!s.active || s.delay > 0f) continue
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dens * 2f
            paint.color = Color.argb(150, 255, 220, 235)
            c.drawLine(s.x, s.y, s.x, s.y + dens * 26f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(255, 255, 255, 255)
            c.drawCircle(s.x, s.y, dens * 2.6f, paint)
        }
        // sparks
        for (i in 0 until cap) {
            if (!sactive[i]) continue
            val u = slife[i] / smax[i]
            val a = ((1f - u).pow(1.3f) * 255f).toInt().coerceIn(0, 255)
            val base = scolor[i]
            val col = Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
            val size = ssize[i] * (1f - 0.5f * u)
            paint.color = col
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size
            c.drawLine(sx[i] - svx[i] * 0.035f, sy[i] - svy[i] * 0.035f, sx[i], sy[i], paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(sx[i], sy[i], size * 0.65f, paint)
        }
    }

    private companion object {
        val PALETTE = intArrayOf(
            Color.rgb(255, 105, 160), Color.rgb(255, 214, 120), Color.rgb(190, 140, 255), Color.rgb(255, 150, 120)
        )
    }
}
