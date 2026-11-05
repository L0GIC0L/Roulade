package io.github.l0gic0l.roulade.ui.screens

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.l0gic0l.roulade.analysis.AnalysisSettings
import io.github.l0gic0l.roulade.ui.AppViewModel
import io.github.l0gic0l.roulade.ui.Screen
import io.github.l0gic0l.roulade.ui.components.ChoiceRow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(vm: AppViewModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { /* Use Cancel. */ }
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Processing") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { vm.progress },
                    modifier = Modifier.size(180.dp),
                    strokeWidth = 12.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Text("${(vm.progress * 100).roundToInt()}%", style = MaterialTheme.typography.displaySmall)
            }
            Spacer(Modifier.height(28.dp))
            Text(vm.stage, style = MaterialTheme.typography.titleLarge)
            vm.etaSeconds?.let { eta ->
                Text(
                    "About ${eta / 60}:${"%02d".format(eta % 60)} left",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Keep Roulade open; the screen stays on until it finishes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = vm::cancel) {
                Icon(Icons.Outlined.Close, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Cancel")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val clip = vm.clip ?: return
    val report = vm.report
    val context = LocalContext.current
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    BackHandler { vm.screen = Screen.Analyze }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Result") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Analyze }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChoiceRow(
                label = null,
                options = listOf(false, true),
                selected = showOriginal,
                labelOf = { if (it) "Original" else "Result" },
                onSelect = { showOriginal = it },
            )
            val aspect = if (showOriginal || report == null) {
                clip.info.displayAspect
            } else if (clip.info.rotation % 180 == 0) {
                report.width.toFloat() / report.height
            } else {
                report.height.toFloat() / report.width
            }
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val uri = if (showOriginal) clip.uri else Uri.fromFile(vm.resultFile)
                    LoopingVideo(uri, "${vm.resultVersion}", Modifier.aspectRatio(aspect))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::saveResult, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Save")
                }
                FilledTonalButton(
                    onClick = { context.startActivity(vm.shareResultIntent()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Share")
                }
            }
            report?.let { r ->
                ElevatedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Run summary", style = MaterialTheme.typography.titleMedium)
                        }
                        val s = vm.settings
                        Text("Output ${r.width}×${r.height}, ${r.frames} frames, ${speedLabel(s.playbackSpeed)} speed, ${s.viewMode.label.lowercase()}")
                        Text(
                            "Band ${AnalysisSettings.fmt(s.lowHz)}–${AnalysisSettings.fmt(s.highHz)} Hz " +
                                "(order ${s.filterOrder}) at ${AnalysisSettings.fmt(vm.clipFps)} fps, amplification ${s.alpha.roundToInt()}×",
                        )
                        if (r.stabilized) {
                            val sync = r.sync
                            if (sync != null) {
                                Text(
                                    if (sync.automatic) {
                                        "Sensors auto-synced: offset ${sync.offsetMs.roundToInt()} ms, match ${"%.2f".format(sync.score)}" +
                                            if (sync.extraRotationDeg != 0) ", axes turned ${sync.extraRotationDeg}°" else ""
                                    } else {
                                        "Sensors aligned with the manual offset (${sync.offsetMs.roundToInt()} ms)"
                                    },
                                )
                            }
                            Text("Average stabilisation correction ${"%.1f".format(r.correctionRmsPx)} px")
                        } else {
                            Text("Not stabilised")
                        }
                        r.notes.forEach {
                            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.screen = Screen.Analyze }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Adjust")
                }
                OutlinedButton(onClick = { vm.screen = Screen.Home }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Home, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Home")
                }
            }
        }
    }
}

internal fun speedLabel(speed: Float) = when {
    speed >= 0.99f -> "real-time"
    else -> "1/${(1 / speed).roundToInt()}"
}

/** Silent, looping playback using the platform VideoView. */
@Composable
private fun LoopingVideo(uri: Uri, version: String, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    start()
                }
            }
        },
        update = { view ->
            val tag = "$uri#$version"
            if (view.tag != tag) {
                view.tag = tag
                view.setVideoURI(uri)
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = modifier,
    )
}
