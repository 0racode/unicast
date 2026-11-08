package com.unicast.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import io.livekit.android.room.track.AudioTrack
import livekit.org.webrtc.AudioTrackSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records a live show with everyone's voice: the host's microphone plus any guest speaker.
 *
 * The audio is read from the live connection (not from the microphone directly), mixed into one
 * mono 16 kHz stream and encoded as Opus at 6 kbps into an Ogg file, like normal recordings.
 * Needs Android 10 or newer. If anything goes wrong, [stop] returns null and the caller falls back
 * to the plain microphone recording.
 */
class LiveMixRecorder {

    private companion object {
        const val RATE = 16000
        const val FRAME = 320 // 20 ms
        const val MAX_QUEUE = RATE / 2 // never hold more than half a second of audio
    }

    /** One voice. Takes audio from the live connection, in any format, and holds it as 16 kHz mono. */
    private class Source {
        private val ring = ShortArray(MAX_QUEUE * 2)
        private var head = 0
        private var size = 0

        // resampler state
        private var acc = 0.0
        private var cnt = 0
        private var phase = 0.0

        @Volatile var received = 0L
            private set

        @Synchronized
        fun push(buffer: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int) {
            if (bits != 16 || rate <= 0 || channels <= 0 || frames <= 0) return
            val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            data.rewind()
            val ratio = rate.toDouble() / RATE
            for (f in 0 until frames) {
                var sum = 0
                var ch = 0
                while (ch < channels) {
                    val index = (f * channels + ch) * 2
                    if (index + 1 >= data.limit()) return
                    sum += data.getShort(index).toInt()
                    ch++
                }
                acc += sum.toDouble() / channels
                cnt++
                phase += 1.0
                if (phase >= ratio) {
                    phase -= ratio
                    put((acc / cnt).toInt().coerceIn(-32768, 32767).toShort())
                    acc = 0.0
                    cnt = 0
                }
            }
            received += frames
        }

        private fun put(sample: Short) {
            if (size == ring.size) { // full: drop the oldest sample
                head = (head + 1) % ring.size
                size--
            }
            ring[(head + size) % ring.size] = sample
            size++
        }

        /** Fills [out] with the next samples. Missing samples are silence. */
        @Synchronized
        fun read(out: IntArray, count: Int) {
            if (size > MAX_QUEUE) { // fallen behind: skip ahead, keep a little cushion
                val drop = size - RATE / 10
                head = (head + drop) % ring.size
                size -= drop
            }
            val n = minOf(count, size)
            for (i in 0 until n) {
                out[i] += ring[(head + i) % ring.size].toInt()
            }
            head = (head + n) % ring.size
            size -= n
        }

        @Synchronized
        fun reset() {
            clear()
            received = 0L
        }

        @Synchronized
        fun clear() {
            head = 0
            size = 0
            acc = 0.0
            cnt = 0
            phase = 0.0
        }
    }

    private val host = Source()
    private val guests = HashMap<AudioTrack, Source>()
    private val sinks = HashMap<AudioTrack, AudioTrackSink>()
    private var hostTrack: AudioTrack? = null

    @Volatile private var hostMuted = false
    @Volatile private var running = false
    private var worker: Thread? = null
    private var file: File? = null

    @Volatile private var frames = 0L
    @Volatile private var failed = false

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 29
    val isRunning: Boolean get() = running

    fun hostHeard(): Boolean = host.received > 0

    fun setHostMuted(muted: Boolean) {
        hostMuted = muted
        if (muted) host.clear()
    }

    /** Starts the recording. Returns false if this phone cannot do it. */
    fun start(target: File): Boolean {
        if (!supported || running) return false
        file = target
        frames = 0L
        failed = false
        hostMuted = false
        host.reset()
        running = true
        worker = Thread({ work(target) }, "live-mix").also { it.start() }
        return true
    }

