package io.github.l0gic0l.roulade.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class BandpassFilterTest {
    private fun gain(f: BandpassFilter, hz: Double, fs: Double): Double {
        val w = 2 * PI * hz / fs
        // H(e^jw) = (b0 + b2 e^-2jw) / (1 + a1 e^-jw + a2 e^-2jw)
        val nRe = f.b0 + f.b2 * cos(2 * w)
        val nIm = -f.b2 * sin(2 * w)
        val dRe = 1 + f.a1 * cos(w) + f.a2 * cos(2 * w)
        val dIm = -f.a1 * sin(w) - f.a2 * sin(2 * w)
        return hypot(nRe, nIm) / hypot(dRe, dIm)
    }

    @Test
    fun bandEdgesAreMinus3dbAndCentreIsUnity() {
        val fs = 30.0
        val f = BandpassFilter(2.0, 5.0, fs)
        assertEquals(1 / sqrt(2.0), gain(f, 2.0, fs), 1e-4)
        assertEquals(1 / sqrt(2.0), gain(f, 5.0, fs), 1e-4)
        // Digital centre frequency for a bilinear-transformed band-pass.
        val tc = sqrt(kotlin.math.tan(PI * 2.0 / fs) * kotlin.math.tan(PI * 5.0 / fs))
        val fc = kotlin.math.atan(tc) * fs / PI
        assertEquals(1.0, gain(f, fc, fs), 1e-4)
        assertTrue(gain(f, 0.01, fs) < 0.01)
        assertTrue(gain(f, 14.99, fs) < 0.01)
    }
}

class LaplacianPyramidTest {
    @Test
    fun collapseInvertsBuildForOddSizes() {
        val w = 53
        val h = 37
        val rnd = Random(1)
        val img = FloatArray(w * h) { rnd.nextFloat() * 255f }
        val pyr = LaplacianPyramid(w, h, 3)
        pyr.build(img)
        val out = FloatArray(w * h)
        pyr.collapse(out)
        for (i in img.indices) assertEquals(img[i], out[i], 1e-3f)
    }
}

class AreaResamplerTest {
    @Test
    fun averagesBlocksWhenHalving() {
        val src = floatArrayOf(
            1f, 3f, 10f, 20f,
            5f, 7f, 30f, 40f,
            0f, 0f, 8f, 8f,
            4f, 4f, 8f, 8f,
        )
        val dst = FloatArray(4)
        AreaResampler(4, 4, 2, 2).resample(src, dst)
        assertEquals(4f, dst[0], 1e-5f)
        assertEquals(25f, dst[1], 1e-5f)
        assertEquals(2f, dst[2], 1e-5f)
        assertEquals(8f, dst[3], 1e-5f)
    }

    @Test
    fun keepsConstantImageConstantForOddRatios() {
        val src = FloatArray(97 * 61) { 42f }
        val dst = FloatArray(40 * 24)
        AreaResampler(97, 61, 40, 24).resample(src, dst)
        for (v in dst) assertEquals(42f, v, 1e-3f)
    }
}

class RieszMagnifierTest {
    private val size = 128
    private val wavelength = 8.0
    private val fps = 30.0

    /** Vertical stripes shifted horizontally by `shift` pixels. */
    private fun frame(shift: Double) = FloatArray(size * size) { i ->
        val x = i % size
        (128 + 60 * sin(2 * PI * (x - shift) / wavelength)).toFloat()
    }

    /** Horizontal shift of the stripes, measured from the phase of the stripe frequency. */
    private fun measureShift(img: FloatArray): Double {
        var c = 0.0
        var s = 0.0
        val theta = 2 * PI / wavelength
        for (y in 32 until 96) for (x in 16 until 112) {
            val v = img[y * size + x] - 128.0
            c += v * cos(theta * x)
            s += v * sin(theta * x)
        }
        return atan2(-c, s) / theta
    }

    /** Runs a sinusoidal motion through the magnifier and returns output/input motion amplitude. */
    private fun amplification(alpha: Float, motionHz: Double, lowHz: Double, highHz: Double): Double {
        val amplitude = 0.05
        val mag = RieszMagnifier(size, size, alpha, lowHz, highHz, fps)
        val out = FloatArray(size * size)
        val shifts = ArrayList<Double>()
        val frames = 150
        for (n in 0 until frames) {
            val d = amplitude * sin(2 * PI * motionHz * n / fps)
            mag.process(frame(d), out)
            if (n >= frames - 60) shifts.add(measureShift(out))
        }
        val outAmplitude = (shifts.max() - shifts.min()) / 2
        return outAmplitude / amplitude
    }

    @Test
    fun alphaZeroIsIdentity() {
        val mag = RieszMagnifier(size, size, 0f, 1.0, 5.0, fps)
        val out = FloatArray(size * size)
        for (n in 0 until 5) {
            val input = frame(0.3 * n)
            mag.process(input, out)
            var maxErr = 0f
            for (i in input.indices) maxErr = maxOf(maxErr, abs(input[i] - out[i]))
            assertTrue("max error $maxErr", maxErr < 1e-2f)
        }
    }

    @Test
    fun inBandMotionIsAmplifiedByAboutOnePlusAlpha() {
        // Ideal is 1 + alpha = 11. The 3-tap Riesz approximation under-reads phase on the finest
        // level, so the measured factor lands around 8.5; this guards against gross errors.
        val ratio = amplification(alpha = 10f, motionHz = 3.0, lowHz = 2.0, highHz = 4.5)
        assertTrue("amplification ratio $ratio", ratio in 11.0 * 0.6..11.0 * 1.2)
    }

    @Test
    fun outOfBandMotionIsLeftMostlyAlone() {
        val inBand = amplification(alpha = 10f, motionHz = 3.0, lowHz = 2.0, highHz = 4.5)
        val outOfBand = amplification(alpha = 10f, motionHz = 12.0, lowHz = 2.0, highHz = 4.5)
        assertTrue("in-band $inBand vs out-of-band $outOfBand", outOfBand < 0.5 * inBand)
    }
}
