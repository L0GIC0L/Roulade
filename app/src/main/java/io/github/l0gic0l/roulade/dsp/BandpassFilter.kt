package io.github.l0gic0l.roulade.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** One second-order IIR section: y = b0 x + b1 x[-1] + b2 x[-2] - a1 y[-1] - a2 y[-2]. */
class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

/**
 * Butterworth band-pass of the given [order] (1 = gentle, 3 = sharp), built as a cascade of
 * [order] second-order sections. Designed like MATLAB/SciPy `butter(order, [low, high], 'bandpass')`:
 * analog low-pass prototype → band-pass transform → bilinear transform with pre-warped edges.
 * The response is 1 at the band centre and 1/√2 (−3 dB) at both edges for every order.
 */
class BandpassFilter(
    val order: Int,
    lowHz: Double,
    highHz: Double,
    sampleRateHz: Double,
) {
    val sections: List<Biquad>

    init {
        require(order in 1..4) { "Filter order must be 1–4" }
        require(sampleRateHz > 0) { "Frame rate must be positive" }
        require(lowHz > 0 && lowHz < highHz) { "Low cutoff must be above 0 and below the high cutoff" }
        require(highHz < sampleRateHz / 2) { "High cutoff must be below half the frame rate" }

        val k = 2.0 * sampleRateHz
        val w1 = k * tan(PI * lowHz / sampleRateHz)
        val w2 = k * tan(PI * highHz / sampleRateHz)
        val bw = w2 - w1
        val w0sq = w1 * w2

        // Digital poles: each analog low-pass pole p gives two band-pass poles, then z = (k+s)/(k-s).
        val upper = ArrayList<Complex>()
        val real = ArrayList<Double>()
        for (i in 0 until order) {
            val theta = PI * (2 * i + order + 1) / (2.0 * order)
            val p = Complex(cos(theta), sin(theta))
            val pb = p * bw
            val disc = (pb * pb - Complex(4 * w0sq, 0.0)).sqrt()
            for (s in listOf((pb + disc) * 0.5, (pb - disc) * 0.5)) {
                val z = (Complex(k, 0.0) + s) / (Complex(k, 0.0) - s)
                when {
                    z.im > 1e-12 -> upper += z
                    abs(z.im) <= 1e-12 -> real += z.re
                    // Lower-half poles are the conjugates of upper ones.
                }
            }
        }
        val raw = ArrayList<DoubleArray>() // a1, a2 per section
        for (z in upper) raw += doubleArrayOf(-2 * z.re, z.re * z.re + z.im * z.im)
        real.sort()
        var i = 0
        while (i + 1 < real.size) {
            raw += doubleArrayOf(-(real[i] + real[i + 1]), real[i] * real[i + 1])
            i += 2
        }
        check(raw.size == order) { "Unexpected pole layout" }

        // Normalise so the cascade has unit gain at the digital centre frequency.
        val wc = 2 * atan(sqrt(w0sq) / k)
        var mag = 1.0
        for (a in raw) mag *= sectionGain(1.0, 0.0, -1.0, a[0], a[1], wc)
        val g = (1.0 / mag).pow(1.0 / order)
        sections = raw.map { a -> Biquad(g.toFloat(), 0f, (-g).toFloat(), a[0].toFloat(), a[1].toFloat()) }
    }

    /** |H(e^jw)| of the whole cascade at [hz]; used by tests and the UI. */
    fun gainAt(hz: Double, sampleRateHz: Double): Double {
        val w = 2 * PI * hz / sampleRateHz
        var m = 1.0
        for (s in sections) {
            m *= sectionGain(s.b0.toDouble(), s.b1.toDouble(), s.b2.toDouble(), s.a1.toDouble(), s.a2.toDouble(), w)
        }
        return m
    }

    private fun sectionGain(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double, w: Double): Double {
        val nRe = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val nIm = -b1 * sin(w) - b2 * sin(2 * w)
        val dRe = 1 + a1 * cos(w) + a2 * cos(2 * w)
        val dIm = -a1 * sin(w) - a2 * sin(2 * w)
        return hypot(nRe, nIm) / hypot(dRe, dIm)
    }

    private data class Complex(val re: Double, val im: Double) {
        operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)
        operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)
        operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(d: Double) = Complex(re * d, im * d)
        operator fun div(o: Complex): Complex {
            val d = o.re * o.re + o.im * o.im
            return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }

        fun sqrt(): Complex {
            val r = hypot(re, im)
            val a = kotlin.math.sqrt((r + re) / 2)
            val b = kotlin.math.sqrt(((r - re) / 2).coerceAtLeast(0.0))
            return Complex(a, if (im < 0) -b else b)
        }
    }
}
