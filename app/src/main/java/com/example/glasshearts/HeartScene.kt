package com.example.glasshearts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The whole experience: a frame-driven scene (Choreographer, time-based, allocation-free per frame).
 *
 * Layers back to front: background blobs, sparkles, rising hearts, glow, the big beating heart,
 * ripples, burst hearts. Touch spawns bursts / ripples / trails; device tilt adds parallax.
 */
class HeartScene(context: Context) : View(context) {
    private val dens = resources.displayMetrics.density
    private val rnd = Random(7)

    private val background = Background()
    private val sparkles = Sparkles()
    private val particles = ParticleSystem(150)
    private val ripples = Ripples(12)
    private val intro = Spring(0f, 1f, 70f, 8.5f)   // entrance with a soft overshoot
    private val kick = Spring(0f, 0f, 240f, 10f)    // extra bounce when the heart is touched

    private var sprites: Array<Bitmap> = emptyArray()
    private var bigHeart: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowShader = RadialGradient(0f, 0f, 1f, Color.rgb(255, 70, 130),
        Color.argb(0, 255, 70, 130), Shader.TileMode.CLAMP)
    private val glowMatrix = Matrix()

    private var t = 0f
    private var lastFrameNanos = 0L
    private var running = false
    private var spawnAccumulator = 0f
    private var lastPhase = 0f
    private var tiltTargetX = 0f
    private var tiltTargetY = 0f
    private var tiltX = 0f
    private var tiltY = 0f
    private var lastTrailX = 0f
    private var lastTrailY = 0f

    // shake gesture: gravity is tracked with a low-pass filter, the rest is "linear" acceleration
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityReady = false
    private var shakeTarget = 0f
    private var shake = 0f            // smoothed intensity 0..1
    private var shakeHold = 0f        // how long the shake has been strong
    private var scatterCooldown = 0f

    // laid out per frame
    private var heartX = 0f
    private var heartY = 0f
    private var heartSize = 0f
    private var pulse = 0f

