package io.github.l0gic0l.roulade.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Rigid motion of image content: shift in pixels and clockwise rotation (radians) about the centre. */
data class Motion(val dx: Float, val dy: Float, val angle: Float) {
    companion object {
        val ZERO = Motion(0f, 0f, 0f)
    }
}

/**
 * Lucas–Kanade style least-squares motion estimation on small grey images.
 *
 * [global] measures how the whole picture moved between two frames (used to time-align sensor data
 * with video). [BlockTracker] measures many small regions against a reference frame (used to find
 * vibration frequencies).
 */
object MotionEstimator {

    /**
     * Motion of the content from [prev] to [cur] (`cur(R(angle)(x - c) + c + d) ≈ prev(x)`), using a
     * two-level coarse-to-fine search. Both images are `w x h` and should be lightly blurred.
     */
    fun global(prev: FloatArray, cur: FloatArray, w: Int, h: Int): Motion {
        val hw = ImageOps.half(w)
        val hh = ImageOps.half(h)
        var m = Motion.ZERO
        if (min(hw, hh) >= 24) {
            val tmp = FloatArray(hw * h)
            val p2 = FloatArray(hw * hh)
            val c2 = FloatArray(hw * hh)
            ImageOps.reduce(prev, w, h, p2, tmp)
            ImageOps.reduce(cur, w, h, c2, tmp)
            m = refine(p2, c2, hw, hh, Motion.ZERO, 3)
            m = Motion(m.dx * 2, m.dy * 2, m.angle)
        }
        return refine(prev, cur, w, h, m, 3)
    }

    private fun refine(prev: FloatArray, cur: FloatArray, w: Int, h: Int, start: Motion, iterations: Int): Motion {
        val warped = FloatArray(w * h)
        var dx = start.dx
        var dy = start.dy
        var angle = start.angle
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val radius = max(w, h) / 2f
        val margin = max(3, (0.06f * min(w, h)).toInt())
        repeat(iterations) {
            Warp.apply(cur, w, h, warped, angle, 1f, dx, dy, bicubic = false)
            // Normal equations for (vx, vy, vAngle * radius); radius scaling keeps them well conditioned.
            val a = DoubleArray(6) // a00 a01 a02 a11 a12 a22
            val b = DoubleArray(3)
            for (y in margin until h - margin) {
                val ry = (y - cy) / radius
                for (x in margin until w - margin) {
                    val i = y * w + x
                    val ix = 0.5f * (prev[i + 1] - prev[i - 1])
                    val iy = 0.5f * (prev[i + w] - prev[i - w])
                    val it = warped[i] - prev[i]
                    val rx = (x - cx) / radius
                    val g2 = -ix * ry + iy * rx
                    a[0] += (ix * ix).toDouble(); a[1] += (ix * iy).toDouble(); a[2] += (ix * g2).toDouble()
                    a[3] += (iy * iy).toDouble(); a[4] += (iy * g2).toDouble(); a[5] += (g2 * g2).toDouble()
                    b[0] -= (ix * it).toDouble(); b[1] -= (iy * it).toDouble(); b[2] -= (g2 * it).toDouble()
                }
            }
            val v = solve3(a, b) ?: return Motion(dx, dy, angle)
            val vx = v[0].toFloat()
            val vy = v[1].toFloat()
            val va = (v[2] / radius).toFloat()
            // Compose: d' = d + R(angle) v, angle' = angle + vAngle.
            val c = kotlin.math.cos(angle)
            val s = kotlin.math.sin(angle)
            dx += c * vx - s * vy
            dy += s * vx + c * vy
            angle += va
            if (abs(vx) < 0.005f && abs(vy) < 0.005f && abs(va) < 1e-5f) return Motion(dx, dy, angle)
        }
        return Motion(dx, dy, angle)
    }

