package io.github.l0gic0l.roulade.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** Smooth random texture, so gradients are well defined for motion estimation. */
internal fun texture(w: Int, h: Int, seed: Int = 3): FloatArray {
    val rnd = Random(seed)
    val img = FloatArray(w * h) { rnd.nextFloat() * 255f }
    val tmp = FloatArray(w * h)
    ImageOps.blur(img, w, h, ImageOps.gaussianKernel(2f), tmp)
    return img
}

class ButterworthOrderTest {
    @Test
    fun everyOrderIsMinus3dbAtEdgesAndUnityAtCentre() {
        val fs = 60.0
        for (order in 1..3) {
            val f = BandpassFilter(order, 4.0, 9.0, fs)
            assertEquals("order $order low edge", 1 / sqrt(2.0), f.gainAt(4.0, fs), 1e-3)
            assertEquals("order $order high edge", 1 / sqrt(2.0), f.gainAt(9.0, fs), 1e-3)
            val tc = sqrt(tan(PI * 4.0 / fs) * tan(PI * 9.0 / fs))
            val fc = kotlin.math.atan(tc) * fs / PI
            assertEquals("order $order centre", 1.0, f.gainAt(fc, fs), 1e-3)
        }
        // Higher order rejects out-of-band motion more strongly.
        val g1 = BandpassFilter(1, 4.0, 9.0, fs).gainAt(20.0, fs)
        val g3 = BandpassFilter(3, 4.0, 9.0, fs).gainAt(20.0, fs)
        assertTrue("order 1 $g1 vs order 3 $g3", g3 < g1 / 5)
    }

    @Test
    fun firstOrderMatchesClosedForm() {
        val fs = 30.0
        val k = 2 * fs
        val w1 = k * tan(PI * 2.0 / fs)
        val w2 = k * tan(PI * 5.0 / fs)
        val bw = w2 - w1
        val a0 = k * k + bw * k + w1 * w2
        val s = BandpassFilter(1, 2.0, 5.0, fs).sections.single()
        assertEquals(bw * k / a0, s.b0.toDouble(), 1e-6)
        assertEquals(-bw * k / a0, s.b2.toDouble(), 1e-6)
        assertEquals((2 * w1 * w2 - 2 * k * k) / a0, s.a1.toDouble(), 1e-6)
        assertEquals((k * k - bw * k + w1 * w2) / a0, s.a2.toDouble(), 1e-6)
    }
}

class WarpAndMotionTest {
    @Test
    fun integerShiftMovesPixels() {
        val w = 40
        val h = 30
        val src = texture(w, h)
        val dst = FloatArray(w * h)
        Warp.apply(src, w, h, dst, 0f, 1f, 2f, -1f, bicubic = true)
        for (y in 2 until h - 2) for (x in 2 until w - 4) {
            assertEquals(src[(y - 1) * w + x + 2], dst[y * w + x], 1e-3f)
        }
    }

    @Test
    fun globalMotionRecoversShiftAndRotation() {
        val w = 160
        val h = 120
        val prev = texture(w, h)
        // cur(R(a)(x-c)+c+d) = prev(x)  =>  cur = prev warped by the inverse motion.
        val angle = 0.01f
        val dx = 1.3f
        val dy = -0.7f
        val cur = FloatArray(w * h)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val c = kotlin.math.cos(-angle)
        val s = kotlin.math.sin(-angle)
        for (y in 0 until h) for (x in 0 until w) {
            val qx = x - cx - dx
            val qy = y - cy - dy
            cur[y * w + x] = Warp.cubic(prev, w, h, cx + c * qx - s * qy, cy + s * qx + c * qy)
        }
        val m = MotionEstimator.global(prev, cur, w, h)
        assertEquals(dx, m.dx, 0.08f)
        assertEquals(dy, m.dy, 0.08f)
        assertEquals(angle, m.angle, 0.0015f)
    }

