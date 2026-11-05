package io.github.l0gic0l.roulade.video

import android.content.Context
import android.net.Uri
import io.github.l0gic0l.roulade.analysis.AnalysisSettings
import io.github.l0gic0l.roulade.analysis.ViewMode
import io.github.l0gic0l.roulade.dsp.ImageOps
import io.github.l0gic0l.roulade.dsp.MagnifierOptions
import io.github.l0gic0l.roulade.dsp.Motion
import io.github.l0gic0l.roulade.dsp.MotionEstimator
import io.github.l0gic0l.roulade.dsp.RieszMagnifier
import io.github.l0gic0l.roulade.dsp.Warp
import io.github.l0gic0l.roulade.stab.SensorLog
import io.github.l0gic0l.roulade.stab.Stabilizer
import io.github.l0gic0l.roulade.stab.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A video to analyse, with its sensor log when it was recorded in Roulade. */
class Clip(val uri: Uri, val info: VideoInfo, val sensorLog: File?, val name: String)

/** What happened during processing, for the result screen. */
data class AmplifyReport(
    val width: Int,
    val height: Int,
    val frames: Int,
    val stabilized: Boolean,
    val sync: SyncResult?,
    /** RMS stabilisation correction in output pixels. */
    val correctionRmsPx: Float,
    val notes: List<String>,
)

/**
 * Runs the whole analysis: optional sensor time-sync pass, then decode → stabilise → magnify →
 * compose view → encode. Colour is carried over, audio is dropped.
 */
class VideoAmplifier(private val context: Context) {

    suspend fun amplify(
        clip: Clip,
        settings: AnalysisSettings,
        output: File,
        onProgress: (stage: String, fraction: Float) -> Unit,
    ): AmplifyReport = withContext(Dispatchers.Default) {
        val info = clip.info
        val fps = settings.captureFps ?: info.frameRate.toDouble()
        val startUs = (settings.trimStartS * 1e6).toLong()
        val endUs = settings.trimEndS?.let { (it * 1e6).toLong() } ?: Long.MAX_VALUE
        val durationUs = (min(endUs, info.durationMs * 1000) - startUs).coerceAtLeast(1)
        val notes = ArrayList<String>()

        // Stabiliser from the sensor log, when there is one and it's wanted.
        var stabilizer: Stabilizer? = null
        if (settings.stabilize && clip.sensorLog?.exists() == true) {
            val log = SensorLog.read(clip.sensorLog)
            val focal = Stabilizer.focalPixels(log, info.width, info.height)
            if (focal == null || log.gyro.size < 10) {
                notes += "Sensor log is incomplete; stabilisation skipped."
            } else {
                stabilizer = Stabilizer(
                    log, focal, Stabilizer.bufferToUpright(log, info.rotation),
                    settings.stabilizer, settings.lowHz,
                )
            }
        } else if (settings.stabilize && clip.sensorLog == null) {
            notes += "No sensor log (clip wasn't recorded in Roulade); stabilisation skipped."
        }

        val progressSplit = if (stabilizer != null && settings.stabilizer.autoSync) 0.25f else 0f
        if (stabilizer != null) {
            if (settings.stabilizer.autoSync) {
                onProgress("Syncing sensors to video", 0f)
                val (pts, motions, scale) = measureGlobalMotion(clip.uri, startUs, endUs, durationUs) {
                    onProgress("Syncing sensors to video", it * progressSplit)
                }
                val sync = stabilizer.calibrate(pts, motions, scale)
                if (!sync.automatic) {
                    notes += "Too little camera shake to auto-sync sensors (score ${"%.2f".format(sync.score)}); used the manual offset."
                }
            } else {
                stabilizer.calibrate(LongArray(0), emptyList(), 1f)
            }
            stabilizer.prepare(startUs, min(endUs, info.durationMs * 1000))
        }

        val report = mainPass(clip, settings, fps, startUs, endUs, durationUs, stabilizer, output) { f ->
            onProgress("Amplifying motion", progressSplit + f * (1 - progressSplit))
        }
        onProgress("Done", 1f)
        report.copy(notes = notes + report.notes)
    }

