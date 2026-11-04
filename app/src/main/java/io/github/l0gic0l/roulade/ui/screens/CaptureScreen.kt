package io.github.l0gic0l.roulade.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.l0gic0l.roulade.capture.CaptureController
import io.github.l0gic0l.roulade.capture.RecordingState
import io.github.l0gic0l.roulade.capture.SensorRecorder
import io.github.l0gic0l.roulade.stab.SensorLog
import io.github.l0gic0l.roulade.ui.AppViewModel
import io.github.l0gic0l.roulade.ui.Screen
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    // Keep the orientation fixed while this screen is open so a rotation can't interrupt a recording.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val controller = remember { CaptureController(context) }
    DisposableEffect(Unit) { onDispose { controller.release() } }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val config = vm.captureConfig
    var recording by remember { mutableStateOf(false) }
    var elapsedNs by remember { mutableLongStateOf(0L) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var supportedFps by remember { mutableStateOf(listOf(30)) }
    var bindKey by remember { mutableIntStateOf(0) }
    val motion = rememberLiveMotion()

    // (Re)bind when the options that need a new camera session change.
    LaunchedEffect(granted, config.fps, config.quality, config.disablePhoneStabilization, bindKey) {
        if (!granted) return@LaunchedEffect
        controller.bind(
            lifecycleOwner, previewView, config,
            onBound = { used ->
                supportedFps = controller.supportedFps
                if (used != config.fps) vm.updateCapture(config.copy(fps = used))
                controller.setZoom(zoom)
            },
            onError = { vm.error = it },
        )
    }
    LaunchedEffect(config.lockExposure) { controller.setExposureLock(config.lockExposure) }

    BackHandler {
        if (recording) controller.stopRecording() else vm.screen = Screen.Home
    }

    // Full-bleed camera screen: system bar insets are applied per element below.
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                // Tap to focus (focus then stays locked).
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures { p -> controller.focusAt(previewView, p.x, p.y) }
                    },
                )
                LevelOverlay(motion.rollErrorDeg, Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Roulade needs the camera to record.", color = Color.White)
                    Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }

            // Top bar: back, level and shake readouts.
            Row(
                Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = { if (!recording) vm.screen = Screen.Home }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                val level = abs(motion.rollErrorDeg) < 0.5f
                AssistChip(
                    onClick = {},
                    label = { Text("Roll ${"%.1f".format(motion.rollErrorDeg)}°  Tilt ${"%.1f".format(motion.tiltDeg)}°") },
                    leadingIcon = { Icon(Icons.Outlined.Straighten, null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = (if (level) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).copy(alpha = 0.9f),
                    ),
                )
                val shake = motion.shakeDegPerS
                AssistChip(
                    onClick = {},
                    label = { Text(when { shake < 0.3f -> "Steady"; shake < 2f -> "Slight shake"; else -> "Shaky" }) },
                    leadingIcon = { Icon(Icons.Outlined.Vibration, null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = when {
                            shake < 0.3f -> MaterialTheme.colorScheme.tertiaryContainer
                            shake < 2f -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        }.copy(alpha = 0.9f),
                    ),
                )
            }

            // Bottom panel: options, zoom, record.
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                Column(
                    Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!recording) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            supportedFps.forEach { f ->
                                FilterChip(selected = config.fps == f, onClick = { vm.updateCapture(config.copy(fps = f)) }, label = { Text("$f fps") })
                            }
                            listOf("HD" to "720p", "FHD" to "1080p", "UHD" to "4K").forEach { (q, label) ->
                                FilterChip(selected = config.quality == q, onClick = { vm.updateCapture(config.copy(quality = q)) }, label = { Text(label) })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = config.lockExposure,
                                onClick = { vm.updateCapture(config.copy(lockExposure = !config.lockExposure)) },
                                label = { Text("Lock exposure") },
                            )
                            FilterChip(
                                selected = config.disablePhoneStabilization,
                                onClick = { vm.updateCapture(config.copy(disablePhoneStabilization = !config.disablePhoneStabilization)) },
                                label = { Text("Phone OIS/EIS off") },
                            )
                            FilterChip(
                                selected = config.fastestSensors,
                                onClick = { vm.updateCapture(config.copy(fastestSensors = !config.fastestSensors)) },
                                label = { Text("Max sensor rate") },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Zoom", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(12.dp))
                            Slider(value = zoom, onValueChange = { zoom = it; controller.setZoom(it) }, modifier = Modifier.weight(1f))
                        }
                        Text(
                            "Tap the picture to focus. Phone stabilisation off lets Roulade's sensor stabilisation work properly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (recording) {
                            val s = (elapsedNs / 1_000_000_000L).toInt()
                            Text(
                                "● ${s / 60}:${"%02d".format(s % 60)}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.width(24.dp))
                        }
                        LargeFloatingActionButton(
                            onClick = {
                                if (recording) {
                                    controller.stopRecording()
                                } else if (granted) {
                                    if (!SensorRecorder.hasGyroscope(context)) vm.message = "This phone has no gyroscope; stabilisation won't be available."
                                    recording = true
                                    elapsedNs = 0
                                    controller.startRecording(vm.recordingsDir, config) { state ->
                                        when (state) {
                                            is RecordingState.Running -> elapsedNs = state.elapsedNanos
                                            is RecordingState.Saved -> {
                                                recording = false
                                                vm.refreshRecordings()
                                                vm.open(Uri.fromFile(state.video), SensorLog.sidecarFor(state.video), "New recording")
                                            }
                                            is RecordingState.Failed -> {
                                                recording = false
                                                vm.error = state.message
                                                bindKey++
                                            }
                                        }
                                    }
                                }
                            },
                            containerColor = if (recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                                if (recording) "Stop recording" else "Start recording",
                                Modifier.size(36.dp),
                                tint = if (recording) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
        }
    }
}

/** Horizon line that turns green when the phone is level about the lens axis. */
@Composable
private fun LevelOverlay(rollErrorDeg: Float, modifier: Modifier) {
    val ok = abs(rollErrorDeg) < 0.5f
    val color = if (ok) MaterialTheme.colorScheme.tertiary else Color.White.copy(alpha = 0.8f)
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val half = size.minDimension * 0.3f
        drawLine(Color.White.copy(alpha = 0.35f), Offset(c.x - half, c.y), Offset(c.x + half, c.y), strokeWidth = 2f)
        rotate(-rollErrorDeg.coerceIn(-20f, 20f), c) {
            drawLine(color, Offset(c.x - half, c.y), Offset(c.x + half, c.y), strokeWidth = 5f)
        }
        drawCircle(color, radius = 6f, center = c)
    }
}

private class LiveMotion {
    var rollErrorDeg by mutableFloatStateOf(0f)
    var tiltDeg by mutableFloatStateOf(0f)
    var shakeDegPerS by mutableFloatStateOf(0f)
}

/** Live gravity-based level and gyro-based shake estimate while the screen is visible. */
@Composable
private fun rememberLiveMotion(): LiveMotion {
    val context = LocalContext.current
    val state = remember { LiveMotion() }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        var shakeSq = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                when (e.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        // Roll about the lens axis, relative to the nearest upright/landscape position.
                        val roll = Math.toDegrees(atan2(v[0].toDouble(), v[1].toDouble())).toFloat()
                        val nearest = (roll / 90f).roundToInt() * 90f
                        state.rollErrorDeg = roll - nearest
                        state.tiltDeg = Math.toDegrees(atan2(-v[2].toDouble(), sqrt((v[0] * v[0] + v[1] * v[1]).toDouble()))).toFloat()
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val m = Math.toDegrees(sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())).toFloat()
                        shakeSq += 0.05f * (m * m - shakeSq)
                        state.shakeDegPerS = sqrt(shakeSq)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { manager.unregisterListener(listener) }
    }
    return state
}