    @Test
    fun blockTrackerMeasuresSubPixelShift() {
        val w = 128
        val h = 96
        val ref = texture(w, h, seed = 9)
        val moved = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) moved[y * w + x] = Warp.cubic(ref, w, h, x - 0.3f, y + 0.2f)
        val t = MotionEstimator.BlockTracker(ref, w, h, 4, 3)
        val dx = FloatArray(t.count)
        val dy = FloatArray(t.count)
        t.measure(moved, dx, dy)
        var n = 0
        for (k in 0 until t.count) if (t.valid[k]) {
            n++
            assertEquals(0.3f, dx[k], 0.06f)
            assertEquals(-0.2f, dy[k], 0.06f)
        }
        assertTrue("valid blocks $n", n >= 6)
    }
}

class SpectrumTest {
    @Test
    fun findsTheDominantFrequencies() {
        val fps = 60.0
        val n = 600
        val rnd = Random(4)
        val series = List(6) {
            FloatArray(n) { i ->
                val t = i / fps
                (0.5 * sin(2 * PI * 7.0 * t) + 0.2 * sin(2 * PI * 17.5 * t) + 0.05 * rnd.nextGaussian()).toFloat()
            }
        }
        val s = SpectrumAnalysis.analyse(series, fps, 0.5, 29.0)
        assertTrue("peaks ${s.peaks}", s.peaks.size >= 2)
        assertEquals(7.0, s.peaks[0].toDouble(), 0.1)
        assertEquals(17.5, s.peaks[1].toDouble(), 0.1)
    }

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
    }
}

class MagnifierOptionsTest {
    private val size = 96

    @Test
    fun heatmapIsHotWhereThingsMoveAndOptionsRun() {
        val base = texture(size, size, seed = 11)
        val opts = MagnifierOptions(
            alpha = 10f, lowHz = 2.0, highHz = 5.0, frameRateHz = 30.0,
            filterOrder = 2, skipFineLevels = 1, phaseLimit = PI.toFloat(), trackEnergy = true,
        )
        val mag = RieszMagnifier(size, size, opts)
        val frame = FloatArray(size * size)
        val out = FloatArray(size * size)
        for (n in 0 until 60) {
            val shift = 0.4f * sin(2 * PI * 3.0 * n / 30.0).toFloat()
            for (y in 0 until size) for (x in 0 until size) {
                // Left half vibrates horizontally, right half is still.
                frame[y * size + x] = if (x < size / 2) Warp.cubic(base, size, size, x - shift, y.toFloat()) else base[y * size + x]
            }
            mag.process(frame, out)
            assertTrue(out.all { it.isFinite() })
        }
        val heat = FloatArray(size * size)
        mag.heatmap(heat)
        var left = 0.0
        var right = 0.0
        for (y in 16 until size - 16) {
            for (x in 8 until 36) left += heat[y * size + x]
            for (x in size - 36 until size - 8) right += heat[y * size + x]
        }
        assertTrue("left $left right $right", left > 3 * right)
    }

    @Test
    fun phaseLimitCapsTheChange() {
        val base = texture(size, size, seed = 12)
        fun maxChange(limit: Float): Float {
            val mag = RieszMagnifier(size, size, MagnifierOptions(80f, 2.0, 5.0, 30.0, phaseLimit = limit))
            val frame = FloatArray(size * size)
            val out = FloatArray(size * size)
            var worst = 0f
            for (n in 0 until 40) {
                val shift = 0.5f * sin(2 * PI * 3.0 * n / 30.0).toFloat()
                for (y in 0 until size) for (x in 0 until size) frame[y * size + x] = Warp.cubic(base, size, size, x - shift, y.toFloat())
                mag.process(frame, out)
                for (i in frame.indices) worst = maxOf(worst, abs(out[i] - frame[i]))
            }
            return worst
        }
        assertTrue(maxChange(0.5f) < maxChange(0f))
    }
}
