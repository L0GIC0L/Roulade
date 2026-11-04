package io.github.l0gic0l.roulade.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.l0gic0l.roulade.ui.screens.AnalyzeScreen
import io.github.l0gic0l.roulade.ui.screens.CaptureScreen
import io.github.l0gic0l.roulade.ui.screens.HomeScreen
import io.github.l0gic0l.roulade.ui.screens.ProcessingScreen
import io.github.l0gic0l.roulade.ui.screens.ResultScreen

@Composable
fun RouladeApp(vm: AppViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.error) { vm.error?.let { snackbar.showSnackbar(it); vm.error = null } }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    LaunchedEffect(Unit) { vm.refreshRecordings() }

    AnimatedContent(
        targetState = vm.screen,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(vm, snackbar)
            Screen.Capture -> CaptureScreen(vm, snackbar)
            Screen.Analyze -> AnalyzeScreen(vm, snackbar)
            Screen.Processing -> ProcessingScreen(vm)
            Screen.Result -> ResultScreen(vm, snackbar)
        }
    }
}
