package io.github.l0gic0l.roulade.dsp

import kotlin.math.PI
import kotlin.math.tan

/**
 * First-order Butterworth band-pass (a second-order IIR section), designed with the bilinear
 * transform and pre-warped band edges. Equivalent to MATLAB/SciPy `butter(1, [low, high], 'bandpass')`.
 *
 * Difference equation, normalised so a0 = 1 (b1 is always 0):
 *   y[n] = b0 x[n] + b2 x[n-2] - a1 y[n-1] - a2 y[n-2]
 */
class BandpassFilter(lowHz: Double, highHz: Double, sampleRateHz: Double) {
    val b0: Float
    val b2: Float
    val a1: Float
    val a2: Float

    init {
        require(sampleRateHz > 0) { "Frame rate must be positive" }
        require(lowHz > 0 && lowHz < highHz) { "Low cutoff must be above 0 and below the high cutoff" }
        require(highHz < sampleRateHz / 2) { "High cutoff must be below half the frame rate" }
        val k = 2.0 * sampleRateHz
        val w1 = k * tan(PI * lowHz / sampleRateHz)
        val w2 = k * tan(PI * highHz / sampleRateHz)
        val bw = w2 - w1
        val w0sq = w1 * w2
        val a0 = k * k + bw * k + w0sq
        b0 = (bw * k / a0).toFloat()
        b2 = -b0
        a1 = ((2.0 * w0sq - 2.0 * k * k) / a0).toFloat()
        a2 = ((k * k - bw * k + w0sq) / a0).toFloat()
    }
}
