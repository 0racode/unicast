package com.unicast.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException

data class RecordingResult(val file: File, val seconds: Int)

/**
 * Records mono Opus audio (Ogg container) straight from the microphone.
 * Needs Android 10 or newer. Tries 6 kbps first and falls back to higher rates
 * if the phone's encoder refuses it. Can be paused and resumed.
 */
class OpusRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var accumulatedMs = 0L

    var paused = false
        private set

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 29

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    fun start() {
        if (!supported) throw IOException("Recording needs Android 10 or newer.")
        val target = File(context.cacheDir, "rec_${System.currentTimeMillis()}.ogg")
        var lastError: Exception? = null

        for (bitrate in listOf(6000, 8000, 12000)) {
            val r = newRecorder()
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(16000)
                r.setAudioEncodingBitRate(bitrate)
                r.setOutputFile(target.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                file = target
                accumulatedMs = 0L
                paused = false
                startedAt = SystemClock.elapsedRealtime()
                return
            } catch (e: Exception) {
                lastError = e
                runCatching { r.reset() }
                runCatching { r.release() }
            }
        }
        throw IOException("Could not start recording", lastError)
    }

    fun elapsedSeconds(): Long {
        if (recorder == null) return 0L
        val running = if (paused) 0L else SystemClock.elapsedRealtime() - startedAt
        return (accumulatedMs + running) / 1000
    }

    /** Returns true if recording is now paused. */
    fun pause(): Boolean {
        val r = recorder ?: return false
        if (paused) return true
        return try {
            r.pause()
            accumulatedMs += SystemClock.elapsedRealtime() - startedAt
            paused = true
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Returns true if recording is running again. */
    fun resume(): Boolean {
        val r = recorder ?: return false
        if (!paused) return true
        return try {
            r.resume()
            startedAt = SystemClock.elapsedRealtime()
            paused = false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Returns null if the recording was too short or failed. */
    fun stop(): RecordingResult? {
        val r = recorder ?: return null
        val seconds = elapsedSeconds().toInt()
        recorder = null
        paused = false
        val saved = file
        return try {
            r.stop()
            r.release()
            if (saved != null && saved.exists() && saved.length() > 0) {
                RecordingResult(saved, seconds)
            } else {
                null
            }
        } catch (e: Exception) {
            runCatching { r.release() }
            saved?.delete()
            null
        }
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        paused = false
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
    }
}