    /** Frame-to-frame global motion at low resolution, for sensor time-sync. */
    private suspend fun measureGlobalMotion(
        uri: Uri, startUs: Long, endUs: Long, durationUs: Long, onProgress: (Float) -> Unit,
    ): Triple<LongArray, List<Motion>, Float> {
        val pts = ArrayList<Long>()
        val motions = ArrayList<Motion>()
        var scaler: FrameScaler? = null
        var prev: FloatArray? = null
        var cur: FloatArray? = null
        val kernel = ImageOps.gaussianKernel(1f)
        var tmp = FloatArray(0)
        FrameReader(context, uri).use { reader ->
            reader.read(startUs, endUs) { image, p ->
                if (p < startUs) return@read true
                val crop = image.cropRect
                val s = scaler ?: run {
                    val scale = min(1f, SYNC_SHORT_SIDE.toFloat() / min(crop.width(), crop.height()))
                    FrameScaler(
                        crop.width(), crop.height(),
                        max(32, (crop.width() * scale).roundToInt()), max(32, (crop.height() * scale).roundToInt()),
                        withChroma = false,
                    ).also { scaler = it; tmp = FloatArray(it.width * it.height) }
                }
                s.load(image)
                val frame = cur ?: FloatArray(s.y.size)
                s.y.copyInto(frame)
                ImageOps.blur(frame, s.width, s.height, kernel, tmp)
                val previous = prev
                motions += if (previous == null) Motion.ZERO else MotionEstimator.global(previous, frame, s.width, s.height)
                pts += p
                // Swap buffers.
                cur = previous
                prev = frame
                onProgress(((p - startUs).toFloat() / durationUs).coerceIn(0f, 1f))
                true
            }
        }
        val s = scaler ?: throw IllegalStateException("No frames could be decoded from that video.")
        return Triple(pts.toLongArray(), motions, s.width.toFloat() / s.srcWidth)
    }

    private suspend fun mainPass(
        clip: Clip,
        settings: AnalysisSettings,
        fps: Double,
        startUs: Long,
        endUs: Long,
        durationUs: Long,
        stabilizer: Stabilizer?,
        output: File,
        onProgress: (Float) -> Unit,
    ): AmplifyReport {
        val info = clip.info
        var scaler: FrameScaler? = null
        var magnifier: RieszMagnifier? = null
        var writer: FrameWriter? = null
        var layout: Layout? = null
        val warpY = ArrayList<FloatArray>(1)
        val warpU = ArrayList<FloatArray>(1)
        val warpV = ArrayList<FloatArray>(1)
        var amplified = FloatArray(0)
        var heat = FloatArray(0)
        var heatPeak = 0f
        var frames = 0
        var corrSq = 0.0
        val invZoom = if (stabilizer != null) 1f - 2f * settings.cropPercent / 100f else 1f
        val speed = settings.playbackSpeed.coerceIn(0.05f, 1f)

        try {
            FrameReader(context, clip.uri).use { reader ->
                reader.read(startUs, endUs) { image, pts ->
                    val crop = image.cropRect
                    val s = scaler ?: run {
                        val (w, h) = outputSize(crop.width(), crop.height(), settings.shortSide)
                        FrameScaler(crop.width(), crop.height(), w, h, withChroma = true).also { sc ->
                            scaler = sc
                            magnifier = RieszMagnifier(
                                w, h,
                                MagnifierOptions(
                                    alpha = settings.alpha,
                                    lowHz = settings.lowHz,
                                    highHz = settings.highHz,
                                    frameRateHz = fps,
                                    filterOrder = settings.filterOrder,
                                    denoiseSigma = settings.denoiseSigma,
                                    skipFineLevels = settings.skipFineLevels,
                                    phaseLimit = settings.phaseLimit,
                                    trackEnergy = settings.viewMode == ViewMode.HEATMAP,
                                ),
                            )
                            amplified = FloatArray(w * h)
                            if (settings.viewMode == ViewMode.HEATMAP) heat = FloatArray(w * h)
                            if (stabilizer != null) {
                                warpY += FloatArray(w * h); warpU += FloatArray(sc.u.size); warpV += FloatArray(sc.v.size)
                            }
                            val l = Layout(settings.viewMode, w, h, info.rotation)
                            layout = l
                            writer = FrameWriter(
                                output, l.width, l.height, (info.frameRate * speed),
                                settings.quality.bitsPerPixel, info.rotation,
                            )
                        }
                    }
                    s.load(image)
                    var y = s.y
                    var u = s.u
                    var v = s.v
                    if (stabilizer != null) {
                        val c = stabilizer.correction(pts)
                        val k = s.width.toFloat() / s.srcWidth
                        val dx = c.dx * k
                        val dy = c.dy * k
                        if (pts >= startUs) corrSq += dx * dx + dy * dy
                        Warp.apply(s.y, s.width, s.height, warpY[0], c.angle, invZoom, dx, dy, bicubic = true)
                        Warp.apply(s.u, s.width / 2, s.height / 2, warpU[0], c.angle, invZoom, dx / 2, dy / 2, bicubic = false)
                        Warp.apply(s.v, s.width / 2, s.height / 2, warpV[0], c.angle, invZoom, dx / 2, dy / 2, bicubic = false)
                        y = warpY[0]; u = warpU[0]; v = warpV[0]
                    }
                    val mag = magnifier!!
                    mag.process(y, amplified)
                    // Frames before the trim start only settle the temporal filter.
                    if (pts >= startUs) {
                        if (settings.viewMode == ViewMode.HEATMAP) {
                            mag.heatmap(heat)
                            var peak = 0f
                            for (h in heat) if (h > peak) peak = h
                            heatPeak = max(heatPeak * 0.97f, peak)
                        }
                        val l = layout!!
                        l.compose(y, amplified, u, v, settings, heat, heatPeak)
                        writer!!.write(l.y, l.u, l.v, ((pts - startUs) / speed).toLong())
                        frames++
                        onProgress(((pts - startUs).toFloat() / durationUs).coerceIn(0f, 0.99f))
                    }
                    true
                }
            }
            val w = writer ?: throw IllegalStateException("No frames could be decoded from that video.")
            w.finish()
            val l = layout!!
            return AmplifyReport(
                width = l.width, height = l.height, frames = frames,
                stabilized = stabilizer != null, sync = stabilizer?.sync,
                correctionRmsPx = if (frames > 0) sqrt(corrSq / frames).toFloat() else 0f,
                notes = emptyList(),
            )
        } finally {
            writer?.close()
        }
    }

