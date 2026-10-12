package com.example.glasshearts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The whole scene, independent of where it is shown (an Activity view or a live wallpaper):
 * allocation-free per frame, time-based. Hosts feed it time ([step]), a canvas ([draw]) and input.
 *
 * Layers back to front: background blobs, sparkles, rising hearts, glow, the big beating heart, ripples,
 * burst hearts, fireworks, the finger stroke, and (inside the app only) the wallpaper button.
 */
class HeartEngine(context: Context, private val host: Host) {
    interface Host {
        fun haptic(strong: Boolean)
        fun sound(fx: Fx, volume: Float)
        fun openWallpaperPicker()
    }

    /** Show the small glass "set as wallpaper" button (app only). */
    var showWallpaperButton = false

    /** Slowly sways the parallax by itself (wallpaper has no accelerometer). */
    var autoTilt = false

    /** 0..1 blow intensity, written by the microphone thread. */
    @Volatile var micBlow = 0f
    @Volatile private var clapPending = false

    var width = 0
        private set
    var height = 0
        private set

    private val dens = context.resources.displayMetrics.density
    private val rnd = Random(7)

    private val background = Background()
    private val sparkles = Sparkles()
    private val particles = ParticleSystem(180)
    private val ripples = Ripples(14)
    private val fireworks = Fireworks(dens, rnd)
    private val intro = Spring(0f, 1f, 70f, 8.5f)   // entrance with a soft overshoot
    private val kick = Spring(0f, 0f, 240f, 10f)    // bounce when the heart is hit

    private var sprites: Array<Bitmap> = emptyArray()
    private var bigHeart: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowShader = RadialGradient(
        0f, 0f, 1f, Color.rgb(255, 70, 130), Color.argb(0, 255, 70, 130), Shader.TileMode.CLAMP
    )
    private val glowMatrix = Matrix()

    // time / input state
    private var t = 0f
    private var spawnAccumulator = 0f
    private var lastPhase = 0f
    private var tiltTargetX = 0f
    private var tiltTargetY = 0f
    private var tiltX = 0f
    private var tiltY = 0f
    private var lastTrailX = 0f
    private var lastTrailY = 0f

    // shake / blow
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityReady = false
    private var shakeTarget = 0f
    private var shake = 0f
    private var shakeHold = 0f
    private var scatterCooldown = 0f

    // finger stroke (heart gesture)
    private val strokeX = FloatArray(MAX_STROKE)
    private val strokeY = FloatArray(MAX_STROKE)
    private var strokeN = 0
    private var drawing = false
    private var strokeAlpha = 0f
    private val strokePath = Path()
    private val strokeGlow = strokePaint()
    private val strokeCore = strokePaint()

