package io.github.l0gic0l.roulade.dsp

import java.util.stream.IntStream

/** Splits simple loops across CPU cores using the shared fork/join pool. */
internal object Parallel {
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * Calls [body] with contiguous, non-overlapping sub-ranges that together cover `[0, count)`.
     * Small ranges run on the calling thread.
     */
    fun forRange(count: Int, minChunk: Int = 8, body: (start: Int, end: Int) -> Unit) {
        if (count <= 0) return
        val chunks = minOf(cores * 2, (count + minChunk - 1) / minChunk).coerceAtLeast(1)
        if (chunks == 1) {
            body(0, count)
            return
        }
        IntStream.range(0, chunks).parallel().forEach { c ->
            val start = (count.toLong() * c / chunks).toInt()
            val end = (count.toLong() * (c + 1) / chunks).toInt()
            body(start, end)
        }
    }
}
