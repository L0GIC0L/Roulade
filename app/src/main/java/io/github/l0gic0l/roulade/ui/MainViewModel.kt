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
import io.github.l0gic0l.roulade.video.AmplifySettings
import io.github.l0gic0l.roulade.video.VideoAmplifier
import io.github.l0gic0l.roulade.video.VideoInfo
import io.github.l0gic0l.roulade.video.outputSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Stage { Empty, Loading, Ready, Processing, Done }

class LoadedVideo(val uri: Uri, val info: VideoInfo, val thumbnail: Bitmap?)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    var stage by mutableStateOf(Stage.Empty)
        private set
    var video by mutableStateOf<LoadedVideo?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    // Settings, kept as text where the user types numbers.
    var alpha by mutableFloatStateOf(20f)
    var lowHzText by mutableStateOf("1")
    var highHzText by mutableStateOf("4")
    var fpsText by mutableStateOf("30")
    var shortSide by mutableStateOf(720)

    // Progress
    var progress by mutableFloatStateOf(0f)
        private set
    var etaSeconds by mutableStateOf<Int?>(null)
        private set
    private var job: Job? = null

    // Result
    val resultFile: File get() = File(context.cacheDir, "amplified.mp4")
    var resultVersion by mutableStateOf(0)
        private set
    private var savedUri: Uri? = null

    /** File the camera app records into when the user taps "Record". */
    fun newCaptureUri(): Uri {
        val file = File(context.cacheDir, "captured.mp4")
        file.delete()
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun load(uri: Uri) {
        stage = Stage.Loading
        error = null
        viewModelScope.launch {
            try {
                val (info, thumb) = withContext(Dispatchers.IO) { VideoInfo.probe(context, uri) }
                video = LoadedVideo(uri, info, thumb)
                val fps = info.captureFrameRate ?: info.frameRate
                fpsText = formatNumber(fps.toDouble())
                // Keep the default band valid for low frame rates.
                val nyquist = fps / 2
                if ((highHzText.toDoubleOrNull() ?: 0.0) >= nyquist) {
                    highHzText = formatNumber(nyquist * 0.8)
                    if ((lowHzText.toDoubleOrNull() ?: 0.0) >= nyquist * 0.8) lowHzText = formatNumber(nyquist * 0.2)
                }
                stage = Stage.Ready
            } catch (e: Exception) {
                error = "Couldn't open that video: ${e.message ?: e.javaClass.simpleName}"
                stage = if (video != null) Stage.Ready else Stage.Empty
            }
        }
    }

    /** Validated settings, or an explanation of what's wrong. */
    fun settingsOrError(): Pair<AmplifySettings?, String?> {
        val fps = fpsText.toDoubleOrNull()
        val low = lowHzText.toDoubleOrNull()
        val high = highHzText.toDoubleOrNull()
        return when {
            fps == null || fps <= 0 -> null to "Enter the capture frame rate."
            low == null || low <= 0 -> null to "Low cutoff must be above 0 Hz."
            high == null || high <= low -> null to "High cutoff must be above the low cutoff."
            high >= fps / 2 -> null to "High cutoff must be below ${formatNumber(fps / 2)} Hz (half the frame rate)."
            else -> AmplifySettings(alpha, low, high, fps, shortSide) to null
        }
    }

    fun outputDimensions(): Pair<Int, Int>? {
        val info = video?.info ?: return null
        val (w, h) = outputSize(info.width, info.height, shortSide)
        return if (info.rotation % 180 == 0) w to h else h to w
    }

    fun start() {
        val v = video ?: return
        val settings = settingsOrError().first ?: return
        error = null
        progress = 0f
        etaSeconds = null
        stage = Stage.Processing
        savedUri = null
        val started = SystemClock.elapsedRealtime()
        job = viewModelScope.launch {
            try {
                VideoAmplifier(context).amplify(v.uri, v.info, settings, resultFile) { p ->
                    progress = p
                    val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
                    etaSeconds = if (p > 0.03f) ((elapsed / p) - elapsed).toInt().coerceAtLeast(0) else null
                }
                resultVersion++
                stage = Stage.Done
            } catch (e: CancellationException) {
                stage = Stage.Ready
                throw e
            } catch (e: Throwable) {
                error = when (e) {
                    is OutOfMemoryError -> "Ran out of memory. Pick a lower output resolution."
                    else -> "Processing failed: ${e.message ?: e.javaClass.simpleName}"
                }
                stage = Stage.Ready
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun backToSettings() {
        stage = if (video != null) Stage.Ready else Stage.Empty
    }

    fun reset() {
        video = null
        stage = Stage.Empty
        error = null
    }

    /** Copies the result into the shared Movies/Roulade folder. */
    fun saveToGallery() {
        viewModelScope.launch {
            try {
                savedUri = withContext(Dispatchers.IO) { copyToMediaStore() }
                message = "Saved to Movies/Roulade"
            } catch (e: Exception) {
                error = "Couldn't save: ${e.message}"
            }
        }
    }

    fun shareIntent(): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", resultFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share amplified video")
    }

    private fun copyToMediaStore(): Uri {
        savedUri?.let { return it }
        val resolver = context.contentResolver
        val name = "Roulade_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Roulade")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore refused the file")
        try {
            resolver.openOutputStream(uri)!!.use { out -> resultFile.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    companion object {
        fun formatNumber(v: Double): String =
            if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}