    // wallpaper button
    private var btnX = 0f
    private var btnY = 0f
    private var btnR = 0f
    private var buttonDown = false
    private val btnFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnSheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(235, 255, 255, 255) }

    // per-frame layout
    private var heartX = 0f
    private var heartY = 0f
    private var heartSize = 0f
    private var pulse = 0f

    init {
        glowPaint.shader = glowShader
        fireworks.onExplode = { x, y ->
            particles.spawnBurst(x, y, 6, dens, rnd, spriteCount())
            ripples.spawn(x, y, min(width, height) * 0.28f, 0.8f, 0.5f)
        }
    }

    private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun spriteCount() = sprites.size.coerceAtLeast(1)

    // ------------------------------------------------------------------ input from sensors / mic
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

    /** A clap was heard (called from the microphone thread). */
    fun onClap() {
        clapPending = true
    }

    // ------------------------------------------------------------------ lifecycle
    fun resize(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        width = w
        height = h
        background.resize(h)
        sparkles.resize(w, h, dens, 46, rnd)
        if (sprites.isEmpty()) {
            sprites = Array(GlassSprites.TINTS.size) { GlassSprites.create(192, GlassSprites.TINTS[it]) }
        }
        val bigPx = min(900, max(512, (min(w, h) * 0.8f).toInt()))
        if (bigHeart?.width != bigPx) {
            bigHeart?.recycle()
            bigHeart = GlassSprites.create(bigPx, GlassSprites.TINTS[0])
        }
        btnR = dens * 26f
        btnX = w / 2f
        btnY = h - dens * 76f
        btnSheen.shader = LinearGradient(
            0f, -btnR, 0f, btnR,
            intArrayOf(Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(34, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        btnRim.shader = LinearGradient(
            0f, -btnR, 0f, btnR,
            intArrayOf(Color.argb(230, 255, 255, 255), Color.argb(50, 255, 255, 255), Color.argb(140, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        btnRim.strokeWidth = max(1.5f, dens * 1.2f)
        iconPaint.strokeWidth = dens * 1.6f
    }

    // ------------------------------------------------------------------ simulation
    fun step(dt: Float) {
        t += dt
        intro.update(dt)
        kick.update(dt)
        if (autoTilt) {
            tiltTargetX = sin(t * 0.35f) * 0.6f
            tiltTargetY = cos(t * 0.27f) * 0.4f
        }
        val k = min(1f, dt * 6f)
        tiltX += (tiltTargetX - tiltX) * k
        tiltY += (tiltTargetY - tiltY) * k

        val blow = micBlow
        if (blow > 0.05f) shakeTarget = max(shakeTarget, blow * 0.9f)   // wind makes hearts tremble, then scatter
        if (clapPending) {
            clapPending = false
            clapShow()
        }
        updateShake(dt)

        pulse = Heartbeat.pulse(t)
        val phase = Heartbeat.phase(t)
        if (crossed(lastPhase, phase, Heartbeat.FIRST_BEAT - 0.02f)) {
            onBeat()
            host.sound(Fx.LUB, 0.35f)
        }
        if (crossed(lastPhase, phase, Heartbeat.SECOND_BEAT - 0.02f)) host.sound(Fx.DUB, 0.28f)
        lastPhase = phase

        spawnAccumulator += dt * RISING_PER_SECOND
        while (spawnAccumulator >= 1f) {
            spawnAccumulator -= 1f
            particles.spawnRising(width.toFloat(), height.toFloat(), dens, rnd, spriteCount())
        }
        particles.update(dt, t)
        ripples.update(dt)
        fireworks.update(dt)
        if (!drawing) strokeAlpha = max(0f, strokeAlpha - dt * 2.4f)

        val unit = min(width, height).toFloat()
        heartSize = unit * 0.74f * (intro.value * (1f + 0.085f * pulse) + kick.value * 0.5f)
        heartX = width / 2f + tiltX * dens * 10f
        heartY = height * 0.46f + sin(t * 0.9f) * dens * 6f + tiltY * dens * 8f
    }

    private fun crossed(a: Float, b: Float, c: Float) = a < c && b >= c

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
        host.haptic(true)
        host.sound(Fx.WHOOSH, 0.7f)
        particles.scatter(width / 2f, dens, rnd)
        particles.spawnSideBurst(heartX, heartY, 26, dens, rnd, spriteCount())
        ripples.spawn(heartX, heartY, min(width, height) * 0.9f, 1.1f, 0.9f)
        kick.velocity = -2.5f
        spawnAccumulator = -RISING_PER_SECOND * 1.2f
    }

    /** Three staggered shells (the middle one bursts into a heart). */
    private fun clapShow() {
        host.haptic(true)
        host.sound(Fx.FIREWORK, 0.9f)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 0 until 3) {
            val x = w * (0.2f + 0.3f * i) + rnd.nextFloat(-0.06f * w, 0.06f * w)
            fireworks.launch(x, h + dens * 20f, h * rnd.nextFloat(0.16f, 0.40f), i * 0.28f, i == 1, i)
        }
        kick.velocity = 2f
    }

    private fun jitter(): Float = ((shake - 0.15f) / 0.85f).coerceIn(0f, 1f)

    private fun onBeat() {
        if (width == 0) return
        ripples.spawn(heartX, heartY, min(width, height) * 0.55f, 1.5f, 0.35f)
    }

    // ------------------------------------------------------------------ rendering
    fun draw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || sprites.isEmpty()) return

        background.draw(canvas, w, h, t, tiltX, tiltY, pulse)
        sparkles.draw(canvas, t)
        val jit = jitter()
        particles.draw(canvas, sprites, bitmapPaint, false, t, jit, dens)

        val gr = heartSize * (0.78f + 0.08f * pulse)
        glowMatrix.setScale(gr, gr)
        glowMatrix.postTranslate(heartX, heartY)
        glowShader.setLocalMatrix(glowMatrix)
        glowPaint.alpha = (70f + 70f * pulse).toInt().coerceIn(0, 255)
        canvas.drawCircle(heartX, heartY, gr, glowPaint)

        bigHeart?.let { bmp ->
            val s = heartSize / bmp.width
            bitmapPaint.alpha = 255
            canvas.save()
            canvas.translate(heartX + sin(t * 47f) * dens * 7f * jit, heartY + cos(t * 53f) * dens * 7f * jit)
            canvas.rotate(sin(t * 0.7f) * 2.2f + tiltX * 2f + sin(t * 61f) * 5f * jit)
            canvas.scale(s, s)
            canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, bitmapPaint)
            canvas.restore()
        }

        ripples.draw(canvas, dens)
        particles.draw(canvas, sprites, bitmapPaint, true, t, jit, dens)
        fireworks.draw(canvas)
        drawStroke(canvas)
        if (showWallpaperButton) drawButton(canvas)
    }

    private fun drawStroke(c: Canvas) {
        if (strokeN < 2 || strokeAlpha <= 0f) return
        strokePath.rewind()
        strokePath.moveTo(strokeX[0], strokeY[0])
        for (i in 1 until strokeN) strokePath.lineTo(strokeX[i], strokeY[i])
        val a = min(1f, strokeAlpha)
        strokeGlow.color = Color.argb((95 * a).toInt(), 255, 110, 170)
        strokeGlow.strokeWidth = dens * 11f
        c.drawPath(strokePath, strokeGlow)
        strokeCore.color = Color.argb((220 * a).toInt(), 255, 255, 255)
        strokeCore.strokeWidth = dens * 2.6f
        c.drawPath(strokePath, strokeCore)
    }

    /** Glass round button with a tiny phone-with-heart icon: opens the live wallpaper picker. */
    private fun drawButton(c: Canvas) {
        val s = 1f + 0.04f * pulse - (if (buttonDown) 0.1f else 0f)
        c.save()
        c.translate(btnX, btnY)
        c.scale(s, s)
        btnFill.color = Color.argb(46, 255, 255, 255)
        c.drawCircle(0f, 0f, btnR, btnFill)
        c.drawCircle(0f, 0f, btnR, btnSheen)
        c.drawCircle(0f, 0f, btnR - dens * 0.6f, btnRim)
        val pw = dens * 12f
        val ph = dens * 21f
        c.drawRoundRect(-pw / 2f, -ph / 2f, pw / 2f, ph / 2f, dens * 3.2f, dens * 3.2f, iconPaint)
        val bmp = sprites[0]
        val sc = dens * 7f / bmp.width
        bitmapPaint.alpha = 255
        c.save()
        c.scale(sc, sc)
        c.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, bitmapPaint)
        c.restore()
        c.restore()
    }

    // ------------------------------------------------------------------ touch
    private fun onButton(x: Float, y: Float) = showWallpaperButton && hypot(x - btnX, y - btnY) < btnR * 1.3f

    fun onTouchDown(x: Float, y: Float) {
        if (onButton(x, y)) {
            buttonDown = true
            host.haptic(false)
            return
        }
        strokeN = 0
        drawing = true
        strokeAlpha = 1f
        addStrokePoint(x, y)
        lastTrailX = x
        lastTrailY = y
        burst(x, y)
    }

    fun onTouchMove(x: Float, y: Float) {
        if (buttonDown) return
        addStrokePoint(x, y)
        if (hypot(x - lastTrailX, y - lastTrailY) > dens * 28f) {
            lastTrailX = x
            lastTrailY = y
            particles.spawnTrail(x, y, dens, rnd, spriteCount())
        }
    }

    fun onTouchUp(x: Float, y: Float) {
        if (buttonDown) {
            buttonDown = false
            if (onButton(x, y)) host.openWallpaperPicker()
            return
        }
        drawing = false
        if (HeartRecognizer.isHeart(strokeX, strokeY, strokeN, dens * 60f)) heartDrawn()
    }

    /** Burst of hearts + ripple at a point (also used for extra fingers). */
    fun burst(x: Float, y: Float) {
        host.haptic(false)
        host.sound(Fx.CHIME, 0.5f)
        if (hypot(x - heartX, y - heartY) < heartSize * 0.42f) {
            kick.velocity = 3.5f
            particles.spawnBurst(heartX, heartY, 18, dens, rnd, spriteCount())
            ripples.spawn(heartX, heartY, min(width, height) * 0.7f, 1.2f, 0.8f)
        } else {
            particles.spawnBurst(x, y, 11, dens, rnd, spriteCount())
            ripples.spawn(x, y, min(width, height) * 0.35f, 0.9f, 0.7f)
        }
    }

    private fun addStrokePoint(x: Float, y: Float) {
        if (strokeN >= MAX_STROKE) return
        if (strokeN > 0 && hypot(x - strokeX[strokeN - 1], y - strokeY[strokeN - 1]) < dens * 3f) return
        strokeX[strokeN] = x
        strokeY[strokeN] = y
        strokeN++
    }

    /** The finger drew a heart: a heart-shaped firework at its centre. */
    private fun heartDrawn() {
        var x0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in 0 until strokeN) {
            x0 = min(x0, strokeX[i]); x1 = max(x1, strokeX[i])
            y0 = min(y0, strokeY[i]); y1 = max(y1, strokeY[i])
        }
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val size = max(x1 - x0, y1 - y0)
        val scale = (size / (dens * 190f)).coerceIn(0.7f, 1.7f)
        host.haptic(true)
        host.sound(Fx.FIREWORK, 0.9f)
        fireworks.explode(cx, cy, true, 0, scale)
        fireworks.explode(cx, cy, false, 1, scale * 0.6f)
        particles.spawnBurst(cx, cy, 16, dens, rnd, spriteCount())
        ripples.spawn(cx, cy, size * 1.2f, 1.1f, 0.9f)
        kick.velocity = 3f
        strokeAlpha = 1.4f
    }

    private companion object {
        const val RISING_PER_SECOND = 2.4f
        const val SCATTER_LEVEL = 0.45f
        const val SCATTER_HOLD = 0.30f
        const val MAX_STROKE = 600
    }
}
