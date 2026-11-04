package io.github.l0gic0l.roulade.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import io.github.l0gic0l.roulade.stab.SensorLog

/** Logs gyroscope, accelerometer, gravity and linear acceleration on a background thread. */
class SensorRecorder(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var thread: HandlerThread? = null
    private var log: SensorLog? = null

    /** Starts logging into a new [SensorLog] that uses [meta] (which may still be filled in later). */
    fun start(meta: Map<String, String>, fastest: Boolean) {
        stop()
        val l = SensorLog(meta)
        log = l
        val t = HandlerThread("roulade-sensors").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        val rate = if (fastest) SensorManager.SENSOR_DELAY_FASTEST else SensorManager.SENSOR_DELAY_GAME
        for (type in TYPES) {
            manager.getDefaultSensor(type)?.let { manager.registerListener(this, it, rate, handler) }
        }
    }

    /** Stops logging and returns what was recorded. */
    fun stop(): SensorLog? {
        manager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        return log.also { log = null }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val l = log ?: return
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> l.gyro.add(event.timestamp, v[0], v[1], v[2])
            Sensor.TYPE_ACCELEROMETER -> l.accel.add(event.timestamp, v[0], v[1], v[2])
            Sensor.TYPE_GRAVITY -> l.gravity.add(event.timestamp, v[0], v[1], v[2])
            Sensor.TYPE_LINEAR_ACCELERATION -> l.linear.add(event.timestamp, v[0], v[1], v[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private val TYPES = intArrayOf(
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION,
        )

        fun hasGyroscope(context: Context) =
            context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }
}
