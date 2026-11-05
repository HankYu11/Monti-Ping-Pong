package com.monti.servecoach

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.monti.servecoach.core.SetupCheck
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Setting the phone up (0.9.1): while the preview runs before Start, a small copy of the picture
 * (an extra camera output) is checked a few times a second with [SetupCheck], with the phone's tilt
 * and movement from its sensors. Advice that holds for a moment is passed on; so is a JPEG of the
 * picture about once a second, for the AI coach if it's on.
 */
class SetupGuide(
    private val ctx: Context,
    cameraId: String,
    private val handler: Handler,
    /** the display's rotation (Surface.ROTATION_*), for which way is level */
    private val rotation: () -> Int,
    /** new advice that held for [HOLD_MS] (on [handler]'s thread) */
    private val onAdvice: (SetupCheck.Advice) -> Unit,
    /** a JPEG of the picture, about once a second (on [handler]'s thread) */
    private val onJpeg: ((ByteArray) -> Unit)? = null,
) : SensorEventListener {
    val size: Size = smallest16x9(cameraId)
    private val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
    val surface: Surface get() = reader.surface
    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @Volatile private var up = floatArrayOf(0f, 0f, 9.8f)
    @Volatile private var movedAt = 0L
    private var lastCheck = 0L; private var lastJpeg = 0L
    private var pending: SetupCheck.Kind? = null; private var pendingSince = 0L; private var shown: SetupCheck.Kind? = null
    private val w = 320; private val h = 180
    private val y = ByteArray(w * h); private val u = ByteArray(w * h); private val v = ByteArray(w * h)

    /** the latest advice and the table as found (for the AI coach's prompt, the report) */
    @Volatile var latest: SetupCheck.Advice? = null; private set
    @Volatile var latestTable: SetupCheck.Table? = null; private set

    fun start() {
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler) }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler) }
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { onImage(it) } }, handler)
    }
    fun stop() {
        sensors.unregisterListener(this)
        reader.setOnImageAvailableListener(null, null)
        handler.post { try { reader.close() } catch (_: Exception) {} }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> up = e.values.copyOf()
            Sensor.TYPE_GYROSCOPE -> if (sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) > 0.25f) movedAt = SystemClock.elapsedRealtime()
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** the phone's roll from level in landscape: the "up" the accelerometer reads, along the screen's width */
    private fun tilt(): Float {
        val a = up
        if (abs(a[2]) > 8.5f) return 0f              // lying flat (pointing at the floor or ceiling): no horizon to keep
        return when (rotation()) {
            Surface.ROTATION_90 -> Math.toDegrees(atan2(a[1].toDouble(), a[0].toDouble())).toFloat()
            Surface.ROTATION_270 -> Math.toDegrees(atan2(-a[1].toDouble(), -a[0].toDouble())).toFloat()
            else -> 0f
        }
    }

    private fun onImage(img: android.media.Image) {
        val now = SystemClock.elapsedRealtime()
        if (onJpeg != null && now - lastJpeg >= 1000) { lastJpeg = now; onJpeg.invoke(jpeg(img)) }
        if (now - lastCheck < 300) return
        lastCheck = now
        sample(img)
        val (a, t) = SetupCheck.advise(y, u, v, w, h, tilt(), moving = now - movedAt < 600)
        latest = a; latestTable = t
        // advice is passed on once it held for a moment, so the voice doesn't chase every jiggle
        if (a.kind != pending) { pending = a.kind; pendingSince = now }
        if (a.kind != shown && now - pendingSince >= HOLD_MS) { shown = a.kind; onAdvice(a) }
    }

    /** the picture at 320 x 180: Y, and U, V at the same size (nearest sample) */
    private fun sample(img: android.media.Image) {
        val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
        val by = py.buffer; val bu = pu.buffer; val bv = pv.buffer
        val sx = img.width.toFloat() / w; val sy = img.height.toFloat() / h
        for (j in 0 until h) {
            val yy = (j * sy).toInt(); val cy = yy / 2
            for (i in 0 until w) {
                val xx = (i * sx).toInt(); val cx = xx / 2; val p = j * w + i
                y[p] = by.get(yy * py.rowStride + xx * py.pixelStride)
                u[p] = bu.get(cy * pu.rowStride + cx * pu.pixelStride)
                v[p] = bv.get(cy * pv.rowStride + cx * pv.pixelStride)
            }
        }
    }

    private fun jpeg(img: android.media.Image): ByteArray {
        val iw = img.width; val ih = img.height
        val nv21 = ByteArray(iw * ih * 3 / 2)
        val py = img.planes[0]; val pu = img.planes[1]; val pv = img.planes[2]
        for (j in 0 until ih) for (i in 0 until iw) nv21[j * iw + i] = py.buffer.get(j * py.rowStride + i * py.pixelStride)
        var o = iw * ih
        for (j in 0 until ih / 2) for (i in 0 until iw / 2) {
            nv21[o++] = pv.buffer.get(j * pv.rowStride + i * pv.pixelStride)
            nv21[o++] = pu.buffer.get(j * pu.rowStride + i * pu.pixelStride)
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, iw, ih, null).compressToJpeg(Rect(0, 0, iw, ih), 70, out)
        return out.toByteArray()
    }

    private fun smallest16x9(id: String): Size {
        val map = (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        return sizes.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f && it.width >= 480 && it.width <= 960 }
            .minByOrNull { it.width } ?: Size(640, 360)
    }

    companion object { const val HOLD_MS = 900L }
}
