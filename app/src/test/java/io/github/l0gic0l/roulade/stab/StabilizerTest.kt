package io.github.l0gic0l.roulade.stab

import io.github.l0gic0l.roulade.dsp.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class StabilizerTest {
    private val start = 5_000_000_000L // video start on the sensor clock, ns
    private val fps = 30.0
    private val frames = 150
    private val focal = 1000f

    /** Handheld-like shake: pitch and yaw at different frequencies, a little roll. */
    private fun thetaX(t: Double) = 0.004 * sin(2 * PI * 2.1 * t) + 0.002 * sin(2 * PI * 5.3 * t)
    private fun thetaY(t: Double) = 0.003 * sin(2 * PI * 1.3 * t + 1.0) + 0.002 * sin(2 * PI * 4.1 * t)
    private fun thetaZ(t: Double) = 0.002 * sin(2 * PI * 0.9 * t)

    private fun log(gyroBias: Float = 0f): SensorLog {
        val log = SensorLog(mapOf(SensorLog.VIDEO_START_NS to start.toString()))
        val dt = 1.0 / 400
        var t = 0.0
        while (t < 7.0) {
            val ns = (start - 1_000_000_000L) + (t * 1e9).toLong()
            val ts = (ns - start) * 1e-9
            val h = 1e-5
            log.gyro.add(
                ns,
                ((thetaX(ts + h) - thetaX(ts - h)) / (2 * h)).toFloat() + gyroBias,
                ((thetaY(ts + h) - thetaY(ts - h)) / (2 * h)).toFloat(),
                ((thetaZ(ts + h) - thetaZ(ts - h)) / (2 * h)).toFloat(),
            )
            // Gravity consistent with the rotation: in device axes g becomes g + g x theta.
            log.gravity.add(ns, (9.81 * thetaZ(ts)).toFloat(), 9.81f, (-9.81 * thetaX(ts)).toFloat())
            t += dt
        }
        return log
    }

    /** Image motion the camera would really see, for a sensor delay of [delayMs] and orientation 90°. */
    private fun upright(t: Double) = floatArrayOf((focal * thetaY(t)).toFloat(), (focal * thetaX(t)).toFloat())
    private fun buffer90(u: FloatArray) = floatArrayOf(u[1], -u[0]) // R(-90)

    @Test
    fun autoSyncFindsDelayAndOrientation() {
        val delayMs = 70f
        val scale = 0.25f
        val pts = LongArray(frames) { (it * 1e6 / fps).toLong() }
        val motions = List(frames) { k ->
            if (k == 0) Motion.ZERO else {
                val ta = pts[k - 1] * 1e-6 + delayMs / 1000.0
                val tb = pts[k] * 1e-6 + delayMs / 1000.0
                val a = buffer90(upright(ta))
                val b = buffer90(upright(tb))
                Motion((b[0] - a[0]) * scale, (b[1] - a[1]) * scale, (thetaZ(tb) - thetaZ(ta)).toFloat())
            }
        }
        // Tell the stabiliser the frame is upright as-is (0°); the search must find the extra 90°.
        val s = Stabilizer(log(), focal, 0, StabilizerSettings(gravityFusion = false), lowCutHz = 1.0)
        val r = s.calibrate(pts, motions, scale)
        assertTrue("automatic", r.automatic)
        assertEquals(delayMs, r.offsetMs, 3f)
        assertEquals(90, r.extraRotationDeg)
        assertEquals(1f, r.gainX, 0.1f)
        assertEquals(1f, r.gainY, 0.1f)
        assertTrue("score ${r.score}", r.score > 0.95f)

        s.prepare(pts.first(), pts.last())
        // Corrections must follow the true shake (up to the straight-line reference).
        val c0 = s.correction(pts[40])
        val c1 = s.correction(pts[47])
        val e0 = buffer90(upright(pts[40] * 1e-6 + delayMs / 1000.0))
        val e1 = buffer90(upright(pts[47] * 1e-6 + delayMs / 1000.0))
        assertEquals(e1[0] - e0[0], c1.dx - c0.dx, 0.3f)
        assertEquals(e1[1] - e0[1], c1.dy - c0.dy, 0.3f)
    }

    @Test
    fun withoutShakeItFallsBackToTheManualOffset() {
        val pts = LongArray(frames) { (it * 1e6 / fps).toLong() }
        val noise = List(frames) { Motion(0.001f * cos(it.toFloat()), 0f, 0f) }
        val s = Stabilizer(log(), focal, 90, StabilizerSettings(offsetMs = 12f, gravityFusion = false), lowCutHz = 1.0)
        val r = s.calibrate(pts, noise, 0.25f)
        assertTrue(!r.automatic)
        assertEquals(12f, r.offsetMs, 0.01f)
    }

    @Test
    fun gravityCancelsGyroDrift() {
        val bias = 0.02f // rad/s of uncorrected gyro bias about x
        val end = start + 5_900_000_000L
        val truth = thetaX((end - start) * 1e-9).toFloat()
        fun error(fusion: Boolean): Float {
            val s = Stabilizer(log(gyroBias = bias), focal, 0, StabilizerSettings(gravityFusion = fusion), lowCutHz = 1.0)
            // Orientation is relative to the first gyro sample, 1 s before the video starts.
            val zero = thetaX(-1.0).toFloat()
            return abs(s.orientation(end, 0) - (truth - zero))
        }
        val raw = error(false)
        val fused = error(true)
        assertTrue("raw error $raw rad vs fused $fused rad", raw > 0.1f && fused < raw / 2)
    }
}
