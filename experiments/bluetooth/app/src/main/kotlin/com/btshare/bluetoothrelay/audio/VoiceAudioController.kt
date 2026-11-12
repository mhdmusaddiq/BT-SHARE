package com.btshare.bluetoothrelay.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import com.btshare.bluetoothrelay.protocol.AudioWireFormat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VoiceAudioController(
    private val callbackExecutor: Executor,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onRecordingStarted()
        fun onRecordingReady(pcmBytes: ByteArray, stoppedEarly: Boolean)
        fun onLocalPlaybackStarted(pcmBytes: Int)
        fun onLocalPlaybackFinished(pcmBytes: Int, stoppedEarly: Boolean, bufferUnderruns: Int)
        fun onAudioError(message: String, error: Throwable?)
    }

    private val recordingExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bt-relay-microphone").apply { isDaemon = true }
    }
    private val localPlaybackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bt-relay-local-playback").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val stopRecordingRequested = AtomicBoolean(false)
    private val localPlaybackGeneration = AtomicLong(0L)

    @Volatile
    private var activeRecorder: AudioRecord? = null

    @Volatile
    private var activeLocalTrack: AudioTrack? = null

    @SuppressLint("MissingPermission")
    fun recordFiveSeconds() {
        check(!closed.get()) { "VoiceAudioController is closed" }
        if (!recording.compareAndSet(false, true)) {
            emitError("A voice recording is already in progress", null)
            return
        }
        stopLocalPlayback()
        stopRecordingRequested.set(false)
        dispatch { listener.onRecordingStarted() }
        recordingExecutor.execute(::capturePcm)
    }

    fun playLocal(pcmBytes: ByteArray) {
        check(!closed.get()) { "VoiceAudioController is closed" }
        if (recording.get()) {
            emitError("Stop recording before local playback", null)
            return
        }
        stopLocalPlayback()
        val playbackId = localPlaybackGeneration.incrementAndGet()
        val playbackCopy = pcmBytes.copyOf()
        localPlaybackExecutor.execute { playPcm(playbackCopy, playbackId) }
    }

    fun stopAll() {
        stopRecording()
        stopLocalPlayback()
    }

    private fun stopRecording() {
        stopRecordingRequested.set(true)
        runCatching { activeRecorder?.stop() }
    }

    private fun stopLocalPlayback() {
        localPlaybackGeneration.incrementAndGet()
        runCatching { activeLocalTrack?.pause() }
        runCatching { activeLocalTrack?.flush() }
    }

    @SuppressLint("MissingPermission")
    private fun capturePcm() {
        var recorder: AudioRecord? = null
        val output = ByteArrayOutputStream(AudioWireFormat.TARGET_PCM_BYTES)
        try {
            val minimumBufferBytes = AudioRecord.getMinBufferSize(
                AudioWireFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBufferBytes <= 0) {
                throw IllegalStateException("Unsupported AudioRecord format: $minimumBufferBytes")
            }
            val bufferBytes = maxOf(minimumBufferBytes, AudioWireFormat.DEFAULT_CHUNK_BYTES)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioWireFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord did not initialize")
            }
            activeRecorder = recorder
            val sampleBuffer = ShortArray(bufferBytes / AudioWireFormat.BYTES_PER_SAMPLE)
            var samplesCaptured = 0
            val targetSamples = AudioWireFormat.TARGET_PCM_BYTES / AudioWireFormat.BYTES_PER_SAMPLE
            recorder.startRecording()
            while (samplesCaptured < targetSamples && !stopRecordingRequested.get()) {
                val requestedSamples = minOf(sampleBuffer.size, targetSamples - samplesCaptured)
                val samplesRead = recorder.read(
                    sampleBuffer,
                    0,
                    requestedSamples,
                    AudioRecord.READ_BLOCKING,
                )
                if (samplesRead < 0) {
                    throw IllegalStateException("AudioRecord.read failed with code $samplesRead")
                }
                if (samplesRead == 0) continue
                writeLittleEndianPcm(output, sampleBuffer, samplesRead)
                samplesCaptured += samplesRead
            }
            val recorded = output.toByteArray()
            if (recorded.isEmpty()) {
                throw IllegalStateException("No microphone samples were captured")
            }
            val stoppedEarly = recorded.size < AudioWireFormat.TARGET_PCM_BYTES
            dispatch { listener.onRecordingReady(recorded, stoppedEarly) }
        } catch (error: Throwable) {
            if (!stopRecordingRequested.get() || output.size() == 0) {
                emitError("Voice recording failed", error)
            } else {
                val recorded = output.toByteArray()
                dispatch { listener.onRecordingReady(recorded, true) }
            }
        } finally {
            activeRecorder = null
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recording.set(false)
        }
    }

    private fun writeLittleEndianPcm(
        output: ByteArrayOutputStream,
        samples: ShortArray,
        sampleCount: Int,
    ) {
        val bytes = ByteArray(sampleCount * AudioWireFormat.BYTES_PER_SAMPLE)
        for (index in 0 until sampleCount) {
            val sample = samples[index].toInt()
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }
        output.write(bytes)
    }

    private fun playPcm(pcmBytes: ByteArray, playbackId: Long) {
        var track: AudioTrack? = null
        try {
            val minimumBufferBytes = AudioTrack.getMinBufferSize(
                AudioWireFormat.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBufferBytes <= 0) {
                throw IllegalStateException("Unsupported AudioTrack format: $minimumBufferBytes")
            }
            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(AudioWireFormat.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                maxOf(minimumBufferBytes, AudioWireFormat.DEFAULT_CHUNK_BYTES * 2),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack did not initialize")
            }
            activeLocalTrack = track
            val startingUnderruns = readUnderrunCount(track)
            dispatch { listener.onLocalPlaybackStarted(pcmBytes.size) }
            track.play()
            var offset = 0
            while (offset < pcmBytes.size && localPlaybackGeneration.get() == playbackId) {
                val length = minOf(AudioWireFormat.DEFAULT_CHUNK_BYTES, pcmBytes.size - offset)
                val written = track.write(pcmBytes, offset, length, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    throw IllegalStateException("AudioTrack.write failed with code $written")
                }
                offset += written
            }
            val stoppedEarly = offset < pcmBytes.size
            if (!stoppedEarly) {
                drainPlayback(
                    track,
                    pcmBytes.size / AudioWireFormat.BYTES_PER_SAMPLE,
                    playbackId,
                )
            }
            val underruns = (readUnderrunCount(track) - startingUnderruns).coerceAtLeast(0)
            dispatch {
                listener.onLocalPlaybackFinished(pcmBytes.size, stoppedEarly, underruns)
            }
        } catch (error: Throwable) {
            if (localPlaybackGeneration.get() == playbackId) {
                emitError("Local playback failed", error)
            }
        } finally {
            activeLocalTrack = null
            releaseTrack(track)
        }
    }

    private fun drainPlayback(track: AudioTrack, targetFrames: Int, playbackId: Long) {
        val deadlineNanos = System.nanoTime() + 2_000_000_000L
        while (localPlaybackGeneration.get() == playbackId && System.nanoTime() < deadlineNanos) {
            val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            if (playedFrames >= targetFrames) return
            Thread.sleep(20)
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

    private fun emitError(message: String, error: Throwable?) {
        dispatch { listener.onAudioError(message, error) }
    }

    private fun dispatch(block: () -> Unit) {
        callbackExecutor.execute(block)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopAll()
        recordingExecutor.shutdownNow()
        localPlaybackExecutor.shutdownNow()
    }
}
