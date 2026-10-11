package com.example.glasshearts

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Uniform float in [from, until). */
internal fun Random.nextFloat(from: Float, until: Float): Float = from + nextFloat() * (until - from)

/** A pooled sprite particle. kind 0 = rising heart (sways), 1 = burst / trail heart (ballistic), 2 = scattered sideways (flies off and fades). */
class Particle {
    var active = false
    var kind = 0
    var x = 0f; var y = 0f; var baseX = 0f
    var vx = 0f; var vy = 0f; var ay = 0f
    var size = 0f
    var rot = 0f; var vr = 0f
    var life = 0f; var maxLife = 1f
    var sprite = 0
    var swayAmp = 0f; var swayFreq = 0f; var phase = 0f
}

/** Fixed-capacity particle pool: no allocation while the scene is running. */
class ParticleSystem(capacity: Int) {
    private val items = Array(capacity) { Particle() }

    private fun obtain(): Particle? {
        for (p in items) if (!p.active) return p
        return null
    }

    fun spawnRising(w: Float, h: Float, dens: Float, rnd: Random, spriteCount: Int) {
        val p = obtain() ?: return
        val size = dens * rnd.nextFloat(18f, 46f)
        val speed = dens * rnd.nextFloat(55f, 130f)
        p.active = true; p.kind = 0
        p.baseX = rnd.nextFloat(0.05f * w, 0.95f * w); p.x = p.baseX
        p.y = h + size
        p.vx = 0f; p.vy = -speed; p.ay = 0f
        p.size = size
        p.rot = rnd.nextFloat(-20f, 20f); p.vr = rnd.nextFloat(-15f, 15f)
        p.life = 0f; p.maxLife = (h + 2f * size) / speed
        p.sprite = rnd.nextInt(spriteCount)
        p.swayAmp = dens * rnd.nextFloat(6f, 22f)
        p.swayFreq = rnd.nextFloat(0.8f, 2.2f)
        p.phase = rnd.nextFloat(0f, 6.2832f)
    }

    fun spawnBurst(x: Float, y: Float, count: Int, dens: Float, rnd: Random, spriteCount: Int) {
        repeat(count) {
            val p = obtain() ?: return
            val angle = rnd.nextFloat(0f, 6.2832f)
            val speed = dens * rnd.nextFloat(120f, 320f)
            p.active = true; p.kind = 1
            p.baseX = x; p.x = x; p.y = y
            p.vx = cos(angle) * speed; p.vy = sin(angle) * speed - dens * 60f; p.ay = -dens * 30f
            p.size = dens * rnd.nextFloat(14f, 34f)
            p.rot = rnd.nextFloat(-40f, 40f); p.vr = rnd.nextFloat(-360f, 360f)
            p.life = 0f; p.maxLife = rnd.nextFloat(0.9f, 1.7f)
            p.sprite = rnd.nextInt(spriteCount)
            p.swayAmp = 0f; p.swayFreq = 0f; p.phase = 0f
        }
    }

    fun spawnTrail(x: Float, y: Float, dens: Float, rnd: Random, spriteCount: Int) {
        val p = obtain() ?: return
        p.active = true; p.kind = 1
        p.baseX = x; p.x = x; p.y = y
        p.vx = dens * rnd.nextFloat(-25f, 25f); p.vy = -dens * rnd.nextFloat(30f, 70f); p.ay = -dens * 20f
        p.size = dens * rnd.nextFloat(10f, 20f)
        p.rot = rnd.nextFloat(-30f, 30f); p.vr = rnd.nextFloat(-90f, 90f)
        p.life = 0f; p.maxLife = rnd.nextFloat(0.8f, 1.3f)
        p.sprite = rnd.nextInt(spriteCount)
        p.swayAmp = 0f; p.swayFreq = 0f; p.phase = 0f
    }

    /** Sends every live heart flying sideways (away from the vertical centre line) - used by the shake gesture. */
    fun scatter(centerX: Float, dens: Float, rnd: Random) {
        for (p in items) {
            if (!p.active) continue
            val dir = if (p.x < centerX) -1f else 1f
            p.kind = 2
            p.baseX = p.x
            p.vx = dir * dens * rnd.nextFloat(500f, 1100f)
            p.vy = dens * rnd.nextFloat(-160f, 120f)
            p.ay = dens * 40f
            p.vr = dir * rnd.nextFloat(200f, 600f)
            p.life = 0f
            p.maxLife = rnd.nextFloat(0.9f, 1.5f)
            p.swayAmp = 0f
        }
    }

    /** Fresh hearts thrown left and right from a point. */
    fun spawnSideBurst(x: Float, y: Float, count: Int, dens: Float, rnd: Random, spriteCount: Int) {
        for (i in 0 until count) {
            val p = obtain() ?: return
            val side = if (i % 2 == 0) -1f else 1f
            p.active = true; p.kind = 2
            p.baseX = x; p.x = x; p.y = y
            p.vx = side * dens * rnd.nextFloat(350f, 900f)
            p.vy = dens * rnd.nextFloat(-380f, 60f)
            p.ay = dens * 60f
            p.size = dens * rnd.nextFloat(16f, 40f)
            p.rot = rnd.nextFloat(-40f, 40f); p.vr = side * rnd.nextFloat(200f, 600f)
            p.life = 0f; p.maxLife = rnd.nextFloat(1.0f, 1.6f)
            p.sprite = rnd.nextInt(spriteCount)
            p.swayAmp = 0f; p.swayFreq = 0f; p.phase = 0f
        }
    }

