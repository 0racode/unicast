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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
    // Check every 10 seconds whether a live show has started.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshLiveStatus()
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LiveBanner(vm)
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
private fun LiveBanner(vm: AppViewModel) {
    if (!vm.liveNow && vm.liveMode == LiveMode.None) return

    val context = LocalContext.current
    var askDialog by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) askDialog = true else vm.message = "Microphone permission is needed to speak."
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LIVE NOW", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (vm.liveMode) {
                            LiveMode.None -> "A live show is on. Tap Listen to join."
                            LiveMode.Connecting -> "Connecting..."
                            LiveMode.Listening -> "You are listening live."
                            LiveMode.Broadcasting -> "You are broadcasting. Manage it in admin."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when (vm.liveMode) {
                    LiveMode.None -> Button(onClick = { vm.listenLive() }) { Text("Listen") }
                    LiveMode.Listening, LiveMode.Connecting ->
                        OutlinedButton(onClick = { vm.stopListening() }) { Text("Stop") }
                    LiveMode.Broadcasting -> {}
                }
            }

            if (vm.liveMode == LiveMode.Listening) {
                Spacer(Modifier.height(8.dp))
                when (vm.handState) {
                    HandState.None -> OutlinedButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) askDialog = true else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ask to speak") }

                    HandState.Requested -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Waiting for the host to let you speak...",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.finishSpeaking() }) { Text("Cancel") }
                    }

                    HandState.Speaking -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (vm.micOn) "Your microphone is on." else "You can speak. Your microphone is off.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { vm.toggleMic() }) {
                                Text(if (vm.micOn) "Mute" else "Unmute")
                            }
                            TextButton(onClick = { vm.finishSpeaking() }) { Text("Finish") }
                        }
                        Text(
                            "Use headphones while speaking so the show does not echo.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (askDialog) {
        var name by remember { mutableStateOf(vm.savedName()) }
        AlertDialog(
            onDismissRequest = { askDialog = false },
            title = { Text("Ask to speak") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The host will see your name and can let you speak.")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askDialog = false
                    vm.askToSpeak(name)
                }) { Text("Send request") }
            },
            dismissButton = { TextButton(onClick = { askDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EpisodeCard(
    vm: AppViewModel,
    episode: Episode,
    showDownload: Boolean = true,
    onDelete: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(episode.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${episode.createdAt.take(10)} · ${formatTime((episode.durationSeconds ?: 0).toLong())}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!episode.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(episode.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (onDelete != null) {
                // Only the admin screen shows the counts.
                Text(
                    "${episode.playCount} plays · ${episode.downloadCount} downloads",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = vm.playingId == episode.id
                Button(onClick = { vm.play(episode) }) {
                    Text(if (current && vm.isPlaying) "Pause" else "Play")
                }
                if (showDownload) {
                    OutlinedButton(onClick = { vm.download(episode) }) { Text("Download") }
                }
                if (onEdit != null) {
                    TextButton(onClick = onEdit, enabled = !vm.busy) { Text("Edit") }
                }
                TextButton(onClick = { vm.share(episode) }) { Text("Share") }
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
                TextButton(onClick = { vm.cycleSpeed() }) {
                    Text("${vm.speed.toString().removeSuffix(".0")}x")
                }
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
    val view = LocalView.current
    LaunchedEffect(Unit) { vm.loadEpisodes() }
    LaunchedEffect(Unit) { vm.refreshLiveStatus() }
    // Keep the screen on while recording or broadcasting.
    LaunchedEffect(vm.recording, vm.liveMode) {
        view.keepScreenOn = vm.recording || vm.liveMode == LiveMode.Broadcasting
    }
    var toDelete by remember { mutableStateOf<Episode?>(null) }
    var toEdit by remember { mutableStateOf<Episode?>(null) }

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
        item { LiveCard(vm) }
        item { RecorderCard(vm) }
        item { Text("Your episodes", style = MaterialTheme.typography.titleMedium) }
        items(vm.episodes, key = { it.id }) { ep ->
            EpisodeCard(
                vm,
                ep,
                showDownload = false,
                onDelete = { toDelete = ep },
                onEdit = { toEdit = ep },
            )
        }
    }

    toEdit?.let { ep ->
        var title by remember(ep.id) { mutableStateOf(ep.title) }
        var description by remember(ep.id) { mutableStateOf(ep.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { toEdit = null },
            title = { Text("Edit episode") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description (optional)") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.editEpisode(ep, title, description)
                    toEdit = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { toEdit = null }) { Text("Cancel") } },
        )
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
private fun LiveCard(vm: AppViewModel) {
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.goLive() else vm.message = "Microphone permission is needed to go live."
    }

    LaunchedEffect(Unit) { vm.refreshUsage() }
    val usedMinutes = vm.usageSeconds / 60

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live broadcast", style = MaterialTheme.typography.titleMedium)
            Text(
                "Estimated minutes used this month: $usedMinutes of ${Config.FREE_MINUTES} (free plan)",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { (usedMinutes.toFloat() / Config.FREE_MINUTES).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (usedMinutes >= Config.FREE_MINUTES * 0.8) {
                Text(
                    "You have used over 80% of your free minutes. Check LiveKit's dashboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (vm.liveMode) {
                LiveMode.Broadcasting -> {
                    Text(
                        "${vm.listenerCount} listening now",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text("You are live. Listeners can hear you, even if you lock the screen.")
                    Button(
                        onClick = { vm.endLive() },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("End live") }

                    if (vm.speakRequests.isNotEmpty()) {
                        Text("Requests to speak", style = MaterialTheme.typography.titleSmall)
                        vm.speakRequests.forEach { req ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(req.name)
                                    Text(
                                        if (req.status == "approved") "Speaking now" else "Waiting",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (req.status == "approved") {
                                    OutlinedButton(onClick = { vm.speakerAction("end_speaker", req.id) }) {
                                        Text("End")
                                    }
                                } else {
                                    Button(onClick = { vm.speakerAction("approve_speaker", req.id) }) {
                                        Text("Allow")
                                    }
                                    TextButton(onClick = { vm.speakerAction("decline_speaker", req.id) }) {
                                        Text("Decline")
                                    }
                                }
                            }
                        }
                    }
                }
                LiveMode.Connecting -> Text("Connecting...")
                LiveMode.Listening -> Text("You are listening to the live show. Stop it on the home screen first.")
                LiveMode.None -> {
                    if (vm.liveNow) {
                        Text("The live flag is still on from an earlier session.")
                        OutlinedButton(
                            onClick = { vm.endLive() },
                            enabled = !vm.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Mark as offline") }
                    }
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) vm.goLive() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        enabled = !vm.recording && !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Go live") }
                }
            }
        }
    }
}

@Composable
private fun RecorderCard(vm: AppViewModel) {
    val context = LocalContext.current
    var seconds by remember { mutableLongStateOf(0L) }

    // Update the recording timer.
    LaunchedEffect(vm.recording) {
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
                    Text(
                        if (vm.recordingPaused) "Paused." else "Recording. Keep the app open.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (vm.recordingPaused) vm.resumeRecording() else vm.pauseRecording() },
                        ) { Text(if (vm.recordingPaused) "Resume" else "Pause") }
                        Button(onClick = { vm.stopRecording() }) { Text("Stop recording") }
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
    var description by remember(rec) { mutableStateOf("") }

    Text("Recorded ${formatTime(rec.seconds.toLong())} (${rec.file.length() / 1024} KB)")
    OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Episode title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = description,
        onValueChange = { description = it },
        label = { Text("Description (optional)") },
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { vm.uploadRecording(title, description) },
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
