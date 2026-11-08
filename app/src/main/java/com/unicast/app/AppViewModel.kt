package com.unicast.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

private const val PREVIEW_ID = "preview"

/** Extra loudness for episodes, in millibels (1200 = +12 dB). Lower it if voices ever crackle. */
private const val PLAYBACK_BOOST_MB = 1200

enum class LiveMode { None, Connecting, Listening, Broadcasting }

/** A listener's state when asking to speak during a live show. */
enum class HandState { None, Requested, Speaking }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("unicast", Context.MODE_PRIVATE)
    private val recorder = OpusRecorder(app)
    private var session: Session? = null
    private var expiresAtMs = 0L
    private var player: ExoPlayer? = null
    private var loudness: LoudnessEnhancer? = null
    private var lastPositionSave = 0L
    private var serviceRunning = false

    var episodes by mutableStateOf<List<Episode>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var signedIn by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var recordingPaused by mutableStateOf(false)
        private set
    var pendingRecording by mutableStateOf<RecordingResult?>(null)
    /** True while your own voice is also being recorded during a live show. */
    var liveRecording by mutableStateOf(false)
        private set
        private set
    var playingId by mutableStateOf<String?>(null)
        private set
    var playingTitle by mutableStateOf("")
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var speed by mutableStateOf(1f)
        private set
    var liveNow by mutableStateOf(false)
        private set
    var liveMode by mutableStateOf(LiveMode.None)
        private set
    var listenerCount by mutableStateOf(0)
        private set
    var usageSeconds by mutableStateOf(0L)
        private set

    // Listener side of "ask to speak"
    var handState by mutableStateOf(HandState.None)
        private set
    var micOn by mutableStateOf(false)
        private set
    private var handId: String? = null
    private var myIdentity = ""

    // Admin side of "ask to speak"
    var speakRequests by mutableStateOf<List<SpeakRequest>>(emptyList())
        private set

    private var room: Room? = null

    // In-app updates
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateProgress by mutableStateOf(-1)
        private set
    var updateReady by mutableStateOf(false)
        private set
    private var updateDismissed = false
    private var lastUpdateCheck = 0L

    val versionCode: Int = Updater.installedVersionCode(app)
    val versionName: String = Updater.installedVersionName(app)

    // "Tell me when a live show starts"
    val liveAlertsAvailable: Boolean = LiveNotifications.available(app)
    var liveAlerts by mutableStateOf(prefs.getBoolean("live_alerts", true))
        private set

    var episodeAlerts by mutableStateOf(prefs.getBoolean("episode_alerts", true))
        private set

    val recordingSupported: Boolean get() = recorder.supported

    init {
        Updater.clearDownloads(app)
        LiveNotifications.setSubscribed(app, liveAlerts)
        LiveNotifications.setEpisodesSubscribed(app, episodeAlerts)
        restoreSession()
    }

    fun changeLiveAlerts(on: Boolean) {
        liveAlerts = on
        prefs.edit().putBoolean("live_alerts", on).apply()
        LiveNotifications.setSubscribed(getApplication<Application>(), on)
    }

    fun changeEpisodeAlerts(on: Boolean) {
        episodeAlerts = on
        prefs.edit().putBoolean("episode_alerts", on).apply()
        LiveNotifications.setEpisodesSubscribed(getApplication<Application>(), on)
    }

    /** Tells everyone's phones about a new episode. A failure here never blocks the upload. */
    private fun sendEpisodeNotification(token: String, title: String) {
        viewModelScope.launch {
            try {
                Api.notifyEpisode(token, title)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    // ---------- in-app updates ----------

    /** Looks for a newer release, at most once every 30 minutes. */
    fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (updateDismissed || update != null || now - lastUpdateCheck < 30 * 60 * 1000) return
        lastUpdateCheck = now
        viewModelScope.launch {
            update = Updater.check(getApplication<Application>())
            // The app may have been restarted by Android while the person allowed installs.
            continuePendingUpdate()
        }
    }

    /** Called whenever the app comes back to the front. */
    fun onAppResumed() = continuePendingUpdate()

    /**
     * If the person left to allow "install unknown apps", carry on with the update
     * as soon as they return, without making them tap Update again.
     */
    private fun continuePendingUpdate() {
        if (update == null || !prefs.getBoolean("pending_update", false)) return
        prefs.edit().remove("pending_update").apply()
        if (Updater.canInstall(getApplication<Application>())) {
            startUpdate()
        } else {
            message = "Installing apps is not allowed yet. Tap Update to try again."
        }
    }

    /** Sends the person to Android's "allow installs" screen and remembers to carry on after. */
    private fun askInstallPermission() {
        val app = getApplication<Application>()
        prefs.edit().putBoolean("pending_update", true).apply()
        message = "Allow Unicast to install apps, then come back. The update will start by itself."
        try {
            Updater.openInstallPermissionSettings(app)
        } catch (e: Exception) {
            prefs.edit().remove("pending_update").apply()
            message = "Open Settings, find Unicast, and allow installing apps."
        }
    }

    fun startUpdate() {
        val info = update ?: return
        if (updateProgress >= 0) return
        if (liveMode == LiveMode.Broadcasting) {
            message = "Finish your live show before updating."
            return
        }
        // Ask for permission first, so nothing is lost if Android restarts the app.
        if (!Updater.canInstall(getApplication<Application>())) {
            askInstallPermission()
            return
        }
        viewModelScope.launch {
            updateProgress = 0
            try {
                Updater.download(getApplication<Application>(), info) { updateProgress = it }
                updateReady = true
                updateProgress = -1
                installUpdate()
            } catch (e: Exception) {
                updateProgress = -1
                report(e)
            }
        }
    }

    fun installUpdate() {
        val app = getApplication<Application>()
        if (!Updater.canInstall(app)) {
            askInstallPermission()
            return
        }
        try {
            Updater.install(app, Updater.apkFile(app))
        } catch (e: Exception) {
            message = "Could not open the installer. Please try again."
        }
    }

    fun dismissUpdate() {
        updateDismissed = true
        update = null
    }

    // ---------- errors ----------

    private fun report(e: Exception) {
        if (e is CancellationException) throw e
        message = (e as? ApiException)?.message ?: "Something went wrong. Please try again."
    }

    // ---------- keep running in the background ----------

    /** Starts or stops the notification that keeps audio alive with the screen off. */
    private fun refreshService() {
        val app = getApplication<Application>()
        val broadcasting = liveMode == LiveMode.Broadcasting
        val listening = liveMode == LiveMode.Listening
        val playing = isPlaying && playingId != null && playingId != PREVIEW_ID
        val text = when {
            broadcasting -> "You are live"
            listening -> "Listening to the live show"
            playing -> "Playing: $playingTitle"
            else -> null
        }
        val intent = Intent(app, KeepAliveService::class.java)
        if (text == null) {
            if (serviceRunning) {
                runCatching { app.stopService(intent) }
                serviceRunning = false
            }
            return
        }
        intent.putExtra("text", text).putExtra("mic", broadcasting)
        try {
            ContextCompat.startForegroundService(app, intent)
            serviceRunning = true
        } catch (e: Exception) {
            // Android does not allow starting it right now. Audio still works while the app is open.
        }
    }

    // ---------- episodes ----------

    fun loadEpisodes() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            try {
                episodes = Api.listEpisodes()
            } catch (e: Exception) {
                report(e)
            } finally {
                loading = false
            }
        }
    }

    fun editEpisode(episode: Episode, title: String, description: String) {
        val clean = title.trim()
        if (clean.isEmpty()) {
            message = "The title cannot be empty."
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                Api.updateEpisode(ensureToken(), episode.id, clean, description.trim())
                if (playingId == episode.id) playingTitle = clean
                message = "Saved."
                loadEpisodes()
            } catch (e: Exception) {
                report(e)
            } finally {
                busy = false
            }
        }
    }

    fun share(episode: Episode) {
        val context = getApplication<Application>()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, episode.title)
            putExtra(
                Intent.EXTRA_TEXT,
                "Listen to \"${episode.title}\" on Unicast:\n${Api.publicUrl(episode.filePath)}",
            )
        }
        try {
            context.startActivity(
                Intent.createChooser(send, "Share episode").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            message = "Could not open sharing."
        }
    }

    // ---------- admin session ----------

    private fun setSession(s: Session?) {
        session = s
        signedIn = s != null
        if (s == null) {
            expiresAtMs = 0L
            prefs.edit().remove("refresh_token").apply()
        } else {
            expiresAtMs = System.currentTimeMillis() + s.expiresIn * 1000
            prefs.edit().putString("refresh_token", s.refreshToken).apply()
        }
    }

    private fun restoreSession() {
        val saved = prefs.getString("refresh_token", null) ?: return
        viewModelScope.launch {
            try {
                setSession(Api.refresh(saved))
            } catch (e: ApiException) {
                // Only forget the login if the server rejected it, not when offline.
                if (e.code in 400..403) setSession(null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun ensureToken(): String {
        val current = session ?: throw ApiException("Please sign in first.")
        if (System.currentTimeMillis() < expiresAtMs - 60_000) return current.accessToken
        val fresh = Api.refresh(current.refreshToken)
        setSession(fresh)
        return fresh.accessToken
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            busy = true
            try {
                setSession(Api.signIn(email, password))
                message = "Signed in."
            } catch (e: Exception) {
                report(e)
            } finally {
                busy = false
            }
        }
    }

    fun signOut() {
        if (liveMode == LiveMode.Broadcasting) {
            message = "End the live show first."
            return
        }
        discardRecording()
        setSession(null)
    }

    // ---------- live streaming ----------

    fun refreshLiveStatus() {
        viewModelScope.launch {
            try {
                liveNow = Api.isLive()
                if (!liveNow && liveMode == LiveMode.Listening) {
                    leaveRoom()
                    message = "The live show has ended."
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun leaveRoom() {
        room?.let {
            runCatching { it.disconnect() }
            runCatching { it.release() }
        }
        room = null
        liveMode = LiveMode.None
        handState = HandState.None
        handId = null
        micOn = false
        refreshService()
    }

    @Suppress("DEPRECATION")
    private fun useSpeaker() {
        runCatching {
            val audio = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.isSpeakerphoneOn = true
        }
    }

    /** Joins the live room as a listener. Listener tokens cannot publish until the host allows it. */
    fun listenLive() {
        if (liveMode != LiveMode.None) return
        stopPlayback()
        viewModelScope.launch {
            liveMode = LiveMode.Connecting
            try {
                val live = Api.fetchLiveToken(broadcaster = false, userToken = null)
                val r = LiveKit.create(getApplication<Application>().applicationContext)
                room = r
                val result: Any? = r.connect(live.url, live.token)
                if (result is Result<*> && result.isFailure) {
                    throw result.exceptionOrNull() ?: IllegalStateException("connect failed")
                }
                myIdentity = live.identity
                useSpeaker()
                liveMode = LiveMode.Listening
                refreshService()
            } catch (e: Exception) {
                leaveRoom()
                if (e is ApiException) report(e) else message = "Could not join the live show."
            }
        }
    }

    fun stopListening() {
        if (liveMode == LiveMode.Listening || liveMode == LiveMode.Connecting) leaveRoom()
    }

    // ----- listener asks to speak -----

    fun savedName(): String = prefs.getString("display_name", "") ?: ""

    fun askToSpeak(name: String) {
        if (liveMode != LiveMode.Listening || handState != HandState.None) return
        val clean = name.trim().ifEmpty { "Listener" }
        prefs.edit().putString("display_name", clean).apply()
        viewModelScope.launch {
            try {
                val id = Api.raiseHand(myIdentity, clean)
                handId = id
                handState = HandState.Requested
                micOn = false
                watchHand(id)
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private fun watchHand(id: String) {
        viewModelScope.launch {
            while (handId == id && handState != HandState.None) {
                try {
                    when (Api.mySpeakStatus(id)) {
                        "approved" -> if (handState == HandState.Requested) {
                            handState = HandState.Speaking
                            message = "The host says you can speak. Tap Unmute."
                        }
                        "declined" -> {
                            stopMicQuietly()
                            resetHand()
                            message = "The host could not take your question right now."
                        }
                        "ended" -> {
                            val wasSpeaking = handState == HandState.Speaking
                            stopMicQuietly()
                            resetHand()
                            if (wasSpeaking) message = "Your turn to speak has ended."
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                delay(2000)
            }
        }
    }

    private fun resetHand() {
        handState = HandState.None
        handId = null
        micOn = false
    }

    private suspend fun stopMicQuietly() {
        try {
            room?.localParticipant?.setMicrophoneEnabled(false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    fun toggleMic() {
        if (handState != HandState.Speaking) return
        val r = room ?: return
        val target = !micOn
        viewModelScope.launch {
            try {
                r.localParticipant.setMicrophoneEnabled(target)
                micOn = target
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = "Could not use the microphone yet. Wait a few seconds and try again."
            }
        }
    }

    /** The host mutes or unmutes their own microphone during a live show. */
    fun toggleHostMic() {
        if (liveMode != LiveMode.Broadcasting) return
        val r = room ?: return
        val target = !micOn
        viewModelScope.launch {
            try {
                r.localParticipant.setMicrophoneEnabled(target)
                micOn = target
                if (liveRecording) {
                    if (target) recorder.resume() else recorder.pause()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = "Could not change the microphone. Try again."
            }
        }
    }

    /** The listener finishes (or cancels) their turn. */
    fun finishSpeaking() {
        val id = handId
        viewModelScope.launch {
            stopMicQuietly()
            resetHand()
            if (id != null) {
                try {
                    Api.finishSpeaking(id)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
    }

    // ----- admin handles speak requests -----

    fun speakerAction(action: String, id: String) {
        viewModelScope.launch {
            try {
                val token = ensureToken()
                Api.speakerAction(token, action, id)
                speakRequests = Api.listSpeakRequests(token)
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    /** Starts broadcasting your microphone and tells listeners you are live. */
    fun goLive() {
        if (liveMode != LiveMode.None || recording) return
        stopPlayback()
        viewModelScope.launch {
            liveMode = LiveMode.Connecting
            try {
                val userToken = ensureToken()
                val live = Api.fetchLiveToken(broadcaster = true, userToken = userToken)
                val r = LiveKit.create(getApplication<Application>().applicationContext)
                room = r
                val result: Any? = r.connect(live.url, live.token)
                if (result is Result<*> && result.isFailure) {
                    throw result.exceptionOrNull() ?: IllegalStateException("connect failed")
                }
                r.localParticipant.setMicrophoneEnabled(true)
                micOn = true
                Api.setLive(userToken, true)
                liveNow = true
                liveMode = LiveMode.Broadcasting
                message = "You are live."
                startLiveRecording()
                refreshService()
                watchListenerCount()
                sendLiveNotification(userToken)
            } catch (e: Exception) {
                if (liveRecording) {
                    recorder.cancel()
                    liveRecording = false
                }
                leaveRoom()
                if (e is ApiException) report(e) else message = "Could not start the live show."
            }
        }
    }

    /** Asks the server to tell everyone's phones that you are live. */
    private fun sendLiveNotification(token: String) {
        viewModelScope.launch {
            try {
                Api.notifyLive(token)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = if ((e as? ApiException)?.code == 404) {
                    "You are live. Notifications are not set up yet."
                } else {
                    "You are live, but listeners could not be notified."
                }
            }
        }
    }

    private fun monthKey(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    /** Loads this month's estimated usage from the database. */
    fun refreshUsage() {
        if (!signedIn) return
        viewModelScope.launch {
            try {
                usageSeconds = Api.getUsageSeconds(ensureToken(), monthKey())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private suspend fun flushUsage(seconds: Int): Boolean = try {
        Api.addUsage(ensureToken(), monthKey(), seconds)
        usageSeconds += seconds
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    /**
     * Every 2 seconds: counts how many listeners are connected, adds them (plus you) to this
     * month's minutes estimate, and refreshes the list of listeners asking to speak.
     */
    private fun watchListenerCount() {
        viewModelScope.launch {
            var pending = 0
            while (liveMode == LiveMode.Broadcasting) {
                val listeners = room?.remoteParticipants?.size ?: 0
                listenerCount = listeners
                pending += (listeners + 1) * 2
                if (pending >= 60 && flushUsage(pending)) pending = 0
                if (pending > 3000) pending = 0
                try {
                    speakRequests = Api.listSpeakRequests(ensureToken())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
                delay(2000)
            }
            listenerCount = 0
            speakRequests = emptyList()
            if (pending in 1..3600) flushUsage(pending)
        }
    }

    /** Stops broadcasting (or just clears a stale live flag) and tells listeners you are offline. */
    fun endLive() {
        viewModelScope.launch {
            busy = true
            finishLiveRecording()
            leaveRoom()
            try {
                val token = ensureToken()
                Api.setLive(token, false)
                liveNow = false
                runCatching { Api.clearSpeakRequests(token) }
            } catch (e: Exception) {
                report(e)
            } finally {
                busy = false
            }
        }
    }

    // ---------- recording and upload ----------

    /** Records your voice while you are live, so the show can be saved as an episode. */
    private fun startLiveRecording() {
        if (!recorder.supported || recording || pendingRecording != null) {
            if (pendingRecording != null) {
                message = "You are live. This show will not be saved because a recording is waiting to be uploaded."
            }
            return
        }
        try {
            recorder.start()
            liveRecording = true
        } catch (e: Exception) {
            liveRecording = false
            message = "You are live, but this show could not be recorded."
        }
    }

    /** Stops the live recording and keeps it ready to be uploaded as an episode. */
    private fun finishLiveRecording() {
        if (!liveRecording) return
        liveRecording = false
        val result = recorder.stop()
        if (result == null) {
            message = "The live show was too short to save."
        } else {
            pendingRecording = result
            message = "Live show saved. Open the recorder, add a title and upload it as an episode."
        }
    }

    fun startRecording() {
        if (liveMode != LiveMode.None) {
            message = "You cannot start a new recording while live."
            return
        }
        stopPlayback()
        try {
            recorder.start()
            recording = true
            recordingPaused = false
        } catch (e: Exception) {
            message = "Could not start recording on this phone."
        }
    }

    fun recordingSeconds(): Long = recorder.elapsedSeconds()

    fun pauseRecording() {
        if (recorder.pause()) recordingPaused = true else message = "Could not pause."
    }

    fun resumeRecording() {
        if (recorder.resume()) recordingPaused = false else message = "Could not resume."
    }

    fun stopRecording() {
        recording = false
        recordingPaused = false
        val result = recorder.stop()
        if (result == null) {
            message = "That recording was too short. Please try again."
        } else {
            pendingRecording = result
        }
    }

    fun discardRecording() {
        if (recording) {
            recorder.cancel()
            recording = false
            recordingPaused = false
        }
        if (playingId == PREVIEW_ID) stopPlayback()
        pendingRecording?.file?.delete()
        pendingRecording = null
    }

    fun uploadRecording(title: String, description: String) {
        val rec = pendingRecording ?: return
        val clean = title.trim()
        if (clean.isEmpty()) {
            message = "Please type a title first."
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                val token = ensureToken()
                val path = "${System.currentTimeMillis()}.ogg"
                Api.uploadAudio(token, path, rec.file)
                Api.insertEpisode(token, clean, description.trim(), path, rec.seconds)
                if (playingId == PREVIEW_ID) stopPlayback()
                rec.file.delete()
                pendingRecording = null
                message = "Uploaded. Your episode is live."
                loadEpisodes()
                sendEpisodeNotification(token, clean)
            } catch (e: Exception) {
                report(e)
            } finally {
                busy = false
            }
        }
    }

    fun deleteEpisode(episode: Episode) {
        viewModelScope.launch {
            busy = true
            try {
                val token = ensureToken()
                Api.deleteEpisode(token, episode)
                if (playingId == episode.id) stopPlayback()
                message = "Deleted."
                loadEpisodes()
            } catch (e: Exception) {
                report(e)
            } finally {
                busy = false
            }
        }
    }

    // ---------- playback ----------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(getApplication<Application>()).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
                refreshService()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playingId?.let { prefs.edit().remove("pos_$it").apply() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                message = "Could not play this audio."
                isPlaying = false
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachLoudness(audioSessionId)
            }
        })
        attachLoudness(p.audioSessionId)
        player = p
        return p
    }

    /** Makes quiet voices louder when playing episodes. Does nothing if the phone refuses. */
    private fun attachLoudness(sessionId: Int) {
        runCatching { loudness?.release() }
        loudness = null
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        loudness = runCatching {
            LoudnessEnhancer(sessionId).apply {
                setTargetGain(PLAYBACK_BOOST_MB)
                enabled = true
            }
        }.getOrNull()
    }

    private fun startItem(id: String, title: String, item: MediaItem, startMs: Long = 0L) {
        val p = ensurePlayer()
        p.setMediaItem(item, startMs)
        p.setPlaybackSpeed(speed)
        p.prepare()
        p.play()
        playingId = id
        playingTitle = title
    }

    fun play(episode: Episode) {
        if (playingId == episode.id) {
            togglePlayback()
        } else {
            val saved = prefs.getLong("pos_${episode.id}", 0L)
            val total = (episode.durationSeconds ?: 0) * 1000L
            val resumeAt = if (saved > 5000 && (total == 0L || saved < total - 5000)) saved else 0L
            startItem(
                episode.id,
                episode.title,
                MediaItem.fromUri(Api.publicUrl(episode.filePath)),
                resumeAt,
            )
            if (resumeAt > 0) message = "Resuming from ${formatTime(resumeAt / 1000)}"
            track(episode, "play")
        }
    }

    /** Counts a play or download. The admin's own plays are not counted. */
    private fun track(episode: Episode, kind: String) {
        if (signedIn) return
        viewModelScope.launch {
            try {
                Api.trackEpisode(episode.id, kind)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun previewRecording() {
        val rec = pendingRecording ?: return
        startItem(PREVIEW_ID, "Preview of your recording", MediaItem.fromUri(Uri.fromFile(rec.file)))
    }

    private fun saveResumePoint() {
        val p = player ?: return
        val id = playingId ?: return
        if (id == PREVIEW_ID) return
        prefs.edit().putLong("pos_$id", p.currentPosition).apply()
    }

    fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) {
            saveResumePoint()
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    fun stopPlayback() {
        if (player?.playbackState != Player.STATE_ENDED) saveResumePoint()
        player?.stop()
        player?.clearMediaItems()
        playingId = null
        playingTitle = ""
        isPlaying = false
        refreshService()
    }

    fun cycleSpeed() {
        speed = when {
            speed < 1.1f -> 1.25f
            speed < 1.4f -> 1.5f
            speed < 1.9f -> 2f
            else -> 1f
        }
        player?.setPlaybackSpeed(speed)
    }

    fun positionMs(): Long {
        val p = player ?: return 0L
        val position = p.currentPosition
        val now = System.currentTimeMillis()
        if (p.isPlaying && now - lastPositionSave > 5000) {
            lastPositionSave = now
            saveResumePoint()
        }
        return position
    }

    fun durationMs(): Long {
        val d = player?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
    }

    // ---------- download ----------

    fun download(episode: Episode) {
        val context = getApplication<Application>()
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val safeTitle = episode.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "episode" }
        val fileName = "$safeTitle.ogg"

        val request = DownloadManager.Request(Uri.parse(Api.publicUrl(episode.filePath)))
            .setTitle(episode.title)
            .setMimeType("audio/ogg")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Unicast/$fileName",
                )
            } else {
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC, fileName)
            }
            manager.enqueue(request)
            track(episode, "download")
            message = "Download started. Check your notifications."
        } catch (e: Exception) {
            message = "Could not start the download."
        }
    }

    override fun onCleared() {
        runCatching { loudness?.release() }
        loudness = null
        player?.release()
        player = null
        recorder.cancel()
        room?.let {
            runCatching { it.disconnect() }
            runCatching { it.release() }
        }
        room = null
        runCatching {
            getApplication<Application>().stopService(
                Intent(getApplication<Application>(), KeepAliveService::class.java),
            )
        }
        super.onCleared()
    }
}
