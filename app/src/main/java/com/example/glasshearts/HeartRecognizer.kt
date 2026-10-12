package com.example.glasshearts

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Recognises a hand-drawn heart. The stroke is resampled by arc length, normalised to the unit square and
 * compared with the heart outline (symmetric chamfer distance). Two structural checks reject look-alikes:
 * a visible notch between the lobes and a tip near the bottom centre. Thresholds were tuned on synthetic strokes
 * (noisy hearts in any start point / slight rotation / aspect change vs circles, ellipses, squares, scribbles).
 */
object HeartRecognizer {
    private const val N = 64
    private const val NOTCH = 0.05f
    private const val CHAMFER = 0.085f

    private val template: FloatArray by lazy { normalize(resample(HeartShape.outline(40), 160, N)!!) }

    fun isHeart(xs: FloatArray, ys: FloatArray, count: Int, minSizePx: Float): Boolean {
        if (count < 12) return false
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        val raw = FloatArray(count * 2)
        for (i in 0 until count) {
            raw[2 * i] = xs[i]; raw[2 * i + 1] = ys[i]
            x0 = min(x0, xs[i]); x1 = max(x1, xs[i])
            y0 = min(y0, ys[i]); y1 = max(y1, ys[i])
        }
        val w = x1 - x0
        val h = y1 - y0
        if (w < minSizePx || h < minSizePx) return false
        val aspect = w / h
        if (aspect < 0.55f || aspect > 1.8f) return false
        val closure = hypot(xs[count - 1] - xs[0], ys[count - 1] - ys[0]) / max(w, h)
        if (closure > 0.5f) return false

        val p = normalize(resample(raw, count, N) ?: return false)
        if (max(chamfer(p, template), chamfer(template, p)) > CHAMFER) return false

        var center = Float.MAX_VALUE
        var left = Float.MAX_VALUE
        var right = Float.MAX_VALUE
        var lowY = -1f
        var lowX = 0f
        for (i in 0 until N) {
            val x = p[2 * i]
            val y = p[2 * i + 1]
            if (x in 0.44f..0.56f) center = min(center, y)
            if (x < 0.38f) left = min(left, y)
            if (x > 0.62f) right = min(right, y)
            if (y > lowY) { lowY = y; lowX = x }
        }
        if (center == Float.MAX_VALUE || left == Float.MAX_VALUE || right == Float.MAX_VALUE) return false
        if (center - min(left, right) < NOTCH) return false
        return lowX in 0.30f..0.70f
    }

    private fun resample(p: FloatArray, count: Int, n: Int): FloatArray? {
        if (count < 2) return null
        val d = FloatArray(count)
        for (i in 1 until count) d[i] = d[i - 1] + hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1])
        val total = d[count - 1]
        if (total <= 0f) return null
        val out = FloatArray(2 * n)
        var j = 0
        for (k in 0 until n) {
            val target = total * k / (n - 1)
            while (j < count - 2 && d[j + 1] < target) j++
            val seg = d[j + 1] - d[j]
            val f = if (seg <= 1e-9f) 0f else (target - d[j]) / seg
            out[2 * k] = p[2 * j] + (p[2 * j + 2] - p[2 * j]) * f
            out[2 * k + 1] = p[2 * j + 1] + (p[2 * j + 3] - p[2 * j + 1]) * f
        }
        return out
    }

    private fun normalize(p: FloatArray): FloatArray {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until p.size / 2) {
            x0 = min(x0, p[2 * i]); x1 = max(x1, p[2 * i])
            y0 = min(y0, p[2 * i + 1]); y1 = max(y1, p[2 * i + 1])
        }
        val w = max(x1 - x0, 1e-6f)
        val h = max(y1 - y0, 1e-6f)
        val out = FloatArray(p.size)
        for (i in 0 until p.size / 2) {
            out[2 * i] = (p[2 * i] - x0) / w
            out[2 * i + 1] = (p[2 * i + 1] - y0) / h
        }
        return out
    }

    /** Mean distance from every point of [a] to its nearest point in [b]. */
    private fun chamfer(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val na = a.size / 2
        val nb = b.size / 2
        for (i in 0 until na) {
            var best = Float.MAX_VALUE
            for (j in 0 until nb) {
                val dx = a[2 * i] - b[2 * j]
                val dy = a[2 * i + 1] - b[2 * j + 1]
                best = min(best, dx * dx + dy * dy)
            }
            sum += sqrt(best)
        }
        return sum / na
    }
}
