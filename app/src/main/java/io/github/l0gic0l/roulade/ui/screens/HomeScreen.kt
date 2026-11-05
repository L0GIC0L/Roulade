package io.github.l0gic0l.roulade.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.l0gic0l.roulade.ui.AppViewModel
import io.github.l0gic0l.roulade.ui.Recording
import io.github.l0gic0l.roulade.ui.Screen
import java.text.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.open(uri, null, "Gallery video")
    }
    var toDelete by remember { mutableStateOf<Recording?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Roulade")
                        Text(
                            "Motion amplification for structures",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ActionCard(
                    icon = Icons.Outlined.Videocam,
                    title = "Record with sensors",
                    text = "Video plus gyroscope, accelerometer and gravity, so shake can be removed. Includes a spirit level.",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = { vm.screen = Screen.Capture },
                )
            }
            item {
                ActionCard(
                    icon = Icons.Outlined.VideoLibrary,
                    title = "Open a video",
                    text = "Any clip from your gallery, including slow motion from the camera app (no sensor stabilisation).",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                )
            }
            if (vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Text(
                    "Recordings",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }
            if (vm.recordings.isEmpty()) {
                item {
                    Text(
                        "Clips you record in Roulade appear here with their sensor logs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            items(vm.recordings, key = { it.video.path }) { r ->
                Card(
                    onClick = { vm.openRecording(r) },
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Movie, null) },
                        headlineContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(r.date)) },
                        supportingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("%.1f MB".format(r.sizeMb))
                                if (r.sensors != null) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Outlined.Sensors, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(" sensor log", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { context.startActivity(vm.shareRecordingIntent(r)) }) {
                                    Icon(Icons.Outlined.Share, "Share video and sensor CSV")
                                }
                                IconButton(onClick = { toDelete = r }) { Icon(Icons.Outlined.Delete, "Delete") }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item { TipsCard() }
        }
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("Delete recording?") },
            text = { Text("The video and its sensor log will be removed from Roulade.") },
            confirmButton = { TextButton(onClick = { vm.deleteRecording(r); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(36.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TipsCard() {
    OutlinedCard(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text("Field tips", style = MaterialTheme.typography.titleSmall)
            }
            Text("• Use a tripod or brace the phone. Sensor stabilisation removes what's left.")
            Text("• Lock exposure in steady, bright light; flicker and noise get amplified.")
            Text("• Capture more than twice the frequency you want to see: 30 fps shows up to 15 Hz.")
            Text("• Not sure of the frequency? Use the frequency finder on the analysis screen.")
            Text("• Compare with known references; amplified motion is a visual aid, not a measurement.")
        }
    }
}
