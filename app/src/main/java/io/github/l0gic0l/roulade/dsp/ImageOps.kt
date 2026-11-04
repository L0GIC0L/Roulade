package io.github.l0gic0l.roulade.dsp

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Basic single-channel image operations on row-major float arrays.
 * Every function writes into caller-owned arrays so per-frame work does not allocate.
 */
internal object ImageOps {

    /** 5-tap binomial kernel used by the Burt–Adelson Laplacian pyramid. */
    private val BINOMIAL = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)

    /** Mirror an index into `[0, n)` without repeating the edge sample (…2 1 | 0 1 2 … n-1 | n-2…). */
    fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        var j = i
        while (j < 0 || j >= n) {
            if (j < 0) j = -j
            if (j >= n) j = 2 * (n - 1) - j
        }
        return j
    }

    /** Size of the next coarser pyramid level. */
    fun half(n: Int) = (n + 1) / 2

    /**
     * Blur with the binomial kernel and drop every other row and column.
     * [src] is `w x h`, [dst] is `half(w) x half(h)`, [tmp] holds at least `half(w) * h` floats.
     */
    fun reduce(src: FloatArray, w: Int, h: Int, dst: FloatArray, tmp: FloatArray) {
        val dw = half(w)
        val dh = half(h)
        val k = BINOMIAL
        Parallel.forRange(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                val out = y * dw
                for (x in 0 until dw) {
                    val c = 2 * x
                    var acc = 0f
                    for (t in 0 until 5) acc += k[t] * src[row + reflect(c + t - 2, w)]
                    tmp[out + x] = acc
                }
            }
        }
        Parallel.forRange(dh) { y0, y1 ->
            for (y in y0 until y1) {
                val out = y * dw
                for (x in 0 until dw) dst[out + x] = 0f
                for (t in 0 until 5) {
                    val src0 = reflect(2 * y + t - 2, h) * dw
                    val kt = k[t]
                    for (x in 0 until dw) dst[out + x] += kt * tmp[src0 + x]
                }
            }
        }
    }

    /**
     * Upsample a `half(w) x half(h)` image to `w x h` (zero insertion followed by the
     * binomial kernel scaled by 2 per axis). [tmp] holds at least `w * half(h)` floats.
     */
    fun expand(src: FloatArray, w: Int, h: Int, dst: FloatArray, tmp: FloatArray) {
        val sw = half(w)
        val sh = half(h)
        val k = BINOMIAL
        // Horizontal: sw x sh -> w x sh
        Parallel.forRange(sh) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * sw
                val out = y * w
                for (x in 0 until w) {
                    var acc = 0f
                    for (t in 0 until 5) {
                        val i = reflect(x + t - 2, w)
                        if (i and 1 == 0) acc += k[t] * src[row + (i shr 1)]
                    }
                    tmp[out + x] = 2f * acc
                }
            }
        }
        // Vertical: w x sh -> w x h
        Parallel.forRange(h) { y0, y1 ->
            for (y in y0 until y1) {
                val out = y * w
                for (x in 0 until w) dst[out + x] = 0f
                for (t in 0 until 5) {
                    val i = reflect(y + t - 2, h)
                    if (i and 1 != 0) continue
                    val src0 = (i shr 1) * w
                    val kt = 2f * k[t]
                    for (x in 0 until w) dst[out + x] += kt * tmp[src0 + x]
                }
            }
        }
    }

    /** Normalised 1-D Gaussian taps for the given sigma (radius = ceil(2.5 sigma)). */
    fun gaussianKernel(sigma: Float): FloatArray {
        val r = kotlin.math.ceil(2.5f * sigma).toInt().coerceAtLeast(1)
        val taps = FloatArray(2 * r + 1) { i ->
            val d = (i - r).toFloat()
            exp(-(d * d) / (2f * sigma * sigma))
        }
        val sum = taps.sum()
        for (i in taps.indices) taps[i] /= sum
        return taps
    }

    /** Separable blur of [data] in place using [kernel]; edges are clamped. [tmp] is `w * h`. */
    fun blur(data: FloatArray, w: Int, h: Int, kernel: FloatArray, tmp: FloatArray) {
        val r = kernel.size / 2
        // Horizontal pass: data -> tmp
        Parallel.forRange(h) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * w
                for (x in 0 until w) {
                    var acc = 0f
                    if (x >= r && x < w - r) {
                        val base = row + x - r
                        for (t in kernel.indices) acc += kernel[t] * data[base + t]
                    } else {
                        for (t in kernel.indices) {
                            val xi = min(max(x + t - r, 0), w - 1)
                            acc += kernel[t] * data[row + xi]
                        }
                    }
                    tmp[row + x] = acc
                }
            }
        }
        // Vertical pass: tmp -> data
        Parallel.forRange(h) { y0, y1 ->
            for (y in y0 until y1) {
                val out = y * w
                for (x in 0 until w) data[out + x] = 0f
                for (t in kernel.indices) {
                    val src0 = min(max(y + t - r, 0), h - 1) * w
                    val kt = kernel[t]
                    for (x in 0 until w) data[out + x] += kt * tmp[src0 + x]
                }
            }
        }
    }
}

