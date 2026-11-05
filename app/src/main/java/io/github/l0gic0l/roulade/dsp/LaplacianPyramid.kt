package io.github.l0gic0l.roulade.dsp

/**
 * Burt–Adelson Laplacian pyramid with preallocated storage.
 *
 * Level `k` holds the detail lost between Gaussian level `k` and `k + 1`; [residual] holds the
 * coarsest low-pass image. [collapse] reconstructs the input exactly when the bands are untouched.
 */
class LaplacianPyramid(val width: Int, val height: Int, val levels: Int) {
    val widths = IntArray(levels + 1)
    val heights = IntArray(levels + 1)

    init {
        require(levels >= 1) { "levels must be >= 1" }
        widths[0] = width
        heights[0] = height
        for (k in 1..levels) {
            widths[k] = ImageOps.half(widths[k - 1])
            heights[k] = ImageOps.half(heights[k - 1])
        }
    }

    /** Band-pass levels `0 until levels`, finest first. */
    val bands = Array(levels) { FloatArray(widths[it] * heights[it]) }

    /** Low-pass image at level [levels]. */
    val residual = FloatArray(widths[levels] * heights[levels])

    // Gaussian levels 1..levels-1 (level 0 is the input itself, the last one is `residual`).
    private val gauss = Array(levels + 1) { k ->
        when (k) {
            0 -> FloatArray(0)
            levels -> residual
            else -> FloatArray(widths[k] * heights[k])
        }
    }
    private val tmp = FloatArray(width * height)
    private val up = FloatArray(width * height)

    fun build(image: FloatArray) {
        var current = image
        for (k in 0 until levels) {
            val w = widths[k]
            val h = heights[k]
            val next = gauss[k + 1]
            ImageOps.reduce(current, w, h, next, tmp)
            ImageOps.expand(next, w, h, up, tmp)
            val band = bands[k]
            val src = current
            Parallel.forRange(w * h, minChunk = 4096) { s, e ->
                for (i in s until e) band[i] = src[i] - up[i]
            }
            current = next
        }
    }

    /** Rebuilds the image from [bands] and [residual] into [out] (`width x height`). */
    fun collapse(out: FloatArray) {
        var current = residual
        for (k in levels - 1 downTo 0) {
            val w = widths[k]
            val h = heights[k]
            val target = if (k == 0) out else gauss[k]
            ImageOps.expand(current, w, h, up, tmp)
            val band = bands[k]
            Parallel.forRange(w * h, minChunk = 4096) { s, e ->
                for (i in s until e) target[i] = band[i] + up[i]
            }
            current = target
        }
    }

    companion object {
        /** Number of band-pass levels so the coarsest one is still at least [minSize] pixels. */
        fun levelsFor(width: Int, height: Int, minSize: Int = 32, maxLevels: Int = 8): Int {
            var n = 0
            var m = minOf(width, height)
            while (m >= minSize && n < maxLevels) {
                n++
                m = ImageOps.half(m)
            }
            return n.coerceAtLeast(1)
        }
    }
}
