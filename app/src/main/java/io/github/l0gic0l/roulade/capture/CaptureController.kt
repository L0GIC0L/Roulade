package io.github.l0gic0l.roulade.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import android.util.Range
import android.util.SizeF
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.github.l0gic0l.roulade.stab.SensorLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Recording options chosen on the capture screen. */
data class CaptureConfig(
    val fps: Int = 30,
    /** "HD", "FHD" or "UHD". */
    val quality: String = "FHD",
    /** Hold exposure and white balance so brightness doesn't pulse. */
    val lockExposure: Boolean = true,
    /** Turn off the phone's OIS/EIS, which would fight sensor-based stabilisation. */
    val disablePhoneStabilization: Boolean = true,
    /** Log motion sensors as fast as the phone allows (otherwise ~200 Hz "game" rate). */
    val fastestSensors: Boolean = true,
)

/** Events from a recording, delivered on the main thread. */
sealed interface RecordingState {
    data class Running(val elapsedNanos: Long) : RecordingState
    data class Saved(val video: File) : RecordingState
    data class Failed(val message: String) : RecordingState
}

/** Binds CameraX preview + video recording and records motion sensors alongside. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CaptureController(private val context: Context) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val sensors = SensorRecorder(context)
    private var camera: Camera? = null
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var boundRotation = Surface.ROTATION_0

    /** Frame rates (30/60) the bound camera supports. */
    var supportedFps: List<Int> = listOf(30)
        private set

    val isRecording get() = recording != null

    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        config: CaptureConfig,
        onBound: (fpsUsed: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                onError("Camera unavailable: ${e.message}")
                return@addListener
            }
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            fun tryBind(fps: Int): Boolean = try {
                bindUseCases(provider, owner, previewView, config, fps, rotation)
                true
            } catch (e: Exception) {
                false
            }
            when {
                tryBind(config.fps) -> onBound(config.fps)
                config.fps != 30 && tryBind(30) -> {
                    onError("${config.fps} fps isn't available here; using 30 fps.")
                    onBound(30)
                }
                else -> onError("Couldn't start the camera.")
            }
        }, mainExecutor)
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        previewView: PreviewView,
        config: CaptureConfig,
        fps: Int,
        rotation: Int,
    ) {
        val quality = when (config.quality) {
            "HD" -> Quality.HD
            "UHD" -> Quality.UHD
            else -> Quality.FHD
        }
        val previewBuilder = Preview.Builder().setTargetRotation(rotation)
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .build()
        val videoBuilder = VideoCapture.Builder(recorder).setTargetRotation(rotation)
        if (fps != 30) videoBuilder.setTargetFrameRate(Range(fps, fps))
        if (config.disablePhoneStabilization) {
            videoBuilder.setVideoStabilizationEnabled(false)
            Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val video = videoBuilder.build()
        provider.unbindAll()
        this.provider = provider
        val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, video)
        camera = cam
        videoCapture = video
        boundRotation = rotation
        supportedFps = buildList {
            add(30)
            if (cam.cameraInfo.supportedFrameRateRanges.any { it.upper >= 60 }) add(60)
        }
        setExposureLock(config.lockExposure)
    }

    fun setExposureLock(locked: Boolean) {
        val cam = camera ?: return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
            .build()
        Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(options)
    }

    fun setZoom(linear: Float) {
        camera?.cameraControl?.setLinearZoom(linear.coerceIn(0f, 1f))
    }

    /** Focuses on a point of the preview and keeps that focus. */
    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
        )
    }

    @SuppressLint("MissingPermission") // Audio is never enabled, so only CAMERA is needed.
    fun startRecording(directory: File, config: CaptureConfig, onState: (RecordingState) -> Unit) {
        val video = videoCapture ?: return onState(RecordingState.Failed("Camera isn't ready yet."))
        val cam = camera ?: return
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "roulade_$stamp.mp4")
        val meta = LinkedHashMap<String, String>().apply { putAll(cameraMeta(cam, config)) }
        sensors.start(meta, config.fastestSensors)
        recording = video.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start ->
                        meta[SensorLog.VIDEO_START_NS] = SystemClock.elapsedRealtimeNanos().toString()
                    is VideoRecordEvent.Status ->
                        onState(RecordingState.Running(event.recordingStats.recordedDurationNanos))
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        val log = sensors.stop()
                        if (!file.exists() || file.length() == 0L) {
                            file.delete()
                            onState(RecordingState.Failed("Recording failed (error ${event.error})."))
                        } else {
                            // Writing the CSV can take a moment; keep it off the main thread.
                            thread(name = "roulade-sensor-writer") {
                                runCatching { log?.write(SensorLog.sidecarFor(file)) }
                                mainExecutor.execute { onState(RecordingState.Saved(file)) }
                            }
                        }
                    }
                    else -> Unit
                }
            }
    }

    fun stopRecording() {
        recording?.stop()
    }

    /** Stops any recording and releases the camera (it would otherwise stay bound to the activity). */
    fun release() {
        recording?.stop()
        recording = null
        sensors.stop()
        provider?.unbindAll()
        camera = null
        videoCapture = null
    }

    private fun cameraMeta(cam: Camera, config: CaptureConfig): Map<String, String> {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size: SizeF? = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val orientation = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION)
        val zoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        return buildMap {
            put(SensorLog.TARGET_ROTATION, (boundRotation * 90).toString())
            orientation?.let { put(SensorLog.SENSOR_ORIENTATION, it.toString()) }
            focal?.let { put(SensorLog.FOCAL_MM, it.toString()) }
            size?.let {
                put(SensorLog.SENSOR_WIDTH_MM, it.width.toString())
                put(SensorLog.SENSOR_HEIGHT_MM, it.height.toString())
            }
            put(SensorLog.ZOOM, zoom.toString())
            put(SensorLog.FPS, config.fps.toString())
            put(SensorLog.DEVICE, "${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }
}