/**
 * Geometric resampling used for stabilisation. For each destination pixel `q`, samples [src] at
 * `c + R(angle) (q - c) * invZoom + (tx, ty)`, where `c` is the image centre and the rotation is
 * clockwise-positive in image coordinates (y down). Edges are clamped.
 */
internal object Warp {
    fun apply(
        src: FloatArray, w: Int, h: Int, dst: FloatArray,
        angle: Float, invZoom: Float, tx: Float, ty: Float, bicubic: Boolean,
    ) {
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val ca = kotlin.math.cos(angle) * invZoom
        val sa = kotlin.math.sin(angle) * invZoom
        Parallel.forRange(h) { y0, y1 ->
            for (y in y0 until y1) {
                val qy = y - cy
                for (x in 0 until w) {
                    val qx = x - cx
                    val sx = cx + ca * qx - sa * qy + tx
                    val sy = cy + sa * qx + ca * qy + ty
                    dst[y * w + x] = if (bicubic) cubic(src, w, h, sx, sy) else linear(src, w, h, sx, sy)
                }
            }
        }
    }

    fun linear(src: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val ix = fx.toInt()
        val iy = fy.toInt()
        val tx = fx - ix
        val ty = fy - iy
        val ix1 = min(ix + 1, w - 1)
        val iy1 = min(iy + 1, h - 1)
        val top = src[iy * w + ix] * (1 - tx) + src[iy * w + ix1] * tx
        val bottom = src[iy1 * w + ix] * (1 - tx) + src[iy1 * w + ix1] * tx
        return top * (1 - ty) + bottom * ty
    }

    /** Catmull-Rom bicubic sample with clamped edges. */
    fun cubic(src: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val ix = fx.toInt()
        val iy = fy.toInt()
        val tx = fx - ix
        val ty = fy - iy
        var acc = 0f
        for (j in -1..2) {
            val row = min(max(iy + j, 0), h - 1) * w
            val wy = catmullRom(ty, j)
            var r = 0f
            for (i in -1..2) r += catmullRom(tx, i) * src[row + min(max(ix + i, 0), w - 1)]
            acc += wy * r
        }
        return acc
    }

    private fun catmullRom(t: Float, i: Int): Float {
        val t2 = t * t
        val t3 = t2 * t
        return when (i) {
            -1 -> -0.5f * t3 + t2 - 0.5f * t
            0 -> 1.5f * t3 - 2.5f * t2 + 1f
            1 -> -1.5f * t3 + 2f * t2 + 0.5f * t
            else -> 0.5f * t3 - 0.5f * t2
        }
    }
}

/**
 * Resizes images by area averaging: each output pixel is the mean of the source area it covers.
 * This avoids the aliasing that point sampling creates, which motion amplification would otherwise
 * turn into visible shimmer.
 */
class AreaResampler(
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val dstWidth: Int,
    private val dstHeight: Int,
) {
    private class Axis(srcLen: Int, dstLen: Int) {
        val start = IntArray(dstLen)
        val count = IntArray(dstLen)
        val offset = IntArray(dstLen)
        val weights: FloatArray

        init {
            val scale = srcLen.toDouble() / dstLen
            val w = ArrayList<Float>()
            for (d in 0 until dstLen) {
                val a = d * scale
                val b = (d + 1) * scale
                val first = kotlin.math.floor(a).toInt().coerceIn(0, srcLen - 1)
                val last = (kotlin.math.ceil(b).toInt() - 1).coerceIn(first, srcLen - 1)
                start[d] = first
                count[d] = last - first + 1
                offset[d] = w.size
                for (s in first..last) {
                    val overlap = min(b, s + 1.0) - max(a, s.toDouble())
                    w.add((overlap / scale).toFloat())
                }
            }
            weights = w.toFloatArray()
        }
    }

    private val xAxis = Axis(srcWidth, dstWidth)
    private val yAxis = Axis(srcHeight, dstHeight)
    private val tmp = FloatArray(dstWidth * srcHeight)

    /** [src] is `srcWidth x srcHeight`, [dst] is `dstWidth x dstHeight`. */
    fun resample(src: FloatArray, dst: FloatArray) {
        val xa = xAxis
        val ya = yAxis
        Parallel.forRange(srcHeight) { y0, y1 ->
            for (y in y0 until y1) {
                val row = y * srcWidth
                val out = y * dstWidth
                for (x in 0 until dstWidth) {
                    var acc = 0f
                    val s = row + xa.start[x]
                    val o = xa.offset[x]
                    for (i in 0 until xa.count[x]) acc += xa.weights[o + i] * src[s + i]
                    tmp[out + x] = acc
                }
            }
        }
        Parallel.forRange(dstHeight) { y0, y1 ->
            for (y in y0 until y1) {
                val out = y * dstWidth
                for (x in 0 until dstWidth) dst[out + x] = 0f
                val o = ya.offset[y]
                for (i in 0 until ya.count[y]) {
                    val wgt = ya.weights[o + i]
                    val src0 = (ya.start[y] + i) * dstWidth
                    for (x in 0 until dstWidth) dst[out + x] += wgt * tmp[src0 + x]
                }
            }
        }
    }
}
