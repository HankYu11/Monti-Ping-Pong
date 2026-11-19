package com.monti.servecoach

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.monti.servecoach.core.TablePose

/**
 * Which way is down, from the accelerometer, while the app is open (19 Nov): recorded with every
 * capture and the report, and the table's pose is worked out against it. Smoothed (about a
 * quarter of a second) so a tap on the screen doesn't move it. The app is fixed in landscape
 * with the top of the phone to the left (ROTATION_90), which [TablePose.downFromAccel] assumes.
 */
class Gravity(ctx: Context) : SensorEventListener {
    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile private var a = doubleArrayOf(9.81, 0.0, 0.0)
    @Volatile var seen = false; private set

    fun start() { sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sensors.unregisterListener(this) }

    /** down in camera axes (x right, y down, z toward the camera), or null before any reading */
    fun down(): DoubleArray? = if (!seen) null else a.let { TablePose.downFromAccel(it[0], it[1], it[2]) }

    override fun onSensorChanged(e: SensorEvent) {
        val k = if (seen) 0.08 else 1.0
        val o = a
        a = doubleArrayOf(o[0] + k * (e.values[0] - o[0]), o[1] + k * (e.values[1] - o[1]), o[2] + k * (e.values[2] - o[2]))
        seen = true
    }
    override fun onAccuracyChanged(s: Sensor?, acc: Int) {}
}