    init {
        glowPaint.shader = glowShader
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos
            step(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /** Normalised tilt in [-1, 1] (from the accelerometer). */
    fun setTilt(x: Float, y: Float) {
        tiltTargetX = x.coerceIn(-1f, 1f)
        tiltTargetY = y.coerceIn(-1f, 1f)
    }

    /** Raw accelerometer sample (m/s^2, includes gravity). Detects vigorous shaking. */
    fun onAcceleration(ax: Float, ay: Float, az: Float) {
        if (!gravityReady) {
            gravityX = ax; gravityY = ay; gravityZ = az
            gravityReady = true
            return
        }
        val a = 0.9f
        gravityX = a * gravityX + (1f - a) * ax
        gravityY = a * gravityY + (1f - a) * ay
        gravityZ = a * gravityZ + (1f - a) * az
        val lx = ax - gravityX
        val ly = ay - gravityY
        val lz = az - gravityZ
        val magnitude = sqrt(lx * lx + ly * ly + lz * lz)
        shakeTarget = max(shakeTarget, ((magnitude - 2.5f) / 10f).coerceIn(0f, 1f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        background.resize(h)
        sparkles.resize(w, h, dens, 46, rnd)
        sprites.forEach { it.recycle() }
        sprites = Array(GlassSprites.TINTS.size) { GlassSprites.create(192, GlassSprites.TINTS[it]) }
        bigHeart?.recycle()
        bigHeart = GlassSprites.create(min(900, max(512, (min(w, h) * 0.8f).toInt())), GlassSprites.TINTS[0])
    }

    // ------------------------------------------------------------------ simulation
    private fun step(dt: Float) {
        t += dt
        intro.update(dt)
        kick.update(dt)
        val k = min(1f, dt * 6f)
        tiltX += (tiltTargetX - tiltX) * k
        tiltY += (tiltTargetY - tiltY) * k

        updateShake(dt)

        pulse = Heartbeat.pulse(t)
        val phase = Heartbeat.phase(t)
        if (lastPhase < Heartbeat.FIRST_BEAT - 0.02f && phase >= Heartbeat.FIRST_BEAT - 0.02f) onBeat()
        lastPhase = phase

        spawnAccumulator += dt * RISING_PER_SECOND
        while (spawnAccumulator >= 1f) {
            spawnAccumulator -= 1f
            particles.spawnRising(width.toFloat(), height.toFloat(), dens, rnd, sprites.size.coerceAtLeast(1))
        }
        particles.update(dt, t)
        ripples.update(dt)

        val unit = min(width, height).toFloat()
        heartSize = unit * 0.74f * (intro.value * (1f + 0.085f * pulse) + kick.value * 0.5f)
        heartX = width / 2f + tiltX * dens * 10f
        heartY = height * 0.46f + sin(t * 0.9f) * dens * 6f + tiltY * dens * 8f
    }

    private fun updateShake(dt: Float) {
        val rate = if (shakeTarget > shake) 12f else 2.5f
        shake += (shakeTarget - shake) * min(1f, dt * rate)
        shakeTarget *= max(0f, 1f - dt * 8f)
        scatterCooldown = max(0f, scatterCooldown - dt)
        shakeHold = if (shake > SCATTER_LEVEL) shakeHold + dt else max(0f, shakeHold - dt * 2f)
        if (shakeHold > SCATTER_HOLD && scatterCooldown <= 0f) {
            shakeHold = 0f
            scatterCooldown = 2.2f
            scatter()
        }
    }

    /** Hearts fly apart sideways, fresh ones are thrown out of the big heart, which bounces. */
    private fun scatter() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        particles.scatter(width / 2f, dens, rnd)
        particles.spawnSideBurst(heartX, heartY, 26, dens, rnd, sprites.size.coerceAtLeast(1))
        ripples.spawn(heartX, heartY, min(width, height) * 0.9f, 1.1f, 0.9f)
        kick.velocity = -2.5f
        spawnAccumulator = -RISING_PER_SECOND * 1.2f   // short pause before new hearts start rising
    }

    /** 0 while calm, grows once the phone is really being shaken. */
    private fun jitter(): Float = ((shake - 0.15f) / 0.85f).coerceIn(0f, 1f)

    private fun onBeat() {
        if (width == 0) return
        ripples.spawn(heartX, heartY, min(width, height) * 0.55f, 1.5f, 0.35f)
    }

    // ------------------------------------------------------------------ rendering
    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || sprites.isEmpty()) return

        background.draw(canvas, w, h, t, tiltX, tiltY, pulse)
        sparkles.draw(canvas, t)
        particles.draw(canvas, sprites, bitmapPaint, false, t, jitter(), dens)

        // glow
        val gr = heartSize * (0.78f + 0.08f * pulse)
        glowMatrix.setScale(gr, gr)
        glowMatrix.postTranslate(heartX, heartY)
        glowShader.setLocalMatrix(glowMatrix)
        glowPaint.alpha = (70f + 70f * pulse).toInt().coerceIn(0, 255)
        canvas.drawCircle(heartX, heartY, gr, glowPaint)

        // the big glass heart
        bigHeart?.let { bmp ->
            val s = heartSize / bmp.width
            bitmapPaint.alpha = 255
            canvas.save()
            val j = jitter()
            canvas.translate(heartX + sin(t * 47f) * dens * 7f * j, heartY + cos(t * 53f) * dens * 7f * j)
            canvas.rotate(sin(t * 0.7f) * 2.2f + tiltX * 2f + sin(t * 61f) * 5f * j)
            canvas.scale(s, s)
            canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, bitmapPaint)
            canvas.restore()
        }

        ripples.draw(canvas, dens)
        particles.draw(canvas, sprites, bitmapPaint, true, t, jitter(), dens)
    }

    // ------------------------------------------------------------------ touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                touchDown(e.getX(i), e.getY(i))
                if (e.actionMasked == MotionEvent.ACTION_DOWN) { lastTrailX = e.x; lastTrailY = e.y }
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - lastTrailX, e.y - lastTrailY) > dens * 28f) {
                    lastTrailX = e.x; lastTrailY = e.y
                    particles.spawnTrail(e.x, e.y, dens, rnd, sprites.size.coerceAtLeast(1))
                }
            }
        }
        return true
    }

    private fun touchDown(x: Float, y: Float) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val n = sprites.size.coerceAtLeast(1)
        if (hypot(x - heartX, y - heartY) < heartSize * 0.42f) {
            kick.velocity = 3.5f
            particles.spawnBurst(heartX, heartY, 18, dens, rnd, n)
            ripples.spawn(heartX, heartY, min(width, height) * 0.7f, 1.2f, 0.8f)
        } else {
            particles.spawnBurst(x, y, 11, dens, rnd, n)
            ripples.spawn(x, y, min(width, height) * 0.35f, 0.9f, 0.7f)
        }
    }

    private companion object {
        const val RISING_PER_SECOND = 2.4f
        const val SCATTER_LEVEL = 0.45f
        const val SCATTER_HOLD = 0.30f
    }
}
