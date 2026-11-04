@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.l0gic0l.roulade.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@Composable
fun RouladeApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingCapture by rememberSaveable { mutableStateOf<Uri?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }
    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = pendingCapture
        if (ok && uri != null) vm.load(uri)
    }
    val onPick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val onRecord = {
        val uri = vm.newCaptureUri()
        pendingCapture = uri
        try {
            recordVideo.launch(uri)
        } catch (e: ActivityNotFoundException) {
            vm.error = "No camera app found."
        }
    }

    LaunchedEffect(vm.error) { vm.error?.let { snackbar.showSnackbar(it); vm.error = null } }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Roulade") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val content = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
            when (vm.stage) {
                Stage.Empty -> EmptyScreen(content, onRecord, onPick)
                Stage.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                Stage.Ready -> {
                    BackHandler { vm.reset() }
                    SettingsScreen(vm, content, onPick)
                }
                Stage.Processing -> ProcessingScreen(vm, content)
                Stage.Done -> {
                    BackHandler { vm.backToSettings() }
                    ResultScreen(vm, content) { context.startActivity(vm.shareIntent()) }
                }
            }
        }
    }
}

@Composable
private fun EmptyScreen(modifier: Modifier, onRecord: () -> Unit, onPick: () -> Unit) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Motion amplification", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Make tiny, invisible movements visible: vibrating machinery, a swaying structure, " +
                "breathing. Pick a frequency band and Roulade enlarges only the motion inside it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) { Text("Record video") }
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choose video") }
        TipsCard()
    }
}

@Composable
private fun TipsCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("For good results", style = MaterialTheme.typography.titleSmall)
            Text("• Keep the phone still (tripod or lean it on something). Camera shake gets amplified too.")
            Text("• Use bright, steady light. Dim scenes are noisy and noise gets amplified.")
            Text("• Keep clips short (5–20 s). Processing time grows with length and resolution.")
            Text("• To see motion at f Hz you need more than 2·f frames per second. Use slow motion for fast vibration.")
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel, modifier: Modifier, onPick: () -> Unit) {
    val video = vm.video ?: return
    val info = video.info
    val (settings, problem) = vm.settingsOrError()
    val fps = vm.fpsText.toDoubleOrNull()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        video.thumbnail?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "First frame of the selected video",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dw, dh) = if (info.rotation % 180 == 0) info.width to info.height else info.height to info.width
            Text(
                "${dw}×$dh · ${MainViewModel.formatNumber(info.frameRate.toDouble())} fps · " +
                    "${MainViewModel.formatNumber(info.durationMs / 100 / 10.0)} s",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onPick) { Text("Change") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Amplification: ${vm.alpha.roundToInt()}×", style = MaterialTheme.typography.titleSmall)
                Slider(value = vm.alpha, onValueChange = { vm.alpha = it.roundToInt().toFloat() }, valueRange = 1f..100f)
                Text(
                    "Roughly how much in-band motion is enlarged. Start around 10–30; " +
                        "high values also enlarge noise and can smear edges.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Frequency band", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("Low (Hz)", vm.lowHzText, { vm.lowHzText = it }, Modifier.weight(1f))
                    NumberField("High (Hz)", vm.highHzText, { vm.highHzText = it }, Modifier.weight(1f))
                }
                NumberField("Capture frame rate (fps)", vm.fpsText, { vm.fpsText = it }, Modifier.fillMaxWidth())
                Text(
                    buildString {
                        append("Only motion between the two frequencies is amplified")
                        if (fps != null && fps > 0) append("; the highest usable is ${MainViewModel.formatNumber(fps / 2)} Hz")
                        append(". For slow-motion clips, enter the real capture rate (e.g. 240).")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Output resolution", style = MaterialTheme.typography.titleSmall)
                val options = listOf(480, 720, 1080)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, side ->
                        SegmentedButton(
                            selected = vm.shortSide == side,
                            onClick = { vm.shortSide = side },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text("${side}p") }
                    }
                }
                vm.outputDimensions()?.let { (w, h) ->
                    Text("Output ${w}×$h. Higher is sharper but slower.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (problem != null) {
            Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = vm::start,
            enabled = settings != null,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Amplify motion") }
        TipsCard()
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun ProcessingScreen(vm: MainViewModel, modifier: Modifier) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { /* Stay here; use Cancel. */ }
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Amplifying motion…", style = MaterialTheme.typography.titleLarge)
        LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
        val eta = vm.etaSeconds
        Text(
            "${(vm.progress * 100).roundToInt()}%" +
                if (eta != null) " · about ${eta / 60}:${"%02d".format(eta % 60)} left" else "",
        )
        Text(
            "Keep Roulade open; the screen stays on until it finishes.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
    }
}

@Composable
private fun ResultScreen(vm: MainViewModel, modifier: Modifier, onShare: () -> Unit) {
    val video = vm.video ?: return
    var showOriginal by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !showOriginal,
                onClick = { showOriginal = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Amplified") }
            SegmentedButton(
                selected = showOriginal,
                onClick = { showOriginal = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Original") }
        }
        val uri = if (showOriginal) video.uri else Uri.fromFile(vm.resultFile)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            contentAlignment = Alignment.Center,
        ) {
            LoopingVideo(uri, "${vm.resultVersion}", Modifier.aspectRatio(video.info.displayAspect))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::saveToGallery, modifier = Modifier.weight(1f)) { Text("Save to gallery") }
            FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::backToSettings, modifier = Modifier.weight(1f)) { Text("Adjust settings") }
            OutlinedButton(onClick = vm::reset, modifier = Modifier.weight(1f)) { Text("New video") }
        }
        Text(
            "Colour is kept from the original; only brightness detail is motion-amplified, and audio is removed.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
