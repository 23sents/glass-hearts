package com.example.glasshearts

import android.graphics.Path

/** Geometry of the heart. Unit space: x in [-1, 1], y in [-1, 1] (y grows downwards), tip at the bottom. */
object HeartShape {
    fun build(out: Path = Path()): Path {
        out.reset()
        out.moveTo(0f, 0.92f)
        out.cubicTo(-0.25f, 0.72f, -1.02f, 0.22f, -1.02f, -0.34f)
        out.cubicTo(-1.02f, -0.86f, -0.38f, -1.06f, 0f, -0.52f)
        out.cubicTo(0.38f, -1.06f, 1.02f, -0.86f, 1.02f, -0.34f)
        out.cubicTo(1.02f, 0.22f, 0.25f, 0.72f, 0f, 0.92f)
        out.close()
        return out
    }
}