    /** Connects the host's own microphone to the recording. Safe to call repeatedly. */
    fun attachHost(track: AudioTrack) {
        if (!running || hostTrack === track) return
        hostTrack = track
        val sink = AudioTrackSink { data, bits, rate, channels, count, _ ->
            if (!hostMuted) host.push(data, bits, rate, channels, count)
        }
        track.addSink(sink)
        synchronized(sinks) { sinks[track] = sink }
    }

    /** Connects a guest speaker's voice to the recording. Safe to call repeatedly. */
    fun attachGuest(track: AudioTrack) {
        if (!running) return
        synchronized(sinks) {
            if (sinks.containsKey(track)) return
            val source = Source()
            val sink = AudioTrackSink { data, bits, rate, channels, count, _ ->
                source.push(data, bits, rate, channels, count)
            }
            sinks[track] = sink
            synchronized(guests) { guests[track] = source }
            track.addSink(sink)
        }
    }

    private fun detachAll() {
        val copy = synchronized(sinks) { sinks.toMap().also { sinks.clear() } }
        for ((track, sink) in copy) {
            runCatching { track.removeSink(sink) }
        }
        synchronized(guests) { guests.clear() }
        hostTrack = null
    }

    private fun work(target: File) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            var bitrate = 6000
            for (candidate in listOf(6000, 8000, 12000)) {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1)
                format.setInteger(MediaFormat.KEY_BIT_RATE, candidate)
                try {
                    val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    c.start()
                    codec = c
                    bitrate = candidate
                    break
                } catch (e: Exception) {
                    runCatching { codec?.release() }
                    codec = null
                }
            }
            val enc = codec ?: throw IllegalStateException("No Opus encoder")
            val mux = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            muxer = mux
            var track = -1
            val info = MediaCodec.BufferInfo()
            val mix = IntArray(FRAME)

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val index = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = mux.addTrack(enc.outputFormat)
                            mux.start()
                        }
                        index >= 0 -> {
                            val out = enc.getOutputBuffer(index)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (out != null && info.size > 0 && !config && track >= 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                mux.writeSampleData(track, out, info)
                            }
                            enc.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            var next = SystemClock.elapsedRealtimeNanos()
            while (running) {
                next += 20_000_000L
                java.util.Arrays.fill(mix, 0)
                host.read(mix, FRAME)
                val list = synchronized(guests) { guests.values.toList() }
                for (g in list) g.read(mix, FRAME)

                val inIndex = enc.dequeueInputBuffer(20_000)
                if (inIndex >= 0) {
                    val input = enc.getInputBuffer(inIndex)!!
                    input.clear()
                    input.order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until FRAME) input.putShort(mix[i].coerceIn(-32768, 32767).toShort())
                    enc.queueInputBuffer(inIndex, 0, FRAME * 2, frames * 20_000L, 0)
                    frames++
                }
                drain(false)

                val wait = (next - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait) else if (wait < -200) next = SystemClock.elapsedRealtimeNanos()
            }

            // finish
            val inIndex = enc.dequeueInputBuffer(100_000)
            if (inIndex >= 0) {
                enc.queueInputBuffer(inIndex, 0, 0, frames * 20_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
            if (track < 0) throw IllegalStateException("Nothing was encoded")
            mux.stop()
        } catch (e: Throwable) {
            failed = true
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
    }

    /** Stops and returns the finished recording, or null if it failed or the host was never heard. */
    fun stop(): RecordingResult? {
        if (!running && worker == null) return null
        running = false
        detachAll()
        worker?.join(3000)
        worker = null
        val saved = file
        file = null
        val seconds = (frames / 50).toInt()
        val ok = !failed && saved != null && saved.exists() && saved.length() > 0 && host.received > 0
        host.clear()
        return if (ok) {
            RecordingResult(saved!!, seconds)
        } else {
            saved?.delete()
            null
        }
    }

    fun cancel() {
        if (worker == null) return
        running = false
        detachAll()
        worker?.join(3000)
        worker = null
        file?.delete()
        file = null
        host.clear()
    }
}
