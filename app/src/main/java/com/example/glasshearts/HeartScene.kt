package com.example.glasshearts

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** What the Activity must provide to the scene: audio and the wallpaper picker. */
interface SceneListener {
    fun onSound(fx: Fx, volume: Float)
    fun onOpenWallpaperPicker()
}

/** Shows the [HeartEngine] full-screen inside the app and drives it with the Choreographer. */
class HeartScene(context: Context, private val listener: SceneListener) : View(context), HeartEngine.Host {
    val engine = HeartEngine(context, this).also { it.showWallpaperButton = true }

    private var running = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos
            engine.step(dt)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.resize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        engine.draw(canvas)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> engine.onTouchDown(e.x, e.y)
            MotionEvent.ACTION_MOVE -> engine.onTouchMove(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> engine.onTouchUp(e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> engine.burst(e.getX(e.actionIndex), e.getY(e.actionIndex))
        }
        return true
    }

    // HeartEngine.Host
    override fun haptic(strong: Boolean) {
        performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun sound(fx: Fx, volume: Float) = listener.onSound(fx, volume)

    override fun openWallpaperPicker() = listener.onOpenWallpaperPicker()
}