    /** Solves the symmetric 3x3 system given as its upper triangle; null when (nearly) singular. */
    private fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val m00 = a[0]; val m01 = a[1]; val m02 = a[2]
        val m11 = a[3]; val m12 = a[4]; val m22 = a[5]
        val c00 = m11 * m22 - m12 * m12
        val c01 = m02 * m12 - m01 * m22
        val c02 = m01 * m12 - m02 * m11
        val det = m00 * c00 + m01 * c01 + m02 * c02
        val scale = m00 + m11 + m22
        if (scale <= 0 || abs(det) < 1e-9 * scale * scale * scale) return null
        val c11 = m00 * m22 - m02 * m02
        val c12 = m01 * m02 - m00 * m12
        val c22 = m00 * m11 - m01 * m01
        return doubleArrayOf(
            (c00 * b[0] + c01 * b[1] + c02 * b[2]) / det,
            (c01 * b[0] + c11 * b[1] + c12 * b[2]) / det,
            (c02 * b[0] + c12 * b[1] + c22 * b[2]) / det,
        )
    }

    /**
     * Tracks a grid of blocks against a fixed reference frame with one linearised Lucas–Kanade step
     * per block. Accurate for small (sub-pixel to ~1 px) motion, which is what vibration produces.
     */
    class BlockTracker(reference: FloatArray, private val w: Int, private val h: Int, gridX: Int, gridY: Int) {
        private val ref = reference.copyOf()
        private val ix = FloatArray(w * h)
        private val iy = FloatArray(w * h)
        private val x0: IntArray
        private val x1: IntArray
        private val y0: IntArray
        private val y1: IntArray
        private val inv: Array<DoubleArray>

        /** Whether each block has enough texture in both directions to be trusted. */
        val valid: BooleanArray
        val count = gridX * gridY

        init {
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                ix[i] = 0.5f * (ref[i + 1] - ref[i - 1])
                iy[i] = 0.5f * (ref[i + w] - ref[i - w])
            }
            x0 = IntArray(count); x1 = IntArray(count); y0 = IntArray(count); y1 = IntArray(count)
            val mins = DoubleArray(count)
            inv = Array(count) { DoubleArray(3) }
            for (by in 0 until gridY) for (bx in 0 until gridX) {
                val k = by * gridX + bx
                x0[k] = max(1, bx * w / gridX); x1[k] = min(w - 1, (bx + 1) * w / gridX)
                y0[k] = max(1, by * h / gridY); y1[k] = min(h - 1, (by + 1) * h / gridY)
                var sxx = 0.0; var sxy = 0.0; var syy = 0.0
                for (y in y0[k] until y1[k]) for (x in x0[k] until x1[k]) {
                    val i = y * w + x
                    sxx += ix[i] * ix[i]; sxy += ix[i] * iy[i]; syy += iy[i] * iy[i]
                }
                val det = sxx * syy - sxy * sxy
                val tr = sxx + syy
                // Smaller eigenvalue of the structure tensor, per pixel.
                mins[k] = (tr / 2 - sqrt(max(0.0, tr * tr / 4 - det))) / ((x1[k] - x0[k]) * (y1[k] - y0[k])).coerceAtLeast(1)
                if (det > 1e-9) inv[k] = doubleArrayOf(syy / det, -sxy / det, sxx / det)
            }
            val sorted = mins.sorted()
            val threshold = max(0.5, sorted[sorted.size / 2] * 0.25)
            valid = BooleanArray(count) { mins[it] >= threshold }
        }

        /** Displacement of every block in [frame] relative to the reference, written to [dx]/[dy]. */
        fun measure(frame: FloatArray, dx: FloatArray, dy: FloatArray) {
            for (k in 0 until count) {
                if (!valid[k]) { dx[k] = 0f; dy[k] = 0f; continue }
                var bx = 0.0
                var by = 0.0
                for (y in y0[k] until y1[k]) for (x in x0[k] until x1[k]) {
                    val i = y * w + x
                    val it = frame[i] - ref[i]
                    bx -= ix[i] * it
                    by -= iy[i] * it
                }
                val m = inv[k]
                dx[k] = (m[0] * bx + m[1] * by).toFloat()
                dy[k] = (m[1] * bx + m[2] * by).toFloat()
            }
        }
    }
}
