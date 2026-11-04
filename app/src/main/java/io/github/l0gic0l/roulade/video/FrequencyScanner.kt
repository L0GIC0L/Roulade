package io.github.l0gic0l.roulade.video

import android.content.Context
import io.github.l0gic0l.roulade.dsp.ImageOps
import io.github.l0gic0l.roulade.dsp.MotionEstimator
import io.github.l0gic0l.roulade.dsp.Spectrum
import io.github.l0gic0l.roulade.dsp.SpectrumAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the frequencies things vibrate at: tracks a grid of small regions at low resolution,
 * optionally subtracts the common (camera) motion, and sums their displacement spectra.
 */
class FrequencyScanner(private val context: Context) {

    suspend fun scan(
        clip: Clip,
        fps: Double,
        startS: Float,
        endS: Float?,
        rejectCameraShake: Boolean,
        onProgress: (Float) -> Unit,
    ): Spectrum = withContext(Dispatchers.Default) {
        val startUs = (startS * 1e6).toLong()
        val endUs = endS?.let { (it * 1e6).toLong() } ?: Long.MAX_VALUE
        val durationUs = (min(endUs, clip.info.durationMs * 1000) - startUs).coerceAtLeast(1)
        val kernel = ImageOps.gaussianKernel(1f)
        var scaler: FrameScaler? = null
        var tracker: MotionEstimator.BlockTracker? = null
        var tmp = FloatArray(0)
        var dx = FloatArray(0)
        var dy = FloatArray(0)
        val xs = ArrayList<FloatArray>()
        val ys = ArrayList<FloatArray>()

        FrameReader(context, clip.uri).use { reader ->
            reader.read(startUs, endUs) { image, pts ->
                if (pts < startUs) return@read true
                val crop = image.cropRect
                val s = scaler ?: run {
                    val scale = min(1f, SCAN_SHORT_SIDE.toFloat() / min(crop.width(), crop.height()))
                    FrameScaler(
                        crop.width(), crop.height(),
                        max(32, (crop.width() * scale).roundToInt()), max(32, (crop.height() * scale).roundToInt()),
                        withChroma = false,
                    ).also { scaler = it; tmp = FloatArray(it.width * it.height) }
                }
                s.load(image)
                ImageOps.blur(s.y, s.width, s.height, kernel, tmp)
                val t = tracker ?: run {
                    val landscape = s.width >= s.height
                    MotionEstimator.BlockTracker(s.y, s.width, s.height, if (landscape) 8 else 6, if (landscape) 6 else 8)
                        .also { tracker = it; dx = FloatArray(it.count); dy = FloatArray(it.count) }
                }
                t.measure(s.y, dx, dy)
                xs += dx.copyOf()
                ys += dy.copyOf()
                onProgress(((pts - startUs).toFloat() / durationUs).coerceIn(0f, 1f))
                xs.size < MAX_FRAMES
            }
        }
        val t = tracker ?: throw IllegalStateException("No frames could be decoded from that video.")
        val n = xs.size
        if (n < 16) throw IllegalArgumentException("The clip is too short to analyse; keep at least half a second.")
        val blocks = (0 until t.count).filter { t.valid[it] }
        if (blocks.isEmpty()) throw IllegalArgumentException("Not enough detail in the picture to track motion.")

        if (rejectCameraShake) {
            // Subtract the median block motion per frame: whole-picture motion is the camera.
            val tmpX = FloatArray(blocks.size)
            val tmpY = FloatArray(blocks.size)
            for (f in 0 until n) {
                for ((j, b) in blocks.withIndex()) { tmpX[j] = xs[f][b]; tmpY[j] = ys[f][b] }
                tmpX.sort(); tmpY.sort()
                val mx = tmpX[blocks.size / 2]
                val my = tmpY[blocks.size / 2]
                for (b in blocks) { xs[f][b] -= mx; ys[f][b] -= my }
            }
        }
        val series = ArrayList<FloatArray>()
        for (b in blocks) {
            series += FloatArray(n) { xs[it][b] }
            series += FloatArray(n) { ys[it][b] }
        }
        val minHz = max(0.1, 2.0 * fps / n)
        SpectrumAnalysis.analyse(series, fps, minHz, 0.95 * fps / 2)
    }

    private companion object {
        const val SCAN_SHORT_SIDE = 200
        const val MAX_FRAMES = 3600
    }
}
