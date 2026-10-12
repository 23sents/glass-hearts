package com.example.glasshearts

import android.graphics.Canvas
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder

/**
 * The same scene as a live wallpaper: silent, no sensors or microphone (battery), capped at 30 fps and
 * paused while the home screen is not visible. A tap on the home screen makes hearts burst.
 */
class HeartWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = HeartWallpaperEngine()

    private inner class HeartWallpaperEngine : Engine(), HeartEngine.Host {
        private val scene = HeartEngine(this@HeartWallpaperService, this)
        private var running = false
        private var lastNanos = 0L
        private var accumulated = 0f

        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
                lastNanos = frameTimeNanos
                accumulated += dt
                if (accumulated >= FRAME_TIME) {
                    scene.step(accumulated)
                    accumulated = 0f
                    drawFrame()
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            scene.autoTilt = true
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            scene.resize(width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) start() else stop()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stop()
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> scene.onTouchDown(event.x, event.y)
                MotionEvent.ACTION_MOVE -> scene.onTouchMove(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scene.onTouchUp(event.x, event.y)
            }
        }

        private fun start() {
            if (running) return
            running = true
            lastNanos = 0L
            accumulated = 0f
            Choreographer.getInstance().postFrameCallback(frame)
        }

        private fun stop() {
            running = false
            Choreographer.getInstance().removeFrameCallback(frame)
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()
                if (canvas != null) scene.draw(canvas)
            } catch (e: IllegalStateException) {
                // surface went away between frames
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        // HeartEngine.Host - a wallpaper is silent and has no UI
        override fun haptic(strong: Boolean) = Unit
        override fun sound(fx: Fx, volume: Float) = Unit
        override fun openWallpaperPicker() = Unit
    }

    private companion object {
        const val FRAME_TIME = 1f / 30f - 0.002f
    }
}
