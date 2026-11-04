package io.github.l0gic0l.roulade.dsp

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Tuning for [RieszMagnifier]. */
data class MagnifierOptions(
    /** Phase gain; in-band motion grows by roughly `1 + alpha`. */
    val alpha: Float,
    val lowHz: Double,
    val highHz: Double,
    val frameRateHz: Double,
    /** Butterworth order of the temporal band-pass (1–3). Higher isolates a mode more sharply. */
    val filterOrder: Int = 1,
    /** Sigma of the amplitude-weighted phase smoothing; 0 disables it. */
    val denoiseSigma: Float = 2f,
    /** Number of finest pyramid levels left unamplified (suppresses noise and fine-edge artifacts). */
    val skipFineLevels: Int = 0,
    /** Largest phase shift applied, in radians; 0 = no limit. Limits ghosting at high gain. */
    val phaseLimit: Float = 0f,
    /** Track per-pixel in-band motion energy for [heatmap]. */
    val trackEnergy: Boolean = false,
)

/**
 * Phase-based motion magnification using the Riesz pyramid
 * (Wadhwa, Rubinstein, Durand, Freeman — "Riesz Pyramids for Fast Phase-Based Video
 * Magnification", ICCP 2014).
 *
 * Each frame is split into a Laplacian pyramid. Per pixel and per level, the approximate Riesz
 * transform gives a local phase and orientation. The frame-to-frame phase change is accumulated,
 * band-pass filtered in time, smoothed spatially (weighted by local amplitude), multiplied by
 * alpha and applied back as a phase shift. Motion inside the frequency band is enlarged by
 * roughly `1 + alpha`; everything else passes through unchanged.
 *
 * The filter is causal, so frames are processed one at a time with constant memory.
 * Call [process] once per frame, in order.
 */
