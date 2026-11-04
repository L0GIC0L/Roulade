package io.github.l0gic0l.roulade.stab

import io.github.l0gic0l.roulade.dsp.Motion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How stabilisation should behave. */
data class StabilizerSettings(
    /** true: hold the view fixed (tripod). false: only remove shake faster than [smoothSeconds]. */
    val lockView: Boolean = true,
    val smoothSeconds: Float = 1f,
    /** Correct pitch/yaw (image shift), from the gyroscope. */
    val rotation: Boolean = true,
    /** Correct roll (image rotation), from the gyroscope. */
    val roll: Boolean = true,
    /** Use the gravity sensor to cancel gyroscope drift in pitch and roll. */
    val gravityFusion: Boolean = true,
    /** Correct sideways/vertical camera translation from the accelerometer. */
    val translation: Boolean = false,
    /** Distance to the subject, needed to turn camera translation into image shift. */
    val subjectDistanceM: Float = 5f,
    /** Match sensor timing to the video's own motion. */
    val autoSync: Boolean = true,
    /** Extra sensor delay, or the centre of the auto-sync search. */
    val offsetMs: Float = 0f,
)

/** Outcome of time-aligning sensors to the video. */
data class SyncResult(
    val offsetMs: Float,
    /** Normalised correlation between gyro-predicted and measured image motion (0–1). */
    val score: Float,
    val automatic: Boolean,
    /** Extra 90° steps between sensor axes and image axes found by the search. */
    val extraRotationDeg: Int,
    val gainX: Float,
    val gainY: Float,
    val gainRoll: Float,
) {
    companion object {
        fun manual(offsetMs: Float) = SyncResult(offsetMs, 0f, false, 0, 1f, 1f, 1f)
    }
}

/**
 * Turns recorded gyroscope / gravity / accelerometer data into per-frame image corrections.
 *
 * Camera rotation dominates image shake (0.001 rad is ~3 px at a typical phone focal length, while
 * 0.05 mm of translation at 5 m is ~0.03 px), so the gyroscope does most of the work. Gravity
 * removes gyro drift in pitch/roll. The accelerometer is double-integrated (high-passed to stop
 * drift) for optional translation correction of nearby subjects.
 *
 * Image geometry: [bufferToUprightDeg] is the clockwise rotation that turns the encoded frame
 * upright in the device's natural orientation; [focalPx] is the focal length in encoded pixels.
 */
