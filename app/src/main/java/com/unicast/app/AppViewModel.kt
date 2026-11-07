package com.unicast.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val PREVIEW_ID = "preview"

enum class LiveMode { None, Connecting, Listening, Broadcasting }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("unicast", Context.MODE_PRIVATE)
    private val recorder = OpusRecorder(app)
    private var session: Session? = null
    private var expiresAtMs = 0L
    private var player: ExoPlayer? = null

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
    var pendingRecording by mutableStateOf<RecordingResult?>(null)
        private set
    var playingId by mutableStateOf<String?>(null)
        private set
    var playingTitle by mutableStateOf("")
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var liveNow by mutableStateOf(false)
        private set
    var liveMode by mutableStateOf(LiveMode.None)
        private set

    private var room: Room? = null

    val recordingSupported: Boolean get() = recorder.supported

    init {
        restoreSession()
    }

    // ---------- errors ----------

    private fun report(e: Exception) {
        if (e is CancellationException) throw e
        message = (e as? ApiException)?.message ?: "Something went wrong. Please try again."
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
    }

    @Suppress("DEPRECATION")
    private fun useSpeaker() {
        runCatching {
            val audio = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.isSpeakerphoneOn = true
        }
    }

    /** Joins the live room as a listener. Listener tokens cannot publish. */
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
                useSpeaker()
                liveMode = LiveMode.Listening
            } catch (e: Exception) {
                leaveRoom()
                if (e is ApiException) report(e) else message = "Could not join the live show."
            }
        }
    }

    fun stopListening() {
        if (liveMode == LiveMode.Listening || liveMode == LiveMode.Connecting) leaveRoom()
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
                Api.setLive(userToken, true)
                liveNow = true
                liveMode = LiveMode.Broadcasting
                message = "You are live."
            } catch (e: Exception) {
                leaveRoom()
                if (e is ApiException) report(e) else message = "Could not start the live show."
            }
        }
    }

    /** Stops broadcasting (or just clears a stale live flag) and tells listeners you are offline. */
    fun endLive() {
        viewModelScope.launch {
            busy = true
            leaveRoom()
            try {
                Api.setLive(ensureToken(), false)
                liveNow = false
            } catch (e: Exception) {
                report(e)
            } finally {
                busy = false
            }
        }
    }

    // ---------- recording and upload ----------

    fun startRecording() {
        stopPlayback()
        try {
            recorder.start()
            recording = true
        } catch (e: Exception) {
            message = "Could not start recording on this phone."
        }
    }

    fun recordingSeconds(): Long = recorder.elapsedSeconds()

    fun stopRecording() {
        recording = false
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
        }
        if (playingId == PREVIEW_ID) stopPlayback()
        pendingRecording?.file?.delete()
        pendingRecording = null
    }

    fun uploadRecording(title: String) {
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
                Api.insertEpisode(token, clean, path, rec.seconds)
                if (playingId == PREVIEW_ID) stopPlayback()
                rec.file.delete()
                pendingRecording = null
                message = "Uploaded. Your episode is live."
                loadEpisodes()
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
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onPlayerError(error: PlaybackException) {
                message = "Could not play this audio."
                isPlaying = false
            }
        })
        player = p
        return p
    }

    private fun startItem(id: String, title: String, item: MediaItem) {
        val p = ensurePlayer()
        p.setMediaItem(item)
        p.prepare()
        p.play()
        playingId = id
        playingTitle = title
    }

    fun play(episode: Episode) {
        if (playingId == episode.id) {
            togglePlayback()
        } else {
            startItem(episode.id, episode.title, MediaItem.fromUri(Api.publicUrl(episode.filePath)))
        }
    }

    fun previewRecording() {
        val rec = pendingRecording ?: return
        startItem(PREVIEW_ID, "Preview of your recording", MediaItem.fromUri(Uri.fromFile(rec.file)))
    }

    fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    fun stopPlayback() {
        player?.stop()
        player?.clearMediaItems()
        playingId = null
        playingTitle = ""
        isPlaying = false
    }

    fun positionMs(): Long = player?.currentPosition ?: 0L

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
            message = "Download started. Check your notifications."
        } catch (e: Exception) {
            message = "Could not start the download."
        }
    }

    override fun onCleared() {
        player?.release()
        player = null
        recorder.cancel()
        room?.let {
            runCatching { it.disconnect() }
            runCatching { it.release() }
        }
        room = null
        super.onCleared()
    }
}