    /** Arranges the output frame for the chosen [ViewMode]. */
    private class Layout(val mode: ViewMode, val w: Int, val h: Int, rotation: Int) {
        // Side-by-side as the viewer sees it: stack along whichever encoded axis is horizontal on screen.
        private val vertical = mode == ViewMode.SIDE_BY_SIDE && rotation % 180 != 0
        // Which half holds the original so it appears on the left after rotation.
        private val originalFirst = when (rotation) { 0, 270 -> true; else -> false }
        val width = if (mode == ViewMode.SIDE_BY_SIDE && !vertical) w * 2 else w
        val height = if (vertical) h * 2 else h
        val y = FloatArray(width * height)
        val u = FloatArray((width / 2) * (height / 2))
        val v = FloatArray(u.size)
        private val gray = FloatArray((w / 2) * (h / 2)) { 128f }

        fun compose(
            original: FloatArray, amplified: FloatArray, srcU: FloatArray, srcV: FloatArray,
            s: AnalysisSettings, heat: FloatArray, heatPeak: Float,
        ) {
            when (mode) {
                ViewMode.AMPLIFIED -> {
                    amplified.copyInto(y)
                    chroma(srcU, srcV, s.grayscale)
                }
                ViewMode.MOTION_ONLY -> {
                    for (i in 0 until w * h) y[i] = 128f + MOTION_GAIN * (amplified[i] - original[i])
                    u.fill(128f); v.fill(128f)
                }
                ViewMode.HEATMAP -> heatmap(amplified, srcU, srcV, s, heat, heatPeak)
                ViewMode.SIDE_BY_SIDE -> {
                    val first = if (originalFirst) original else amplified
                    val second = if (originalFirst) amplified else original
                    place(first, w, h, y, 0, 0)
                    if (vertical) place(second, w, h, y, 0, h) else place(second, w, h, y, w, 0)
                    val cu = if (s.grayscale) gray else srcU
                    val cv = if (s.grayscale) gray else srcV
                    place(cu, w / 2, h / 2, u, 0, 0); place(cv, w / 2, h / 2, v, 0, 0)
                    if (vertical) {
                        place(cu, w / 2, h / 2, u, 0, h / 2); place(cv, w / 2, h / 2, v, 0, h / 2)
                    } else {
                        place(cu, w / 2, h / 2, u, w / 2, 0); place(cv, w / 2, h / 2, v, w / 2, 0)
                    }
                }
            }
        }

