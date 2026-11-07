package com.unicast.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

fun formatTime(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnicastApp(vm: AppViewModel = viewModel()) {
    // The admin screen is hidden: long-press the "Unicast" title to open it.
    var onAdmin by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    BackHandler(enabled = onAdmin) { onAdmin = false }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Unicast",
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onLongPress = { onAdmin = true })
                        },
                    )
                },
                navigationIcon = {
                    if (onAdmin) {
                        TextButton(onClick = { onAdmin = false }) { Text("Back") }
                    }
                },
            )
        },
        bottomBar = {
            if (vm.playingId != null) MiniPlayer(vm)
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (onAdmin) AdminScreen(vm) else HomeScreen(vm)
        }
    }
}

// ---------------------------------------------------------------- listener

@Composable
private fun HomeScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadEpisodes() }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                "Episodes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.loadEpisodes() }, enabled = !vm.loading) {
                Text(if (vm.loading) "Loading..." else "Refresh")
            }
        }

        if (vm.episodes.isEmpty() && !vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No episodes yet. Check back soon.")
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.episodes, key = { it.id }) { ep -> EpisodeCard(vm, ep) }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    vm: AppViewModel,
    episode: Episode,
    showDownload: Boolean = true,
    onDelete: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(episode.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${episode.createdAt.take(10)} · ${formatTime((episode.durationSeconds ?: 0).toLong())}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = vm.playingId == episode.id
                Button(onClick = { vm.play(episode) }) {
                    Text(if (current && vm.isPlaying) "Pause" else "Play")
                }
                if (showDownload) {
                    OutlinedButton(onClick = { vm.download(episode) }) { Text("Download") }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, enabled = !vm.busy) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(vm: AppViewModel) {
    var pos by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(vm.playingId) {
        while (true) {
            if (!dragging) pos = vm.positionMs()
            delay(300)
        }
    }

    val dur = vm.durationMs()

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(vm.playingTitle, maxLines = 1, style = MaterialTheme.typography.titleSmall)
            Slider(
                value = if (dur > 0) pos.coerceIn(0L, dur).toFloat() else 0f,
                onValueChange = {
                    dragging = true
                    pos = it.toLong()
                },
                onValueChangeFinished = {
                    vm.seekTo(pos)
                    dragging = false
                },
                valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatTime(pos / 1000)} / ${formatTime(dur / 1000)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.togglePlayback() }) {
                    Text(if (vm.isPlaying) "Pause" else "Play")
                }
                TextButton(onClick = { vm.stopPlayback() }) { Text("Close") }
            }
        }
    }
}

// ------------------------------------------------------------------- admin

@Composable
private fun AdminScreen(vm: AppViewModel) {
    if (vm.signedIn) AdminPanel(vm) else LoginForm(vm)
}

@Composable
private fun LoginForm(vm: AppViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Admin sign in", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.signIn(email.trim(), password) },
            enabled = !vm.busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (vm.busy) "Signing in..." else "Sign in")
        }
    }
}

@Composable
private fun AdminPanel(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadEpisodes() }
    var toDelete by remember { mutableStateOf<Episode?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Signed in as admin", modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
            }
        }
        item { RecorderCard(vm) }
        item { Text("Your episodes", style = MaterialTheme.typography.titleMedium) }
        items(vm.episodes, key = { it.id }) { ep ->
            EpisodeCard(vm, ep, showDownload = false, onDelete = { toDelete = ep })
        }
    }

    toDelete?.let { ep ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete episode?") },
            text = { Text("\"${ep.title}\" will be removed for everyone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEpisode(ep)
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecorderCard(vm: AppViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    var seconds by remember { mutableLongStateOf(0L) }

    // Keep the screen on while recording, and update the timer.
    LaunchedEffect(vm.recording) {
        view.keepScreenOn = vm.recording
        while (vm.recording) {
            seconds = vm.recordingSeconds()
            delay(500)
        }
        seconds = 0L
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startRecording() else vm.message = "Microphone permission is needed to record."
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Record a new episode", style = MaterialTheme.typography.titleMedium)
            val pending = vm.pendingRecording
            when {
                !vm.recordingSupported -> Text("Recording needs Android 10 or newer.")
                vm.recording -> {
                    Text(formatTime(seconds), style = MaterialTheme.typography.headlineMedium)
                    Text("Keep the app open while recording.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.stopRecording() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop recording")
                    }
                }
                pending != null -> PendingRecording(vm, pending)
                else -> Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) vm.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start recording")
                }
            }
        }
    }
}

@Composable
private fun PendingRecording(vm: AppViewModel, rec: RecordingResult) {
    var title by remember(rec) { mutableStateOf("") }

    Text("Recorded ${formatTime(rec.seconds.toLong())} (${rec.file.length() / 1024} KB)")
    OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Episode title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { vm.uploadRecording(title) },
        enabled = !vm.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (vm.busy) "Uploading..." else "Upload")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.previewRecording() }) { Text("Preview") }
        TextButton(onClick = { vm.discardRecording() }, enabled = !vm.busy) { Text("Discard") }
    }
}
