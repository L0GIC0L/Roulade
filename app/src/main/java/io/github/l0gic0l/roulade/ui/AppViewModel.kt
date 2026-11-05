package io.github.l0gic0l.roulade.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.l0gic0l.roulade.analysis.AnalysisSettings
import io.github.l0gic0l.roulade.analysis.BandPreset
import io.github.l0gic0l.roulade.capture.CaptureConfig
import io.github.l0gic0l.roulade.dsp.Spectrum
import io.github.l0gic0l.roulade.stab.SensorLog
import io.github.l0gic0l.roulade.video.AmplifyReport
import io.github.l0gic0l.roulade.video.Clip
import io.github.l0gic0l.roulade.video.FrequencyScanner
import io.github.l0gic0l.roulade.video.VideoAmplifier
import io.github.l0gic0l.roulade.video.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { Home, Capture, Analyze, Processing, Result }

/** A video recorded in Roulade, listed on the home screen. */
class Recording(val video: File, val sensors: File?) {
    val date: Date get() = Date(video.lastModified())
    val sizeMb: Float get() = (video.length() + (sensors?.length() ?: 0)) / 1e6f
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val store = SettingsStore(app)

    var screen by mutableStateOf(Screen.Home)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    // Settings
    var settings by mutableStateOf(store.loadAnalysis())
        private set
    var captureConfig by mutableStateOf(store.loadCapture())
        private set

    fun updateSettings(s: AnalysisSettings) {
        settings = s
        store.saveAnalysis(s)
    }

    fun updateCapture(c: CaptureConfig) {
        captureConfig = c
        store.saveCapture(c)
    }

    fun resetSettings() {
        val clipFps = settings.captureFps
        updateSettings(AnalysisSettings(captureFps = clipFps))
        clip?.let { fitBandToClip(it) }
    }

    // Library of recordings
    val recordingsDir: File get() = File(context.filesDir, "recordings")
    var recordings by mutableStateOf<List<Recording>>(emptyList())
        private set

