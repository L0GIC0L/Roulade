package io.github.l0gic0l.roulade.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/** Motion power spectrum of a clip and its strongest peaks. */
class Spectrum(
    val frequencies: FloatArray,
    /** Power per frequency bin, normalised so the largest value is 1. */
    val power: FloatArray,
    /** Peak frequencies in Hz, strongest first. */
    val peaks: List<Float>,
)

object SpectrumAnalysis {

    /** In-place radix-2 complex FFT; the size must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Sums the power spectra of many equally sampled displacement series (detrended, Hann
     * windowed, zero-padded 2x) and picks up to [maxPeaks] peaks between [minHz] and [maxHz].
     */
    fun analyse(series: List<FloatArray>, sampleRateHz: Double, minHz: Double, maxHz: Double, maxPeaks: Int = 5): Spectrum {
        val n = series.firstOrNull()?.size ?: 0
        require(n >= 8) { "Need at least 8 frames" }
        var size = 1
        while (size < n) size = size shl 1
        size = size shl 1
        val bins = size / 2 + 1
        val total = DoubleArray(bins)
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        for (s in series) {
            // Remove the straight-line trend (drift) before windowing.
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for (i in 0 until n) { sx += i; sy += s[i]; sxx += i.toDouble() * i; sxy += i * s[i].toDouble() }
            val slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
            val icept = (sy - slope * sx) / n
            re.fill(0.0); im.fill(0.0)
            for (i in 0 until n) re[i] = (s[i] - (icept + slope * i)) * window[i]
            fft(re, im)
            for (k in 0 until bins) total[k] += re[k] * re[k] + im[k] * im[k]
        }
        val freqs = FloatArray(bins) { (it * sampleRateHz / size).toFloat() }
        val peak = total.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val power = FloatArray(bins) { (total[it] / peak).toFloat() }
        return Spectrum(freqs, power, findPeaks(freqs, power, minHz, maxHz, maxPeaks))
    }

    /** Local maxima well above the median level, refined by parabolic interpolation in log power. */
    fun findPeaks(freqs: FloatArray, power: FloatArray, minHz: Double, maxHz: Double, maxPeaks: Int): List<Float> {
        val idx = freqs.indices.filter { freqs[it] in minHz..maxHz }
        if (idx.size < 3) return emptyList()
        val median = idx.map { power[it] }.sorted()[idx.size / 2]
        val found = ArrayList<Pair<Float, Float>>()
        for (k in idx) {
            if (k == 0 || k == power.size - 1) continue
            val p = power[k]
            if (p > power[k - 1] && p >= power[k + 1] && p > 4 * median && p > 1e-4f) {
                val a = ln(power[k - 1] + 1e-12)
                val b = ln(p + 1e-12)
                val c = ln(power[k + 1] + 1e-12)
                val denom = a - 2 * b + c
                val shift = if (denom < 0) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0
                val df = freqs[1] - freqs[0]
                found += (freqs[k] + (shift * df).toFloat()) to p
            }
        }
        return found.sortedByDescending { it.second }.take(maxPeaks).map { it.first }
    }
}
