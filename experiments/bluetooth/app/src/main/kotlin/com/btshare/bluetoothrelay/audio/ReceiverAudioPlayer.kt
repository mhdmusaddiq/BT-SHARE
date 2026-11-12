package com.btshare.bluetoothrelay.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.btshare.bluetoothrelay.protocol.AudioReceiveSummary
import com.btshare.bluetoothrelay.protocol.AudioStreamMetadata
import com.btshare.bluetoothrelay.protocol.AudioStreamResult
import com.btshare.bluetoothrelay.protocol.AudioWireFormat
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Receives commands without blocking the Bluetooth reader and plays them on one worker. */
class ReceiverAudioPlayer(
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onPlaybackStatus(message: String)
        fun onPlaybackFinished(summary: AudioReceiveSummary, bufferUnderruns: Int)
        fun onPlaybackError(message: String, error: Throwable?)
    }

    private sealed interface Command {
        data class Start(val streamId: Long, val metadata: AudioStreamMetadata) : Command
        data class Chunk(val streamId: Long, val pcmBytes: ByteArray) : Command
        data class Finish(val summary: AudioReceiveSummary) : Command
        data class Stop(val detail: String) : Command
        data object Close : Command
    }

    private val queue = ArrayBlockingQueue<Command>(MAX_QUEUED_COMMANDS)
    private val closed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val worker = Thread(::runWorker, "bt-relay-audio-playback").apply {
        isDaemon = true
        start()
    }

    fun start(streamId: Long, metadata: AudioStreamMetadata): Boolean {
        if (closed.get()) return false
        stopRequested.set(false)
        queue.clear()
        return queue.offer(Command.Start(streamId, metadata))
    }

    fun enqueue(streamId: Long, pcmBytes: ByteArray): Boolean {
        if (closed.get()) return false
        if (stopRequested.get()) return true
        if (queue.offer(Command.Chunk(streamId, pcmBytes))) return true
        abortForOverflow()
        return false
    }

    fun finish(summary: AudioReceiveSummary): Boolean {
        if (closed.get()) return false
        if (queue.offer(Command.Finish(summary))) return true
        abortForOverflow()
        return false
    }

    fun stop(detail: String = "Playback stopped") {
        if (closed.get()) return
        stopRequested.set(true)
        queue.clear()
        queue.offer(Command.Stop(detail))
    }

    private fun abortForOverflow() {
        stopRequested.set(true)
        queue.clear()
        queue.offer(Command.Stop("Receiver audio queue overflow"))
    }

    private fun runWorker() {
        var track: AudioTrack? = null
        var activeStreamId: Long? = null
        var framesWritten = 0L
        var startingUnderruns = 0
        try {
            while (!closed.get()) {
                try {
                    when (val command = queue.take()) {
                        is Command.Start -> {
                            releaseTrack(track)
                            track = createTrack(command.metadata)
                            activeStreamId = command.streamId
                            framesWritten = 0
                            startingUnderruns = readUnderrunCount(track)
                            track.play()
                            listener.onPlaybackStatus(
                                "Playing received stream ${command.streamId}: " +
                                    "${command.metadata.sampleRateHz} Hz mono PCM16LE",
                            )
                        }

                        is Command.Chunk -> {
                            val activeTrack = track
                            if (command.streamId != activeStreamId || activeTrack == null) continue
                            var offset = 0
                            while (offset < command.pcmBytes.size && !stopRequested.get()) {
                                val written = activeTrack.write(
                                    command.pcmBytes,
                                    offset,
                                    command.pcmBytes.size - offset,
                                    AudioTrack.WRITE_BLOCKING,
                                )
                                if (written <= 0) {
                                    throw IllegalStateException(
                                        "AudioTrack.write failed with code $written",
                                    )
                                }
                                offset += written
                                framesWritten += written / AudioWireFormat.BYTES_PER_SAMPLE
                            }
                        }

                        is Command.Finish -> {
                            val activeTrack = track
                            if (command.summary.streamId == activeStreamId && activeTrack != null) {
                                if (command.summary.result == AudioStreamResult.COMPLETED &&
                                    !stopRequested.get()
                                ) {
                                    drainPlayback(activeTrack, framesWritten)
                                }
                                val underruns =
                                    (readUnderrunCount(activeTrack) - startingUnderruns)
                                        .coerceAtLeast(0)
                                releaseTrack(activeTrack)
                                listener.onPlaybackFinished(command.summary, underruns)
                            }
                            track = null
                            activeStreamId = null
                        }

                        is Command.Stop -> {
                            releaseTrack(track)
                            track = null
                            activeStreamId = null
                            listener.onPlaybackStatus(command.detail)
                        }

                        Command.Close -> return
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (error: Throwable) {
                    releaseTrack(track)
                    track = null
                    activeStreamId = null
                    stopRequested.set(true)
                    listener.onPlaybackError("Receiver playback failed", error)
                }
            }
        } finally {
            releaseTrack(track)
        }
    }

    private fun createTrack(metadata: AudioStreamMetadata): AudioTrack {
        val minimumBufferBytes = AudioTrack.getMinBufferSize(
            metadata.sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBufferBytes <= 0) {
            throw IllegalStateException("Unsupported AudioTrack format: $minimumBufferBytes")
        }
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(metadata.sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minimumBufferBytes, metadata.chunkSizeBytes * 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack did not initialize")
        }
        return track
    }

    private fun drainPlayback(track: AudioTrack, targetFrames: Long) {
        val deadlineNanos = System.nanoTime() + DRAIN_TIMEOUT_MILLIS * 1_000_000L
        while (!stopRequested.get() && System.nanoTime() < deadlineNanos) {
            val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            if (playedFrames >= targetFrames) return
            Thread.sleep(DRAIN_POLL_MILLIS)
        }
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun readUnderrunCount(track: AudioTrack): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.underrunCount else 0

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopRequested.set(true)
        queue.clear()
        queue.offer(Command.Close)
        worker.interrupt()
    }

    companion object {
        private const val MAX_QUEUED_COMMANDS = 48
        private const val DRAIN_TIMEOUT_MILLIS = 2_000L
        private const val DRAIN_POLL_MILLIS = 20L
    }
}
