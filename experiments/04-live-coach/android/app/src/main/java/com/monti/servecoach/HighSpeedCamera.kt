package com.monti.servecoach

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.util.Range
import android.util.Size
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * The phone's slow-motion camera, opened the way the camera app records slow motion: a
 * "constrained high-speed" session. It can only feed a preview and a video encoder, not the
 * app's code directly, so frames go to the encoder and serves are cut from what it wrote.
 */
class HighSpeedCamera(private val context: Context) {
    data class Config(
        val cameraId: String, val size: Size, val fps: Int, val mime: String, val encoder: String,
        val timestampRealtime: Boolean, val sensorOrientation: Int,
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null

    /** Everything the phone offers for slow motion, for the report. */
    fun probe(): JSONObject {
        val out = JSONObject()
        val cams = JSONArray()
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> "back"; CameraMetadata.LENS_FACING_FRONT -> "front"; else -> "other"
            }
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
            val hs = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
            val cam = JSONObject().put("id", id).put("facing", facing).put("highSpeed", hs)
                .put("timestamp", if (ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "realtime" else "unknown")
                .put("sensorOrientation", ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
            if (hs) {
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val modes = JSONArray()
                for (s in map.highSpeedVideoSizes) for (r in map.getHighSpeedVideoFpsRangesFor(s))
                    modes.put("${s.width}x${s.height} ${r.lower}-${r.upper}")
                cam.put("modes", modes)
            }
            cams.put(cam)
        }
        out.put("cameras", cams)
        val enc = JSONArray()
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC))
            for (sz in listOf(Size(1920, 1080), Size(1280, 720)))
                for (fps in listOf(240, 120)) encoderFor(mime, sz, fps)?.let { enc.put("$mime ${sz.width}x${sz.height}@$fps: $it") }
        out.put("encoders", enc)
        return out
    }

    private fun encoderFor(mime: String, size: Size, fps: Int): String? {
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(mime, true) }) continue
            val vc: MediaCodecInfo.VideoCapabilities = info.getCapabilitiesForType(mime).videoCapabilities ?: continue
            if (vc.areSizeAndRateSupported(size.width, size.height, fps.toDouble())) return info.name
        }
        return null
    }

    /**
     * The best mode: a back camera, 240 fps (else 120), 1080p if the encoder keeps up (the ball
     * is bigger), else 720p; H.264 if it can, else HEVC.
     */
    fun choose(): Config? {
        var best: Config? = null
        var bestScore = -1
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: continue
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            for (s in map.highSpeedVideoSizes) {
                for (r in map.getHighSpeedVideoFpsRangesFor(s)) {
                    if (r.lower != r.upper || r.upper < 120) continue        // fixed rate: every frame to both outputs
                    for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                        val enc = encoderFor(mime, s, r.upper) ?: continue
                        val score = (if (r.upper >= 240) 1000 else 0) + (if (s.height >= 1080) 100 else if (s.height >= 720) 50 else 0) +
                            (if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) 1 else 0)
                        if (score > bestScore) {
                            bestScore = score
                            best = Config(id, s, r.upper, mime, enc,
                                ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                                ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                        }
                    }
                }
            }
        }
        return best
    }

    @SuppressLint("MissingPermission")
    fun start(cfg: Config, surfaces: List<Surface>, handler: Handler, onError: (String) -> Unit, onRunning: () -> Unit) {
        val executor = Executor { handler.post(it) }
        val gen = ++generation
        manager.openCamera(cfg.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (gen != generation) { d.close(); return }       // stopped while it was opening
                device = d
                val outputs = surfaces.map { OutputConfiguration(it) }
                val sc = SessionConfiguration(SessionConfiguration.SESSION_HIGH_SPEED, outputs, executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (gen != generation) { s.close(); return }
                            val hs = s as CameraConstrainedHighSpeedCaptureSession
                            session = hs
                            try {
                                fun burst(lock: Boolean, freezeFocus: Boolean): List<CaptureRequest> {
                                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    surfaces.forEach { b.addTarget(it) }
                                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfg.fps, cfg.fps))
                                    b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    if (lock) {
                                        b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                                        b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                                    }
                                    if (freezeFocus) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                                    return hs.createHighSpeedRequestList(b.build())
                                }
                                fun send(list: List<CaptureRequest>) {
                                    // Android's list sends the preview only the 1st frame of each 8 (30 a
                                    // second); its 1st request goes to both, so every frame does with it 8 times
                                    // over (the app watches the preview stream). If refused: Android's own.
                                    everyFrameToPreview = false
                                    if (surfaces.size == 2 && list.size > 1 && allToPreview) try {
                                        hs.setRepeatingBurst(List(list.size) { list[0] }, null, handler)
                                        everyFrameToPreview = true
                                    } catch (e: Exception) { burstNote = "every frame to the preview refused: ${e.message}" }
                                    if (!everyFrameToPreview) hs.setRepeatingBurst(list, null, handler)
                                }
                                send(burst(lock = false, freezeFocus = false))
                                onRunning()
                                // Then hold the camera still.
                                //
                                // Exposure, white balance and focus were left hunting for the whole
                                // session. Every one of them moves the WHOLE picture away from the
                                // background at once, which is a mask with everything in it moving --
                                // and that is what a phone session reported: 129,600 pixels of 129,600,
                                // fifty times. The row gain cancels an exposure change; nothing cancels
                                // a white balance change, and nothing cancels focus breathing, which
                                // moves every edge in the room at the same moment.
                                //
                                // A second and a half of hunting first, so it settles on this room
                                // rather than on whatever the preview was pointed at, and then locked.
                                // Only three keys, all of which a constrained high-speed session is
                                // documented to take -- and if a device still refuses, it is refused
                                // one at a time rather than losing the session.
                                handler.postDelayed({
                                    if (gen != generation) return@postDelayed
                                    lockNote = try { send(burst(lock = true, freezeFocus = true)); "exposure, white balance and focus locked" }
                                    catch (e: Exception) {
                                        try { send(burst(lock = true, freezeFocus = false)); "exposure and white balance locked; focus left to the camera (${e.message})" }
                                        catch (e2: Exception) {
                                            try { send(burst(lock = false, freezeFocus = false)) } catch (_: Exception) {}
                                            "nothing could be locked (${e2.message})"
                                        }
                                    }
                                }, 1500)
                            } catch (e: Exception) { onError("request: ${e.message}") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { onError("the camera refused the slow-motion session") }
                    })
                try { d.createCaptureSession(sc) } catch (e: Exception) { onError("session: ${e.message}") }
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
            override fun onError(d: CameraDevice, error: Int) { d.close(); device = null; if (gen == generation) onError("camera error $error") }
        }, handler)
    }

    /**
     * Only the screen, at 30 frames a second, for setting the phone up before Start: an ordinary
     * session (0.7.7 ran the slow-motion one with only the screen fed, 240 frames a second to it,
     * and the app froze when Start closed it). Same size, so the same part of the sensor.
     */
    @SuppressLint("MissingPermission")
    fun startPreview(cfg: Config, surface: Surface, handler: Handler, onError: (String) -> Unit, onRunning: () -> Unit, analysis: Surface? = null) {
        val executor = Executor { handler.post(it) }
        val gen = ++generation
        manager.openCamera(cfg.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (gen != generation) { d.close(); return }
                device = d
                // the screen, and a small copy for checking the setup (SetupGuide)
                val outs = listOfNotNull(surface, analysis)
                val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs.map { OutputConfiguration(it) }, executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (gen != generation) { s.close(); return }
                            session = s
                            try {
                                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                outs.forEach { b.addTarget(it) }
                                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                                s.setRepeatingRequest(b.build(), null, handler)
                                onRunning()
                            } catch (e: Exception) { onError("preview request: ${e.message}") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { onError("the camera refused the preview") }
                    })
                try { d.createCaptureSession(sc) } catch (e: Exception) { onError("preview session: ${e.message}") }
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); if (device === d) device = null }
            override fun onError(d: CameraDevice, error: Int) { d.close(); if (device === d) device = null; if (gen == generation) onError("camera error $error") }
        }, handler)
    }

    /** send every frame to the preview stream too (else 1 in 8), and whether that was taken */
    var allToPreview = false
    @Volatile var everyFrameToPreview = false; private set
    @Volatile var burstNote = ""; private set
    /** what the camera let us hold still once the scene had settled */
    @Volatile var lockNote = "not yet"

    /** counts starts and stops, so a camera that opens after it was stopped is closed at once */
    @Volatile private var generation = 0

    fun stop() {
        generation++
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        device?.close(); device = null
    }

    /** [stop] on the camera's thread (closing can take a while), then [then] there */
    fun stopOn(handler: Handler, then: () -> Unit) {
        generation++
        val s = session; val d = device
        session = null; device = null
        handler.post {
            try { s?.stopRepeating() } catch (_: Exception) {}
            try { s?.close() } catch (_: Exception) {}
            try { d?.close() } catch (_: Exception) {}
            then()
        }
    }
}
