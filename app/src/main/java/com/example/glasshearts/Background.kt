package com.example.glasshearts

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Deep gradient with slowly orbiting colour blobs. They are what the glass hearts "refract". */
class Background {
    private class Blob(
        val ax: Float, val ay: Float,          // anchor (fraction of width / height)
        val rx: Float, val ry: Float,          // orbit radii (fraction)
        val fx: Float, val fy: Float,          // angular speeds
        val phase: Float,
        val radius: Float,                     // fraction of min(width, height)
        val color: Int,
        val parallax: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        lateinit var shader: RadialGradient
    }

    private val blobs = listOf(
        Blob(0.15f, 0.18f, 0.10f, 0.07f, 0.31f, 0.23f, 0.0f, 0.95f, Color.argb(170, 255, 64, 129), 1.0f),
        Blob(0.90f, 0.35f, 0.08f, 0.10f, 0.22f, 0.29f, 1.7f, 1.00f, Color.argb(150, 124, 77, 255), 0.7f),
        Blob(0.25f, 0.82f, 0.12f, 0.06f, 0.27f, 0.19f, 3.1f, 1.00f, Color.argb(140, 255, 94, 130), 1.3f),
        Blob(0.85f, 0.88f, 0.07f, 0.09f, 0.18f, 0.33f, 4.4f, 0.85f, Color.argb(120, 72, 140, 255), 0.5f),
        Blob(0.50f, 0.50f, 0.15f, 0.10f, 0.14f, 0.17f, 5.2f, 0.70f, Color.argb(95, 255, 170, 205), 0.9f),
    )
    private val base = Paint()
    private val m = Matrix()

    fun resize(h: Int) {
        base.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(14, 8, 38), Color.rgb(48, 12, 68), Color.rgb(92, 16, 82)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        for (b in blobs) {
            b.shader = RadialGradient(0f, 0f, 1f, b.color, b.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            b.paint.shader = b.shader
        }
    }

    fun draw(c: Canvas, w: Int, h: Int, t: Float, tiltX: Float, tiltY: Float, pulse: Float) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), base)
        val unit = min(w, h).toFloat()
        for (b in blobs) {
            val cx = (b.ax + b.rx * cos(b.fx * t + b.phase)) * w + tiltX * b.parallax * unit * 0.06f
            val cy = (b.ay + b.ry * sin(b.fy * t + b.phase)) * h + tiltY * b.parallax * unit * 0.06f
            val r = b.radius * unit * (1f + 0.035f * pulse)
            m.setScale(r, r)
            m.postTranslate(cx, cy)
            b.shader.setLocalMatrix(m)
            c.drawCircle(cx, cy, r, b.paint)
        }
    }
}
