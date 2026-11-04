package io.github.l0gic0l.roulade.dsp

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase-based motion magnification using the Riesz pyramid
 * (Wadhwa, Rubinstein, Durand, Freeman — "Riesz Pyramids for Fast Phase-Based Video
 * Magnification", ICCP 2014).
 *
 * Each frame is split into a Laplacian pyramid. Per pixel and per level, the approximate Riesz
 * transform gives a local phase and orientation. The frame-to-frame phase change is accumulated,
 * band-pass filtered in time, smoothed spatially (weighted by local amplitude), multiplied by
 * [alpha] and applied back as a phase shift. Motion inside the frequency band is enlarged by
 * roughly `1 + alpha`; everything else passes through unchanged.
 *
 * The filter is causal, so frames are processed one at a time with constant memory.
 * Call [process] once per frame, in order.
 */
class RieszMagnifier(
    val width: Int,
    val height: Int,
    private val alpha: Float,
    lowHz: Double,
    highHz: Double,
    frameRateHz: Double,
    blurSigma: Float = 2f,
) {
    private val pyramid = LaplacianPyramid(width, height, LaplacianPyramid.levelsFor(width, height))
    private val filter = BandpassFilter(lowHz, highHz, frameRateHz)
    private val kernel = ImageOps.gaussianKernel(blurSigma)
    private val levels = Array(pyramid.levels) { Level(pyramid.widths[it], pyramid.heights[it]) }
    private var firstFrame = true

    private class Level(val w: Int, val h: Int) {
        val n = w * h

        // Riesz coefficients of the previous frame (after a frame is processed: the current one).
        val prevReal = FloatArray(n)
        val prevX = FloatArray(n)
        val prevY = FloatArray(n)

        // Accumulated phase (times orientation) and the band-pass filter state for each component.
        val phaseCos = FloatArray(n)
        val phaseSin = FloatArray(n)
        val regCos0 = FloatArray(n)
        val regCos1 = FloatArray(n)
        val regSin0 = FloatArray(n)
        val regSin1 = FloatArray(n)

        // Scratch for the amplitude-weighted blur.
        val numCos = FloatArray(n)
        val numSin = FloatArray(n)
        val amplitude = FloatArray(n)
        val tmp = FloatArray(n)
    }

    val levelCount: Int get() = pyramid.levels

    /** Magnifies one luminance frame. [input] and [output] are `width x height`; they may be the same array. */
    fun process(input: FloatArray, output: FloatArray) {
        require(input.size >= width * height && output.size >= width * height)
        pyramid.build(input)
        for (k in 0 until pyramid.levels) processLevel(levels[k], pyramid.bands[k])
        pyramid.collapse(output)
        firstFrame = false
    }

    private fun processLevel(s: Level, band: FloatArray) {
        val w = s.w
        val h = s.h
        val first = firstFrame
        val b0 = filter.b0
        val b2 = filter.b2
        val a1 = filter.a1
        val a2 = filter.a2

        // 1) Riesz transform, phase difference to the previous frame, temporal band-pass.
        Parallel.forRange(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                val up = (if (y > 0) y - 1 else 0) * w
                val down = (if (y < h - 1) y + 1 else h - 1) * w
                for (x in 0 until w) {
                    val i = row + x
                    val left = if (x > 0) x - 1 else 0
                    val right = if (x < w - 1) x + 1 else w - 1

                    val cr = band[i]
                    val cx = 0.5f * (band[row + left] - band[row + right])
                    val cy = 0.5f * (band[up + x] - band[down + x])

                    val pr: Float
                    val px: Float
                    val py: Float
                    if (first) {
                        pr = cr; px = cx; py = cy
                    } else {
                        pr = s.prevReal[i]; px = s.prevX[i]; py = s.prevY[i]
                    }
                    s.prevReal[i] = cr
                    s.prevX[i] = cx
                    s.prevY[i] = cy

                    // q_current * conj(q_previous)
                    val qr = cr * pr + cx * px + cy * py
                    val qx = -cr * px + pr * cx
                    val qy = -cr * py + pr * cy
                    val orient = sqrt(qx * qx + qy * qy)
                    val qAmp = sqrt(qr * qr + orient * orient)

                    var pc = s.phaseCos[i]
                    var ps = s.phaseSin[i]
                    if (orient > EPS) {
                        // atan2 stays accurate for tiny angles, unlike acos(qr / |q|).
                        val dPhase = atan2(orient, qr)
                        pc += dPhase * qx / orient
                        ps += dPhase * qy / orient
                        s.phaseCos[i] = pc
                        s.phaseSin[i] = ps
                    }

                    val fc = b0 * pc + s.regCos0[i]
                    s.regCos0[i] = s.regCos1[i] - a1 * fc
                    s.regCos1[i] = b2 * pc - a2 * fc

                    val fs = b0 * ps + s.regSin0[i]
                    s.regSin0[i] = s.regSin1[i] - a1 * fs
                    s.regSin1[i] = b2 * ps - a2 * fs

                    val amp = sqrt(qAmp)
                    s.amplitude[i] = amp
                    s.numCos[i] = fc * amp
                    s.numSin[i] = fs * amp
                }
            }
        }

        // 2) Amplitude-weighted spatial smoothing of the filtered phase (reduces noise).
        ImageOps.blur(s.numCos, w, h, kernel, s.tmp)
        ImageOps.blur(s.numSin, w, h, kernel, s.tmp)
        ImageOps.blur(s.amplitude, w, h, kernel, s.tmp)

        // 3) Amplify and apply the phase shift; keep only the real part.
        val alpha = alpha
        Parallel.forRange(s.n, minChunk = 4096) { start, end ->
            for (i in start until end) {
                val den = s.amplitude[i] + EPS
                val pc = alpha * s.numCos[i] / den
                val ps = alpha * s.numSin[i] / den
                val mag = sqrt(pc * pc + ps * ps)
                if (mag > EPS) {
                    val sinOverMag = sin(mag) / mag
                    band[i] = cos(mag) * band[i] - sinOverMag * (pc * s.prevX[i] + ps * s.prevY[i])
                }
            }
        }
    }

    private companion object {
        const val EPS = 1e-6f
    }
}
