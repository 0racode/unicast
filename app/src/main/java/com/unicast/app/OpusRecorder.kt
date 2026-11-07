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
 * if the phone's encoder refuses it.
 */
class OpusRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

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

    fun elapsedSeconds(): Long =
        if (recorder == null) 0L else (SystemClock.elapsedRealtime() - startedAt) / 1000

    /** Returns null if the recording was too short or failed. */
    fun stop(): RecordingResult? {
        val r = recorder ?: return null
        recorder = null
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
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
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
    }
}
