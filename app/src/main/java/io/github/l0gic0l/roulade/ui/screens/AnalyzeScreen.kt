package io.github.l0gic0l.roulade.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SensorsOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.roulade.analysis.AnalysisSettings
import io.github.l0gic0l.roulade.analysis.AnalysisSettings.Companion.fmt
import io.github.l0gic0l.roulade.analysis.BandPreset
import io.github.l0gic0l.roulade.analysis.OutputQuality
import io.github.l0gic0l.roulade.analysis.ViewMode
import io.github.l0gic0l.roulade.ui.AppViewModel
import io.github.l0gic0l.roulade.ui.Screen
import io.github.l0gic0l.roulade.ui.components.ChoiceRow
import io.github.l0gic0l.roulade.ui.components.Hint
import io.github.l0gic0l.roulade.ui.components.NumberField
import io.github.l0gic0l.roulade.ui.components.SettingsSection
import io.github.l0gic0l.roulade.ui.components.SliderRow
import io.github.l0gic0l.roulade.ui.components.SpectrumChart
import io.github.l0gic0l.roulade.ui.components.SwitchRow
import io.github.l0gic0l.roulade.video.outputSize
import kotlin.math.PI
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val clip = vm.clip ?: return
    val s = vm.settings
    val problem = vm.problem()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler { vm.screen = Screen.Home }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(clip.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = vm::resetSettings) { Icon(Icons.Outlined.RestartAlt, "Reset settings to defaults") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::start,
                icon = { Icon(Icons.Outlined.AutoAwesome, null) },
                text = { Text("Amplify") },
                containerColor = if (problem == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ClipCard(vm) }
            if (problem != null) {
                item {
                    Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { BandSection(vm, s) }
            item { FinderSection(vm, s) }
            item { AmplificationSection(vm, s) }
            item { StabilizationSection(vm, s) }
            item { TimingSection(vm, s) }
            item { OutputSection(vm, s) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipCard(vm: AppViewModel) {
    val clip = vm.clip ?: return
    val info = clip.info
    ElevatedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            vm.thumbnail?.let {
                Image(
                    it.asImageBitmap(), "First frame",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(20.dp)),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (dw, dh) = if (info.rotation % 180 == 0) info.width to info.height else info.height to info.width
                SuggestionChip(onClick = {}, label = { Text("${dw}×$dh") })
                SuggestionChip(onClick = {}, label = { Text("${fmt(info.frameRate.toDouble())} fps") })
                SuggestionChip(onClick = {}, label = { Text("${fmt(info.durationMs / 100 / 10.0)} s") })
                if (clip.sensorLog != null) {
                    AssistChip(onClick = {}, label = { Text("Sensor log") }, leadingIcon = { Icon(Icons.Outlined.Sensors, null, Modifier.size(18.dp)) })
                } else {
                    AssistChip(onClick = {}, label = { Text("No sensors") }, leadingIcon = { Icon(Icons.Outlined.SensorsOff, null, Modifier.size(18.dp)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandSection(vm: AppViewModel, s: AnalysisSettings) {
    val fps = vm.clipFps
    var showRpm by rememberSaveable { mutableStateOf(false) }
    SettingsSection("Frequency band", Icons.Outlined.GraphicEq, "Only motion between these frequencies is amplified") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BandPreset.entries.forEach { p ->
                FilterChip(
                    selected = s.lowHz == p.lowHz && s.highHz == p.highHz,
                    onClick = { vm.applyPreset(p) },
                    label = { Text(p.label) },
                )
            }
        }
        BandPreset.entries.firstOrNull { s.lowHz == it.lowHz && s.highHz == it.highHz }?.let {
            Hint("${it.label}: ${fmt(it.lowHz)}–${fmt(it.highHz)} Hz. ${it.hint}.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Low", s.lowHz, { v -> v?.let { vm.updateSettings(s.copy(lowHz = it)) } }, Modifier.weight(1f), suffix = "Hz")
            NumberField("High", s.highHz, { v -> v?.let { vm.updateSettings(s.copy(highHz = it)) } }, Modifier.weight(1f), suffix = "Hz")
        }
        NumberField(
            "Capture frame rate", s.captureFps,
            { v -> vm.updateSettings(s.copy(captureFps = v)) }, Modifier.fillMaxWidth(), suffix = "fps",
        )
        Hint("Highest usable frequency: ${fmt(fps / 2)} Hz. For slow-motion clips enter the real capture rate (e.g. 240).")
        ChoiceRow(
            "Filter sharpness", listOf(1, 2, 3), s.filterOrder,
            { when (it) { 1 -> "Gentle"; 2 -> "Sharp"; else -> "Sharper" } },
            { vm.updateSettings(s.copy(filterOrder = it)) },
            hint = "Butterworth order ${s.filterOrder}. Sharper isolates one mode from its neighbours but uses more memory.",
        )
        TextButton(onClick = { showRpm = !showRpm }) { Text(if (showRpm) "Hide machine-speed helper" else "Set band from machine speed (RPM)") }
        AnimatedVisibility(showRpm) { RpmHelper(vm) }
    }
}

@Composable
private fun RpmHelper(vm: AppViewModel) {
    var rpm by rememberSaveable { mutableDoubleStateOf(1800.0) }
    var harmonic by rememberSaveable { mutableIntStateOf(1) }
    var width by rememberSaveable { mutableStateOf(10f) }
    val hz = rpm / 60.0 * harmonic
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField("Running speed", rpm, { v -> v?.let { rpm = it } }, Modifier.fillMaxWidth(), suffix = "rpm")
        ChoiceRow("Order", listOf(1, 2, 3), harmonic, { "$it×" }, { harmonic = it }, hint = "1× is running speed (imbalance); 2× often shows misalignment or looseness.")
        SliderRow("Band width", "±${width.roundToInt()}%", width, { width = it.roundToInt().toFloat() }, 3f..30f)
        FilledTonalButton(onClick = { vm.centreBandOn(hz, width / 100.0) }) {
            Text("Use ${fmt(Math.round(hz * 100) / 100.0)} Hz ±${width.roundToInt()}%")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinderSection(vm: AppViewModel, s: AnalysisSettings) {
    SettingsSection("Frequency finder", Icons.Outlined.Insights, "Scan the clip for the frequencies things vibrate at") {
        SwitchRow(
            "Ignore camera shake", s.rejectCameraShake, { vm.updateSettings(s.copy(rejectCameraShake = it)) },
            subtitle = "Removes motion shared by the whole picture",
        )
        if (vm.scanning) {
            LinearProgressIndicator(progress = { vm.scanProgress }, modifier = Modifier.fillMaxWidth())
        } else {
            FilledTonalButton(onClick = vm::scanFrequencies) {
                Icon(Icons.Outlined.Insights, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (vm.spectrum == null) "Scan for frequencies" else "Scan again")
            }
        }
        vm.spectrum?.let { sp ->
            SpectrumChart(sp, s.lowHz, s.highHz, vm.clipFps / 2, onTapFrequency = { vm.centreBandOn(it) })
            Hint("Shaded: current band. Tap the chart or a peak to centre the band on it (±15%).")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sp.peaks.forEach { p ->
                    AssistChip(onClick = { vm.centreBandOn(p.toDouble()) }, label = { Text("${fmt(Math.round(p * 100) / 100.0)} Hz") })
                }
            }
        }
    }
}

@Composable
private fun AmplificationSection(vm: AppViewModel, s: AnalysisSettings) {
    SettingsSection("Amplification", Icons.Outlined.OpenInFull, "How much, and at which scales") {
        SliderRow(
            "Factor", "${s.alpha.roundToInt()}×", s.alpha,
            { vm.updateSettings(s.copy(alpha = it.roundToInt().toFloat())) }, 1f..150f,
            hint = "In-band motion is enlarged roughly this much. 10–30 suits most structures.",
        )
        val limits = listOf(0f, (2 * PI).toFloat(), PI.toFloat(), (PI / 2).toFloat())
        ChoiceRow(
            "Ghosting limiter", limits, limits.minByOrNull { kotlin.math.abs(it - s.phaseLimit) } ?: 0f,
            { when (it) { 0f -> "Off"; limits[1] -> "Light"; limits[2] -> "Medium"; else -> "Strong" } },
            { vm.updateSettings(s.copy(phaseLimit = it)) },
            hint = "Caps the phase shift so large factors don't smear edges into ghosts.",
        )
        ChoiceRow(
            "Fine detail", listOf(0, 1, 2), s.skipFineLevels,
            { when (it) { 0 -> "All scales"; 1 -> "Skip finest"; else -> "Skip 2 finest" } },
            { vm.updateSettings(s.copy(skipFineLevels = it)) },
            hint = "Skipping the finest scales cuts sensor noise and shimmer on textured surfaces.",
        )
        val sigmas = listOf(0f, 1f, 2f, 3f)
        ChoiceRow(
            "Noise reduction", sigmas, sigmas.minByOrNull { kotlin.math.abs(it - s.denoiseSigma) } ?: 2f,
            { when (it) { 0f -> "Off"; 1f -> "Low"; 2f -> "Medium"; else -> "High" } },
            { vm.updateSettings(s.copy(denoiseSigma = it)) },
            hint = "Smooths the measured motion, weighted towards well-textured areas.",
        )
    }
}

@Composable
private fun StabilizationSection(vm: AppViewModel, s: AnalysisSettings) {
    val hasLog = vm.clip?.sensorLog != null
    val st = s.stabilizer
    fun upd(f: (io.github.l0gic0l.roulade.stab.StabilizerSettings) -> io.github.l0gic0l.roulade.stab.StabilizerSettings) =
        vm.updateSettings(s.copy(stabilizer = f(st)))
    SettingsSection(
        "Sensor stabilisation", Icons.Outlined.Sensors,
        if (hasLog) "Uses the gyroscope, gravity and accelerometer log" else "Record in Roulade to log motion sensors",
    ) {
        val on = hasLog && s.stabilize
        SwitchRow(
            "Stabilise", on, { vm.updateSettings(s.copy(stabilize = it)) },
            subtitle = if (hasLog) "Holds the view still so camera shake isn't amplified" else "No sensor log for this clip",
            enabled = hasLog,
        )
        if (on) {
            ChoiceRow(
                "Mode", listOf(true, false), st.lockView,
                { if (it) "Lock view" else "Smooth" }, { v -> upd { it.copy(lockView = v) } },
                hint = if (st.lockView) "Tripod or braced: remove all camera motion." else "Handheld: remove shake but keep slow pans.",
            )
            if (!st.lockView) {
                SliderRow(
                    "Smoothing window", "${fmt(st.smoothSeconds.toDouble())} s", st.smoothSeconds,
                    { v -> upd { it.copy(smoothSeconds = Math.round(v * 10) / 10f) } }, 0.2f..3f,
                )
            }
            SwitchRow("Pitch & yaw", st.rotation, { v -> upd { it.copy(rotation = v) } }, "Gyroscope, the main source of image shake")
            SwitchRow("Roll", st.roll, { v -> upd { it.copy(roll = v) } }, "Rotation about the lens axis")
            SwitchRow("Gravity drift correction", st.gravityFusion, { v -> upd { it.copy(gravityFusion = v) } }, "Gravity sensor cancels slow gyroscope drift")
            SwitchRow(
                "Translation", st.translation, { v -> upd { it.copy(translation = v) } },
                "Accelerometer, double-integrated. Only matters for close subjects",
            )
            if (st.translation) {
                NumberField(
                    "Distance to subject", st.subjectDistanceM.toDouble(),
                    { v -> v?.takeIf { it > 0 }?.let { d -> upd { it.copy(subjectDistanceM = d.toFloat()) } } },
                    Modifier.fillMaxWidth(), suffix = "m",
                )
            }
            SwitchRow(
                "Auto-sync sensors", st.autoSync, { v -> upd { it.copy(autoSync = v) } },
                "Matches sensor timing to the video's own motion (needs some shake)",
            )
            SliderRow(
                if (st.autoSync) "Search centre" else "Sensor time offset", "${st.offsetMs.roundToInt()} ms", st.offsetMs,
                { v -> upd { it.copy(offsetMs = (v / 5).roundToInt() * 5f) } }, -300f..300f,
                hint = "Positive if the sensors lag the picture.",
            )
            SliderRow(
                "Edge crop", "${s.cropPercent.roundToInt()}%", s.cropPercent,
                { vm.updateSettings(s.copy(cropPercent = it.roundToInt().toFloat())) }, 0f..15f,
                hint = "Zooms in slightly to hide edges exposed by the correction.",
            )
        }
    }
}

@Composable
private fun TimingSection(vm: AppViewModel, s: AnalysisSettings) {
    val duration = (vm.clip?.info?.durationMs ?: 0) / 1000f
    SettingsSection("Clip & timing", Icons.Outlined.ContentCut, "Trim and slow down the output") {
        if (duration > 1f) {
            val end = s.trimEndS ?: duration
            Column {
                Row {
                    Text("Trim", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "${fmt(Math.round(s.trimStartS * 10) / 10.0)}–${fmt(Math.round(end * 10) / 10.0)} s",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
                RangeSlider(
                    value = s.trimStartS..end,
                    onValueChange = { r ->
                        vm.updateSettings(
                            s.copy(
                                trimStartS = Math.round(r.start * 10) / 10f,
                                trimEndS = (Math.round(r.endInclusive * 10) / 10f).takeIf { it < duration - 0.05f },
                            ),
                        )
                    },
                    valueRange = 0f..duration,
                )
                Hint("Frames just before the start still settle the filter, so the output begins clean.")
            }
        }
        val speeds = listOf(1f, 0.5f, 0.25f, 0.125f)
        ChoiceRow(
            "Playback speed", speeds, speeds.minByOrNull { kotlin.math.abs(it - s.playbackSpeed) } ?: 1f,
            { if (it == 1f) "1×" else "1/${(1 / it).roundToInt()}" },
            { vm.updateSettings(s.copy(playbackSpeed = it)) },
            hint = "Slows the output so faster vibration is easy to follow.",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutputSection(vm: AppViewModel, s: AnalysisSettings) {
    SettingsSection("Output", Icons.Outlined.VideoSettings, "What the result shows") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ViewMode.entries.forEach { m ->
                FilterChip(selected = s.viewMode == m, onClick = { vm.updateSettings(s.copy(viewMode = m)) }, label = { Text(m.label) })
            }
        }
        Hint(
            when (s.viewMode) {
                ViewMode.AMPLIFIED -> "The amplified video."
                ViewMode.SIDE_BY_SIDE -> "Original and amplified next to each other, for reports."
                ViewMode.MOTION_ONLY -> "Grey except where things move: shows mode shapes clearly."
                ViewMode.HEATMAP -> "Colours where in-band motion is strongest (blue → red)."
            },
        )
        if (s.viewMode == ViewMode.HEATMAP) {
            SliderRow(
                "Heatmap opacity", "${(s.heatmapOpacity * 100).roundToInt()}%", s.heatmapOpacity,
                { vm.updateSettings(s.copy(heatmapOpacity = Math.round(it * 20) / 20f)) }, 0.2f..1f,
            )
        }
        SwitchRow("Black & white", s.grayscale, { vm.updateSettings(s.copy(grayscale = it)) }, "Removes colour distractions")
        val clip = vm.clip
        ChoiceRow(
            "Resolution", listOf(480, 720, 1080), s.shortSide, { "${it}p" },
            { vm.updateSettings(s.copy(shortSide = it)) },
            hint = clip?.let {
                val (w, h) = outputSize(it.info.width, it.info.height, s.shortSide)
                val (dw, dh) = if (it.info.rotation % 180 == 0) w to h else h to w
                "Output ${dw}×$dh${if (s.viewMode == ViewMode.SIDE_BY_SIDE) " per side" else ""}. Higher is sharper but slower."
            },
        )
        ChoiceRow(
            "Quality", OutputQuality.entries, s.quality, { it.label },
            { vm.updateSettings(s.copy(quality = it)) },
            hint = "Bit rate of the saved video.",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Hint("Settings are remembered for next time.")
        }
    }
}