    fun update(dt: Float, t: Float) {
        for (p in items) {
            if (!p.active) continue
            p.life += dt
            if (p.life >= p.maxLife) { p.active = false; continue }
            if (p.kind != 0) {
                val drag = max(0f, 1f - (if (p.kind == 1) 2.2f else 0.7f) * dt)
                p.vx *= drag
                p.vy = p.vy * drag + p.ay * dt
            }
            p.baseX += p.vx * dt
            p.y += p.vy * dt
            p.x = p.baseX + if (p.kind == 0) sin(t * p.swayFreq + p.phase) * p.swayAmp else 0f
            p.rot += p.vr * dt
        }
    }

    /**
     * @param front draws kinds 1 and 2 when true, kind 0 (behind the big heart) when false
     * @param jitter 0..1 shake intensity: hearts tremble while the phone is shaken
     */
    fun draw(c: Canvas, sprites: Array<android.graphics.Bitmap>, paint: Paint, front: Boolean,
             t: Float, jitter: Float, dens: Float) {
        for (i in items.indices) {
            val p = items[i]
            if (!p.active || (p.kind != 0) != front) continue
            val u = p.life / p.maxLife
            val alpha = if (p.kind == 2) min(1f, (p.maxLife - p.life) / 0.45f)
                        else min(1f, u / 0.12f) * min(1f, (1f - u) / 0.30f)
            if (alpha <= 0.01f) continue
            val bmp = sprites[p.sprite]
            val jit = if (p.kind == 2) 0f else jitter
            val jx = sin(t * 47f + i * 1.7f) * dens * 7f * jit
            val jy = cos(t * 53f + i * 2.3f) * dens * 7f * jit
            val jr = sin(t * 61f + i) * 14f * jit
            val shrink = if (p.kind == 1) 0.6f + 0.4f * (1f - u) else 1f
            val scale = p.size * shrink * (1f + 0.15f * jit * sin(t * 40f + i)) / bmp.width
            paint.alpha = (alpha * 255f).toInt()
            c.save()
            c.translate(p.x + jx, p.y + jy)
            c.rotate(p.rot + jr)
            c.scale(scale, scale)
            c.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, paint)
            c.restore()
        }
    }
}

/** Expanding glass ring (touch response and heartbeat echo). */
class Ripple {
    var active = false
    var x = 0f; var y = 0f
    var age = 0f; var duration = 1f
    var maxRadius = 1f; var strength = 1f
}

class Ripples(capacity: Int) {
    private val items = Array(capacity) { Ripple() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun spawn(x: Float, y: Float, maxRadius: Float, duration: Float, strength: Float) {
        val r = items.firstOrNull { !it.active } ?: return
        r.active = true; r.x = x; r.y = y; r.age = 0f
        r.duration = duration; r.maxRadius = maxRadius; r.strength = strength
    }

    fun update(dt: Float) {
        for (r in items) {
            if (!r.active) continue
            r.age += dt
            if (r.age >= r.duration) r.active = false
        }
    }

    fun draw(c: Canvas, dens: Float) {
        for (r in items) {
            if (!r.active) continue
            val u = r.age / r.duration
            val ease = 1f - (1f - u).pow(3)
            val fade = (1f - u).pow(1.5f) * r.strength
            val radius = r.maxRadius * ease
            ring.strokeWidth = dens * (1f + 3f * (1f - u))
            ring.color = Color.argb((fade * 200f).toInt(), 255, 255, 255)
            c.drawCircle(r.x, r.y, radius, ring)
            ring.strokeWidth = dens * (2f + 5f * (1f - u))
            ring.color = Color.argb((fade * 70f).toInt(), 255, 110, 170)
            c.drawCircle(r.x, r.y, radius * 0.93f, ring)
        }
    }
}

/** Twinkling four-point stars scattered over the screen. */
class Sparkles {
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var phase = FloatArray(0)
    private var freq = FloatArray(0)
    private var size = FloatArray(0)
    private val star = Path().apply {
        moveTo(0f, -1f); quadTo(0.12f, -0.12f, 1f, 0f)
        quadTo(0.12f, 0.12f, 0f, 1f); quadTo(-0.12f, 0.12f, -1f, 0f)
        quadTo(-0.12f, -0.12f, 0f, -1f); close()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun resize(w: Int, h: Int, dens: Float, count: Int, rnd: Random) {
        xs = FloatArray(count) { rnd.nextFloat(0f, w.toFloat()) }
        ys = FloatArray(count) { rnd.nextFloat(0f, h.toFloat()) }
        phase = FloatArray(count) { rnd.nextFloat(0f, 6.2832f) }
        freq = FloatArray(count) { rnd.nextFloat(0.6f, 1.8f) }
        size = FloatArray(count) { dens * rnd.nextFloat(3f, 8f) }
    }

    fun draw(c: Canvas, t: Float) {
        for (i in xs.indices) {
            val tw = (sin(t * freq[i] + phase[i]) * 0.5f + 0.5f).pow(3)
            if (tw < 0.03f) continue
            paint.color = Color.argb((tw * 230f).toInt(), 255, 240, 250)
            c.save()
            c.translate(xs[i], ys[i])
            c.scale(size[i], size[i])
            c.drawPath(star, paint)
            c.restore()
        }
    }
}