    fun refreshRecordings() {
        recordings = (recordingsDir.listFiles { f -> f.extension == "mp4" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { Recording(it, SensorLog.sidecarFor(it).takeIf { s -> s.exists() }) }
    }

    fun deleteRecording(r: Recording) {
        r.video.delete()
        r.sensors?.delete()
        refreshRecordings()
    }

    // Current clip
    var clip by mutableStateOf<Clip?>(null)
        private set
    var thumbnail by mutableStateOf<Bitmap?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun open(uri: Uri, sensorLog: File? = null, name: String = "Video") {
        loading = true
        viewModelScope.launch {
            try {
                val (info, thumb) = withContext(Dispatchers.IO) { VideoInfo.probe(context, uri) }
                val c = Clip(uri, info, sensorLog, name)
                clip = c
                thumbnail = thumb
                spectrum = null
                fitBandToClip(c)
                screen = Screen.Analyze
            } catch (e: Exception) {
                error = "Couldn't open that video: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    fun openRecording(r: Recording) =
        open(Uri.fromFile(r.video), r.sensors, SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(r.date))

    /** Per-clip defaults: detected frame rate, full length, a band below the Nyquist limit. */
    private fun fitBandToClip(c: Clip) {
        val fps = (c.info.captureFrameRate ?: c.info.frameRate).toDouble()
        var s = settings.copy(captureFps = fps, trimStartS = 0f, trimEndS = null)
        val nyquist = fps / 2
        if (s.highHz >= nyquist) s = s.copy(highHz = round2(nyquist * 0.8))
        if (s.lowHz >= s.highHz) s = s.copy(lowHz = round2(s.highHz / 4))
        updateSettings(s)
    }

    val clipFps: Double get() = settings.captureFps ?: clip?.info?.frameRate?.toDouble() ?: 30.0

    fun applyPreset(p: BandPreset) {
        val nyquist = clipFps / 2
        val high = minOf(p.highHz, round2(nyquist * 0.9))
        val low = minOf(p.lowHz, round2(high / 2))
        updateSettings(settings.copy(lowHz = low, highHz = high))
        if (p.highHz >= nyquist) message = "Clipped to ${AnalysisSettings.fmt(high)} Hz; this clip's frame rate can't show faster motion."
    }

    /** Band around a frequency, e.g. from a spectrum peak or a machine's running speed. */
    fun centreBandOn(hz: Double, widthFraction: Double = 0.15) {
        val nyquist = clipFps / 2
        val high = minOf(hz * (1 + widthFraction), nyquist * 0.95)
        val low = maxOf(0.05, hz * (1 - widthFraction))
        if (low >= high) {
            error = "${AnalysisSettings.fmt(hz)} Hz is too close to this clip's limit of ${AnalysisSettings.fmt(nyquist)} Hz."
            return
        }
        updateSettings(settings.copy(lowHz = round2(low), highHz = round2(high)))
    }

    // Frequency finder
    var spectrum by mutableStateOf<Spectrum?>(null)
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanProgress by mutableFloatStateOf(0f)
        private set

    fun scanFrequencies() {
        val c = clip ?: return
        scanning = true
        scanProgress = 0f
        viewModelScope.launch {
            try {
                spectrum = FrequencyScanner(context).scan(
                    c, clipFps, settings.trimStartS, settings.trimEndS, settings.rejectCameraShake,
                ) { scanProgress = it }
                if (spectrum?.peaks.isNullOrEmpty()) message = "No clear vibration peaks found."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Scan failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                scanning = false
            }
        }
    }

    // Processing
    var stage by mutableStateOf("")
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var etaSeconds by mutableStateOf<Int?>(null)
        private set
    var report by mutableStateOf<AmplifyReport?>(null)
        private set
    var resultVersion by mutableStateOf(0)
        private set
    private var job: Job? = null
    private var savedUri: Uri? = null
    val resultFile: File get() = File(context.cacheDir, "amplified.mp4")

    fun problem(): String? {
        val c = clip ?: return "No video selected."
        return settings.problem(clipFps, c.info.durationMs / 1000f)
    }

    fun start() {
        val c = clip ?: return
        problem()?.let { error = it; return }
        progress = 0f
        etaSeconds = null
        stage = "Starting"
        savedUri = null
        screen = Screen.Processing
        val started = SystemClock.elapsedRealtime()
        job = viewModelScope.launch {
            try {
                report = VideoAmplifier(context).amplify(c, settings, resultFile) { st, p ->
                    stage = st
                    progress = p
                    val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
                    etaSeconds = if (p > 0.03f) ((elapsed / p) - elapsed).toInt().coerceAtLeast(0) else null
                }
                resultVersion++
                screen = Screen.Result
            } catch (e: CancellationException) {
                screen = Screen.Analyze
                throw e
            } catch (e: Throwable) {
                error = when (e) {
                    is OutOfMemoryError -> "Ran out of memory. Pick a lower output resolution or filter order."
                    else -> "Processing failed: ${e.message ?: e.javaClass.simpleName}"
                }
                screen = Screen.Analyze
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun saveResult() {
        viewModelScope.launch {
            try {
                savedUri = withContext(Dispatchers.IO) { savedUri ?: copyToGallery(resultFile, "Roulade_amplified") }
                message = "Saved to Movies/Roulade"
            } catch (e: Exception) {
                error = "Couldn't save: ${e.message}"
            }
        }
    }

    fun shareResultIntent(): Intent = shareIntent(listOf(resultFile), "video/mp4", "Share amplified video")

    /** Shares a recording with its sensor CSV, for analysis elsewhere. */
    fun shareRecordingIntent(r: Recording): Intent =
        shareIntent(listOfNotNull(r.video, r.sensors), "*/*", "Share recording and sensor log")

    private fun shareIntent(files: List<File>, type: String, title: String): Intent {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }

    private fun copyToGallery(file: File, prefix: String): Uri {
        val resolver = context.contentResolver
        val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Roulade")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
