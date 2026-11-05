package io.github.l0gic0l.roulade.stab

import java.io.File

/** Growable list of timestamped 3-axis samples. Timestamps are `SensorEvent.timestamp` nanoseconds. */
class SensorSeries {
    var size = 0
        private set
    private var t = LongArray(1024)
    private var x = FloatArray(1024)
    private var y = FloatArray(1024)
    private var z = FloatArray(1024)

    @Synchronized
    fun add(time: Long, a: Float, b: Float, c: Float) {
        if (size == t.size) {
            val n = size * 2
            t = t.copyOf(n); x = x.copyOf(n); y = y.copyOf(n); z = z.copyOf(n)
        }
        t[size] = time; x[size] = a; y[size] = b; z[size] = c
        size++
    }

    fun time(i: Int) = t[i]
    fun x(i: Int) = x[i]
    fun y(i: Int) = y[i]
    fun z(i: Int) = z[i]

    /** Linearly interpolated sample at [time] (clamped at the ends) written to [out]. */
    fun sample(time: Long, out: FloatArray) {
        if (size == 0) { out.fill(0f); return }
        if (time <= t[0]) { out[0] = x[0]; out[1] = y[0]; out[2] = z[0]; return }
        if (time >= t[size - 1]) { out[0] = x[size - 1]; out[1] = y[size - 1]; out[2] = z[size - 1]; return }
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= time) lo = mid else hi = mid
        }
        val f = ((time - t[lo]).toDouble() / (t[hi] - t[lo])).toFloat()
        out[0] = x[lo] + (x[hi] - x[lo]) * f
        out[1] = y[lo] + (y[hi] - y[lo]) * f
        out[2] = z[lo] + (z[hi] - z[lo]) * f
    }

    /** Mean sampling rate in Hz, or 0 when there are too few samples. */
    val rateHz: Double
        get() = if (size < 2) 0.0 else (size - 1) * 1e9 / (t[size - 1] - t[0]).coerceAtLeast(1)
}

/**
 * Motion sensors recorded alongside a video, plus the camera facts needed to turn them into image
 * motion. Stored as a CSV sidecar (`clip.mp4` → `clip.sensors.csv`) so it can also be opened in a
 * spreadsheet. Units: gyro rad/s, accelerations m/s², time ns (elapsed-realtime clock).
 */
class SensorLog(
    val meta: Map<String, String>,
    val gyro: SensorSeries = SensorSeries(),
    val accel: SensorSeries = SensorSeries(),
    val gravity: SensorSeries = SensorSeries(),
    val linear: SensorSeries = SensorSeries(),
) {
    fun long(key: String): Long? = meta[key]?.toLongOrNull()
    fun float(key: String): Float? = meta[key]?.toFloatOrNull()
    fun int(key: String): Int? = meta[key]?.toIntOrNull()

    fun write(file: File) {
        file.bufferedWriter().use { w ->
            w.write("# roulade-sensors v1\n")
            for ((k, v) in meta) w.write("# $k=$v\n")
            w.write("sensor,t_ns,x,y,z\n")
            for ((name, s) in listOf(GYRO to gyro, ACCEL to accel, GRAVITY to gravity, LINEAR to linear)) {
                for (i in 0 until s.size) {
                    w.write(name); w.write(",")
                    w.write(s.time(i).toString()); w.write(",")
                    w.write(s.x(i).toString()); w.write(",")
                    w.write(s.y(i).toString()); w.write(",")
                    w.write(s.z(i).toString()); w.write("\n")
                }
            }
        }
    }

    companion object {
        const val GYRO = "gyro"
        const val ACCEL = "accel"
        const val GRAVITY = "gravity"
        const val LINEAR = "linear"

        /** Elapsed-realtime ns at which the recorder reported the video started. */
        const val VIDEO_START_NS = "video_start_ns"
        /** Display rotation (degrees) the camera was bound with. */
        const val TARGET_ROTATION = "target_rotation_deg"
        const val SENSOR_ORIENTATION = "sensor_orientation_deg"
        const val FOCAL_MM = "focal_length_mm"
        const val SENSOR_WIDTH_MM = "sensor_width_mm"
        const val SENSOR_HEIGHT_MM = "sensor_height_mm"
        const val ZOOM = "zoom_ratio"
        const val FPS = "target_fps"
        const val DEVICE = "device"

        fun sidecarFor(video: File) = File(video.parentFile, video.nameWithoutExtension + ".sensors.csv")

        fun read(file: File): SensorLog {
            val meta = LinkedHashMap<String, String>()
            val log = SensorLog(meta)
            file.forEachLine { line ->
                when {
                    line.startsWith("#") -> {
                        val eq = line.indexOf('=')
                        if (eq > 2) meta[line.substring(2, eq).trim()] = line.substring(eq + 1).trim()
                    }
                    line.startsWith("sensor") || line.isBlank() -> Unit
                    else -> {
                        val p = line.split(',')
                        if (p.size == 5) {
                            val series = when (p[0]) {
                                GYRO -> log.gyro
                                ACCEL -> log.accel
                                GRAVITY -> log.gravity
                                LINEAR -> log.linear
                                else -> null
                            }
                            series?.add(p[1].toLong(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
                        }
                    }
                }
            }
            return log
        }
    }
}