        private fun chroma(srcU: FloatArray, srcV: FloatArray, gray: Boolean) {
            if (gray) { u.fill(128f); v.fill(128f) } else { srcU.copyInto(u); srcV.copyInto(v) }
        }

        private fun place(src: FloatArray, sw: Int, sh: Int, dst: FloatArray, ox: Int, oy: Int) {
            val dw = if (dst === y) width else width / 2
            for (r in 0 until sh) System.arraycopy(src, r * sw, dst, (oy + r) * dw + ox, sw)
        }

        private fun heatmap(amplified: FloatArray, srcU: FloatArray, srcV: FloatArray, s: AnalysisSettings, heat: FloatArray, peak: Float) {
            val norm = if (peak > 1e-6f) 1f / peak else 0f
            val cw = w / 2
            for (cy in 0 until h / 2) for (cx in 0 until cw) {
                // Chroma sample from the 2x2 luma block.
                val i = (2 * cy) * w + 2 * cx
                val hv = (heat[i] + heat[i + 1] + heat[i + w] + heat[i + w + 1]) * 0.25f * norm
                val a = s.heatmapOpacity * smoothstep(0.15f, 0.6f, hv)
                val lut = ((hv.coerceIn(0f, 1f)) * 255).toInt() * 3
                val ci = cy * cw + cx
                val bu = if (s.grayscale) 128f else srcU[ci]
                val bv = if (s.grayscale) 128f else srcV[ci]
                u[ci] = bu + (COLORMAP[lut + 1] - bu) * a
                v[ci] = bv + (COLORMAP[lut + 2] - bv) * a
                val ly = COLORMAP[lut]
                val ay = a * 0.5f
                y[i] = amplified[i] + (ly - amplified[i]) * ay
                y[i + 1] = amplified[i + 1] + (ly - amplified[i + 1]) * ay
                y[i + w] = amplified[i + w] + (ly - amplified[i + w]) * ay
                y[i + w + 1] = amplified[i + w + 1] + (ly - amplified[i + w + 1]) * ay
            }
        }

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }

    private companion object {
        const val SYNC_SHORT_SIDE = 240
        const val MOTION_GAIN = 3f

        /** Blue → cyan → green → yellow → red, as Y, U, V triples (BT.601). */
        val COLORMAP: FloatArray = run {
            val stops = arrayOf(
                floatArrayOf(0f, 40f, 40f, 160f),
                floatArrayOf(0.25f, 30f, 140f, 240f),
                floatArrayOf(0.5f, 40f, 210f, 110f),
                floatArrayOf(0.75f, 250f, 210f, 40f),
                floatArrayOf(1f, 230f, 40f, 30f),
            )
            FloatArray(256 * 3) { idx ->
                val k = idx / 3
                val t = k / 255f
                var a = stops[0]
                var b = stops[stops.size - 1]
                for (j in 0 until stops.size - 1) if (t >= stops[j][0] && t <= stops[j + 1][0]) { a = stops[j]; b = stops[j + 1] }
                val f = if (b[0] > a[0]) (t - a[0]) / (b[0] - a[0]) else 0f
                val r = a[1] + (b[1] - a[1]) * f
                val g = a[2] + (b[2] - a[2]) * f
                val bl = a[3] + (b[3] - a[3]) * f
                when (idx % 3) {
                    0 -> 0.299f * r + 0.587f * g + 0.114f * bl
                    1 -> -0.169f * r - 0.331f * g + 0.5f * bl + 128f
                    else -> 0.5f * r - 0.419f * g - 0.081f * bl + 128f
                }
            }
        }
    }
}