class Stabilizer(
    private val log: SensorLog,
    private val focalPx: Float,
    private val bufferToUprightDeg: Int,
    private val settings: StabilizerSettings,
    private val lowCutHz: Double,
) {
    private val videoStartNs = log.long(SensorLog.VIDEO_START_NS) ?: log.gyro.time(0)

    // Orientation (rad, device axes) and translation (m) on a uniform 1 ms grid.
    private val t0: Long
    private val steps: Int
    private val theta: Array<FloatArray>
    private val position: Array<FloatArray>?

    // Reference trajectory subtracted from the corrections (set by [prepare]).
    private var refTheta: Array<FloatArray>? = null
    private var refPos: Array<FloatArray>? = null

    var sync: SyncResult = SyncResult.manual(settings.offsetMs)
        private set

    init {
        require(log.gyro.size >= 10) { "The sensor log has no gyroscope data." }
        t0 = log.gyro.time(0)
        steps = ((log.gyro.time(log.gyro.size - 1) - t0) / GRID_NS).toInt() + 1
        theta = integrateOrientation()
        position = if (settings.translation) integratePosition() else null
    }

    private fun gridIndex(t: Long): Float = ((t - t0).toDouble() / GRID_NS).toFloat().coerceIn(0f, (steps - 1).toFloat())

    private fun at(series: Array<FloatArray>, axis: Int, t: Long): Float {
        val f = gridIndex(t)
        val i = f.toInt()
        val j = min(i + 1, steps - 1)
        val a = series[axis]
        return a[i] + (a[j] - a[i]) * (f - i)
    }

    /** Gyro integration (trapezoid) with an optional gravity complementary filter, resampled to the grid. */
    private fun integrateOrientation(): Array<FloatArray> {
        val g = log.gyro
        val n = g.size
        val th = Array(3) { DoubleArray(n) }
        val useGravity = settings.gravityFusion && log.gravity.size > 10
        val g0 = FloatArray(3)
        val gi = FloatArray(3)
        if (useGravity) {
            log.gravity.sample(g.time(0), g0)
            normalise(g0)
        }
        for (i in 1 until n) {
            val dt = (g.time(i) - g.time(i - 1)) * 1e-9
            th[0][i] = th[0][i - 1] + 0.5 * (g.x(i) + g.x(i - 1)) * dt
            th[1][i] = th[1][i - 1] + 0.5 * (g.y(i) + g.y(i - 1)) * dt
            th[2][i] = th[2][i - 1] + 0.5 * (g.z(i) + g.z(i - 1)) * dt
            if (useGravity) {
                log.gravity.sample(g.time(i), gi)
                normalise(gi)
                // Rotation seen by gravity (perpendicular to it): theta_perp = -(g0 x g).
                val ox = -(g0[1] * gi[2] - g0[2] * gi[1])
                val oy = -(g0[2] * gi[0] - g0[0] * gi[2])
                val oz = -(g0[0] * gi[1] - g0[1] * gi[0])
                val dot = th[0][i] * g0[0] + th[1][i] * g0[1] + th[2][i] * g0[2]
                val k = (dt / GRAVITY_TIME_CONSTANT_S).coerceAtMost(1.0)
                th[0][i] += k * (ox - (th[0][i] - dot * g0[0]))
                th[1][i] += k * (oy - (th[1][i] - dot * g0[1]))
                th[2][i] += k * (oz - (th[2][i] - dot * g0[2]))
            }
        }
        return Array(3) { axis -> resample(n, { g.time(it) }, th[axis]) }
    }

    /** Double integration of linear acceleration with moving-average high-pass after each step. */
    private fun integratePosition(): Array<FloatArray>? {
        val lin = log.linear
        val acc = log.accel
        val source = if (lin.size > 10) lin else acc
        if (source.size < 10) return null
        val n = source.size
        val grav = FloatArray(3)
        val window = (1.5 / lowCutHz).coerceIn(1.0, 10.0)
        return Array(3) { axis ->
            val a = DoubleArray(n) { i ->
                val raw = when (axis) { 0 -> source.x(i); 1 -> source.y(i); else -> source.z(i) }.toDouble()
                if (source === acc) {
                    log.gravity.sample(source.time(i), grav)
                    raw - grav[axis]
                } else raw
            }
            val times = LongArray(n) { source.time(it) }
            val v = highPass(cumulative(a, times), times, window)
            val p = highPass(cumulative(v, times), times, window)
            resample(n, { times[it] }, p)
        }
    }

    private fun cumulative(x: DoubleArray, t: LongArray): DoubleArray {
        val out = DoubleArray(x.size)
        for (i in 1 until x.size) out[i] = out[i - 1] + 0.5 * (x[i] + x[i - 1]) * (t[i] - t[i - 1]) * 1e-9
        return out
    }

    /** Subtracts a centred moving average of [windowS] seconds. */
    private fun highPass(x: DoubleArray, t: LongArray, windowS: Double): DoubleArray {
        val half = (windowS * 0.5e9).toLong()
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        val out = DoubleArray(x.size)
        var lo = 0
        var hi = 0
        for (i in x.indices) {
            while (t[lo] < t[i] - half) lo++
            while (hi < x.size - 1 && t[hi + 1] <= t[i] + half) hi++
            out[i] = x[i] - (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
        return out
    }

    private fun resample(n: Int, time: (Int) -> Long, values: DoubleArray): FloatArray {
        val out = FloatArray(steps)
        var j = 0
        for (k in 0 until steps) {
            val t = t0 + k * GRID_NS
            while (j < n - 2 && time(j + 1) < t) j++
            val ta = time(j)
            val tb = time(min(j + 1, n - 1))
            val f = if (tb > ta) ((t - ta).toDouble() / (tb - ta)).coerceIn(0.0, 1.0) else 0.0
            out[k] = (values[j] + (values[min(j + 1, n - 1)] - values[j]) * f).toFloat()
        }
        return out
    }

    private fun normalise(v: FloatArray) {
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        v[0] /= m; v[1] /= m; v[2] /= m
    }

    /** Integrated orientation (rad) about device [axis] at sensor time [timeNs]; for diagnostics and tests. */
    internal fun orientation(timeNs: Long, axis: Int): Float = at(theta, axis, timeNs)

    private fun sensorTime(ptsUs: Long, offsetMs: Float): Long = videoStartNs + ptsUs * 1000 + (offsetMs * 1e6f).toLong()

    /** Rotates an upright-image vector into encoded-buffer axes. */
    private fun toBuffer(ux: Float, uy: Float, extraDeg: Int, out: FloatArray) {
        when (((-(bufferToUprightDeg + extraDeg)) % 360 + 360) % 360) {
            0 -> { out[0] = ux; out[1] = uy }
            90 -> { out[0] = -uy; out[1] = ux }
            180 -> { out[0] = -ux; out[1] = -uy }
            else -> { out[0] = uy; out[1] = -ux }
        }
    }

    /**
     * Finds the sensor time offset (and, if needed, axis orientation and gains) that best explains
     * the measured frame-to-frame image motion. [motions][n] is the motion from frame n-1 to n,
     * measured at [imageScale] times the encoded resolution.
     */
    fun calibrate(ptsUs: LongArray, motions: List<Motion>, imageScale: Float): SyncResult {
        if (!settings.autoSync || ptsUs.size < 10) return SyncResult.manual(settings.offsetMs).also { sync = it }
        val n = ptsUs.size
        val px = FloatArray(n)
        val py = FloatArray(n)
        val pr = FloatArray(n)
        val tmp = FloatArray(2)
        var best = SyncResult.manual(settings.offsetMs)
        var bestScore = -1f
        var bestRms = 0f
        var offset = settings.offsetMs - SEARCH_MS
        while (offset <= settings.offsetMs + SEARCH_MS) {
            for (k in 1 until n) {
                val ta = sensorTime(ptsUs[k - 1], offset)
                val tb = sensorTime(ptsUs[k], offset)
                val dX = at(theta, 0, tb) - at(theta, 0, ta)
                val dY = at(theta, 1, tb) - at(theta, 1, ta)
                px[k] = focalPx * dY * imageScale // upright x from rotation about device y
                py[k] = focalPx * dX * imageScale // upright y from rotation about device x
                pr[k] = at(theta, 2, tb) - at(theta, 2, ta)
            }
            for (extra in intArrayOf(0, 90, 180, 270)) {
                var smp = 0.0; var spp = 0.0; var smm = 0.0
                for (k in 1 until n) {
                    toBuffer(px[k], py[k], extra, tmp)
                    val m = motions[k]
                    smp += m.dx * tmp[0] + m.dy * tmp[1]
                    spp += tmp[0] * tmp[0] + tmp[1] * tmp[1]
                    smm += m.dx * m.dx + m.dy * m.dy
                }
                val score = if (spp > 0 && smm > 0) (smp / sqrt(spp * smm)).toFloat() else 0f
                if (score > bestScore) {
                    bestScore = score
                    bestRms = sqrt(spp / (n - 1)).toFloat()
                    best = SyncResult(offset, score, true, extra, 1f, 1f, 1f)
                }
            }
            offset += SEARCH_STEP_MS
        }

        if (bestScore < MIN_SCORE || bestRms < MIN_MOTION_PX) {
            return SyncResult.manual(settings.offsetMs).copy(score = max(bestScore, 0f)).also { sync = it }
        }

        // Per-axis gains at the best alignment (corrects focal length / zoom, and mirrored axes).
        var sxx = 0.0; var spx = 0.0; var syy = 0.0; var spy = 0.0; var srr = 0.0; var spr = 0.0
        for (k in 1 until n) {
            val ta = sensorTime(ptsUs[k - 1], best.offsetMs)
            val tb = sensorTime(ptsUs[k], best.offsetMs)
            toBuffer(
                focalPx * (at(theta, 1, tb) - at(theta, 1, ta)) * imageScale,
                focalPx * (at(theta, 0, tb) - at(theta, 0, ta)) * imageScale,
                best.extraRotationDeg, tmp,
            )
            val r = at(theta, 2, tb) - at(theta, 2, ta)
            val m = motions[k]
            spx += m.dx * tmp[0]; sxx += tmp[0] * tmp[0]
            spy += m.dy * tmp[1]; syy += tmp[1] * tmp[1]
            spr += m.angle * r; srr += r * r
        }
        fun gain(num: Double, den: Double, limit: Float): Float {
            if (den <= 1e-12) return 1f
            val g = (num / den).toFloat()
            return if (abs(g) < 1f / limit) 1f else g.coerceIn(-limit, limit)
        }
        best = best.copy(
            gainX = gain(spx, sxx, 2f),
            gainY = gain(spy, syy, 2f),
            gainRoll = gain(spr, srr, 1.5f),
        )
        sync = best
        return best
    }

    /** Builds the reference trajectory over the frames that will be output. */
    fun prepare(firstPtsUs: Long, lastPtsUs: Long) {
        val a = gridIndex(sensorTime(firstPtsUs, sync.offsetMs)).toInt()
        val b = max(a + 1, gridIndex(sensorTime(lastPtsUs, sync.offsetMs)).toInt())
        refTheta = Array(3) { reference(theta[it], a, b) }
        refPos = position?.let { p -> Array(3) { reference(p[it], a, b) } }
    }

    private fun reference(x: FloatArray, a: Int, b: Int): FloatArray {
        val out = FloatArray(steps)
        if (settings.lockView) {
            // Straight-line fit over the clip: removes the slow gyro bias drift as well.
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            val n = b - a + 1
            for (i in a..b) { val t = (i - a).toDouble(); sx += t; sy += x[i]; sxx += t * t; sxy += t * x[i] }
            val den = n * sxx - sx * sx
            val slope = if (den > 0) (n * sxy - sx * sy) / den else 0.0
            val icept = (sy - slope * sx) / n
            for (i in 0 until steps) out[i] = (icept + slope * (i - a)).toFloat()
        } else {
            val half = (settings.smoothSeconds * 500).toInt().coerceAtLeast(1) // grid is 1 ms
            val prefix = DoubleArray(steps + 1)
            for (i in 0 until steps) prefix[i + 1] = prefix[i] + x[i]
            for (i in 0 until steps) {
                val lo = max(0, i - half)
                val hi = min(steps - 1, i + half)
                out[i] = ((prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)).toFloat()
            }
        }
        return out
    }

    /**
     * Image motion of frame [ptsUs] relative to the reference, in encoded-buffer pixels and
     * radians (clockwise). Warping the frame by this motion holds the view still.
     */
    fun correction(ptsUs: Long): Motion {
        val ref = refTheta ?: error("Call prepare() first")
        val s = sync
        val t = sensorTime(ptsUs, s.offsetMs)
        val tx = at(theta, 0, t) - at(ref, 0, t)
        val ty = at(theta, 1, t) - at(ref, 1, t)
        val tz = at(theta, 2, t) - at(ref, 2, t)
        var ux = 0f
        var uy = 0f
        if (settings.rotation) {
            ux += focalPx * ty
            uy += focalPx * tx
        }
        val pos = position
        val rp = refPos
        if (pos != null && rp != null && settings.subjectDistanceM > 0f) {
            val k = focalPx / settings.subjectDistanceM
            ux -= k * (at(pos, 0, t) - at(rp, 0, t))
            uy += k * (at(pos, 1, t) - at(rp, 1, t))
        }
        val out = FloatArray(2)
        toBuffer(ux, uy, s.extraRotationDeg, out)
        val angle = if (settings.roll) tz * s.gainRoll else 0f
        return Motion(out[0] * s.gainX, out[1] * s.gainY, angle)
    }

    companion object {
        private const val GRID_NS = 1_000_000L
        private const val GRAVITY_TIME_CONSTANT_S = 2.0
        private const val SEARCH_MS = 500f
        private const val SEARCH_STEP_MS = 2f
        private const val MIN_SCORE = 0.4f
        private const val MIN_MOTION_PX = 0.02f

        /**
         * Focal length in encoded pixels from camera metadata: lens focal length over the sensor's
         * long side, scaled to the frame's long side (video crops the short side) and by zoom.
         */
        fun focalPixels(log: SensorLog, frameWidth: Int, frameHeight: Int): Float? {
            val f = log.float(SensorLog.FOCAL_MM) ?: return null
            val sw = log.float(SensorLog.SENSOR_WIDTH_MM) ?: return null
            val sh = log.float(SensorLog.SENSOR_HEIGHT_MM) ?: return null
            val zoom = log.float(SensorLog.ZOOM) ?: 1f
            return f / max(sw, sh) * max(frameWidth, frameHeight) * zoom
        }

        /** Rotation that turns the encoded frame upright in the phone's natural orientation. */
        fun bufferToUpright(log: SensorLog, fileRotationDeg: Int): Int =
            ((fileRotationDeg + (log.int(SensorLog.TARGET_ROTATION) ?: 0)) % 360 + 360) % 360
    }
}