class RieszMagnifier(
    val width: Int,
    val height: Int,
    private val options: MagnifierOptions,
) {
    constructor(
        width: Int,
        height: Int,
        alpha: Float,
        lowHz: Double,
        highHz: Double,
        frameRateHz: Double,
    ) : this(width, height, MagnifierOptions(alpha, lowHz, highHz, frameRateHz))

    private val pyramid = LaplacianPyramid(width, height, LaplacianPyramid.levelsFor(width, height))
    private val filter = BandpassFilter(options.filterOrder, options.lowHz, options.highHz, options.frameRateHz)
    private val sections = filter.sections.toTypedArray()
    private val kernel = if (options.denoiseSigma > 0f) ImageOps.gaussianKernel(options.denoiseSigma) else null
    private val firstLevel = options.skipFineLevels.coerceIn(0, pyramid.levels - 1)
    private val levels = Array(pyramid.levels) { k ->
        if (k >= firstLevel) Level(pyramid.widths[k], pyramid.heights[k], sections.size) else null
    }
    private var firstFrame = true

    // Motion-energy map (heatmap) state, kept on one mid-sized level.
    private val energyLevel = max(firstLevel, min(2, pyramid.levels - 1))
    private val energy = if (options.trackEnergy) FloatArray(pyramid.widths[energyLevel] * pyramid.heights[energyLevel]) else null
    private val energyRate = (1.0 - exp(-1.0 / (options.frameRateHz * ENERGY_TIME_CONSTANT_S))).toFloat()

    private class Level(val w: Int, val h: Int, sectionCount: Int) {
        val n = w * h

        // Riesz coefficients of the previous frame (after a frame is processed: the current one).
        val prevReal = FloatArray(n)
        val prevX = FloatArray(n)
        val prevY = FloatArray(n)

        // Accumulated phase (times orientation) and the band-pass state, two registers per section.
        val phaseCos = FloatArray(n)
        val phaseSin = FloatArray(n)
        val regCos = Array(2 * sectionCount) { FloatArray(n) }
        val regSin = Array(2 * sectionCount) { FloatArray(n) }

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
        for (k in 0 until pyramid.levels) {
            val level = levels[k] ?: continue
            processLevel(level, pyramid.bands[k], k == energyLevel)
        }
        pyramid.collapse(output)
        firstFrame = false
    }

    /**
     * Writes the in-band motion map (RMS filtered phase, radians, smoothed over ~0.5 s) at full
     * resolution into [out]. Only available when [MagnifierOptions.trackEnergy] is set.
     */
    fun heatmap(out: FloatArray) {
        val e = energy ?: error("Energy tracking is off")
        val lw = pyramid.widths[energyLevel]
        val lh = pyramid.heights[energyLevel]
        val sx = lw.toFloat() / width
        val sy = lh.toFloat() / height
        Parallel.forRange(height) { y0, y1 ->
            for (y in y0 until y1) {
                val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (lh - 1).toFloat())
                val iy = fy.toInt()
                val ty = fy - iy
                val iy1 = min(iy + 1, lh - 1)
                for (x in 0 until width) {
                    val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (lw - 1).toFloat())
                    val ix = fx.toInt()
                    val tx = fx - ix
                    val ix1 = min(ix + 1, lw - 1)
                    val top = e[iy * lw + ix] * (1 - tx) + e[iy * lw + ix1] * tx
                    val bottom = e[iy1 * lw + ix] * (1 - tx) + e[iy1 * lw + ix1] * tx
                    out[y * width + x] = sqrt(max(0f, top * (1 - ty) + bottom * ty))
                }
            }
        }
    }

    private fun processLevel(s: Level, band: FloatArray, trackEnergy: Boolean) {
        val w = s.w
        val h = s.h
        val first = firstFrame
        val secs = sections

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

                    var fc = pc
                    var fs = ps
                    for (k in secs.indices) {
                        val sec = secs[k]
                        val r0c = s.regCos[2 * k]
                        val r1c = s.regCos[2 * k + 1]
                        val yc = sec.b0 * fc + r0c[i]
                        r0c[i] = sec.b1 * fc + r1c[i] - sec.a1 * yc
                        r1c[i] = sec.b2 * fc - sec.a2 * yc
                        fc = yc

                        val r0s = s.regSin[2 * k]
                        val r1s = s.regSin[2 * k + 1]
                        val ys = sec.b0 * fs + r0s[i]
                        r0s[i] = sec.b1 * fs + r1s[i] - sec.a1 * ys
                        r1s[i] = sec.b2 * fs - sec.a2 * ys
                        fs = ys
                    }

                    val amp = sqrt(qAmp)
                    s.amplitude[i] = amp
                    s.numCos[i] = fc * amp
                    s.numSin[i] = fs * amp
                }
            }
        }

        // 2) Amplitude-weighted spatial smoothing of the filtered phase (reduces noise).
        kernel?.let {
            ImageOps.blur(s.numCos, w, h, it, s.tmp)
            ImageOps.blur(s.numSin, w, h, it, s.tmp)
            ImageOps.blur(s.amplitude, w, h, it, s.tmp)
        }

        // Motion energy for the heatmap: filtered phase power, weighted by how much texture
        // (amplitude) backs it so flat, noisy areas stay cool.
        val e = if (trackEnergy) energy else null
        if (e != null) {
            var sum = 0.0
            for (i in 0 until s.n) sum += s.amplitude[i]
            val meanAmp = (sum / s.n).toFloat() + EPS
            val rate = energyRate
            Parallel.forRange(s.n, minChunk = 4096) { start, end ->
                for (i in start until end) {
                    val den = s.amplitude[i] + EPS
                    val fc = s.numCos[i] / den
                    val fs = s.numSin[i] / den
                    val confidence = den / (den + meanAmp)
                    e[i] += rate * ((fc * fc + fs * fs) * confidence - e[i])
                }
            }
        }

        // 3) Amplify and apply the phase shift; keep only the real part.
        val alpha = options.alpha
        val limit = options.phaseLimit
        Parallel.forRange(s.n, minChunk = 4096) { start, end ->
            for (i in start until end) {
                val den = s.amplitude[i] + EPS
                var pc = alpha * s.numCos[i] / den
                var ps = alpha * s.numSin[i] / den
                var mag = sqrt(pc * pc + ps * ps)
                if (limit > 0f && mag > limit) {
                    val scale = limit / mag
                    pc *= scale
                    ps *= scale
                    mag = limit
                }
                if (mag > EPS) {
                    val sinOverMag = sin(mag) / mag
                    band[i] = cos(mag) * band[i] - sinOverMag * (pc * s.prevX[i] + ps * s.prevY[i])
                }
            }
        }
    }

    private companion object {
        const val EPS = 1e-6f
        const val ENERGY_TIME_CONSTANT_S = 0.5
    }
}
