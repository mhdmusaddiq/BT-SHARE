package com.btshare.bluetoothrelay.meshpoc

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled 5-second user-initiated mono PCM sample. No system playback capture. */
class RelayPcmAudio(private val report: (String) -> Unit) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-demo-audio").apply { isDaemon = true } }
    private val stopping = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var lastRecording: ByteArray? = null

    fun hasRecording(): Boolean = lastRecording?.size == PcmClipProtocol.CLIP_BYTES
    fun recordingCopy(): ByteArray? = lastRecording?.copyOf()

    @SuppressLint("MissingPermission") // Activity explicitly requests RECORD_AUDIO runtime permission.
    fun recordFiveSeconds(onRecorded: (Boolean) -> Unit) {
        stopping.set(false)
        worker.execute {
            val min = AudioRecord.getMinBufferSize(PcmClipProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { report("AudioRecord 16k mono unsupported"); onRecorded(false); return@execute }
            val bufferSize = maxOf(min, 4096)
            var source: AudioRecord? = null
            try {
                source = AudioRecord(MediaRecorder.AudioSource.MIC, PcmClipProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                if (source.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Recorder failed to initialize")
                recorder = source
                val target = ByteArray(PcmClipProtocol.CLIP_BYTES)
                source.startRecording()
                report("Recording 5 seconds at 16 kHz mono PCM...")
                var pos = 0
                while (pos < target.size && !stopping.get()) {
                    val n = source.read(target, pos, minOf(4096, target.size - pos))
                    if (n <= 0) throw IllegalStateException("AudioRecord read failed: $n")
                    pos += n
                }
                if (pos == target.size && !stopping.get()) {
                    lastRecording = target
                    report("Recording ready: ${target.size} bytes")
                    onRecorded(true)
                } else {
                    report("Recording stopped early: $pos/${target.size} bytes")
                    onRecorded(false)
                }
            } catch (e: Exception) {
                report("Record failure: ${e.message}")
                onRecorded(false)
            } finally {
                runCatching { source?.stop() }
                runCatching { source?.release() }
                recorder = null
            }
        }
    }

    fun play(clip: ByteArray, onFinished: () -> Unit = {}) {
        stopping.set(false)
        worker.execute {
            var audio: AudioTrack? = null
            try {
                if (clip.size != PcmClipProtocol.CLIP_BYTES) throw IllegalArgumentException("Invalid PCM clip length")
                val min = AudioTrack.getMinBufferSize(PcmClipProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) throw IllegalStateException("AudioTrack 16k mono unsupported")
                audio = AudioTrack(AudioManager.STREAM_MUSIC, PcmClipProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, 8192), AudioTrack.MODE_STREAM)
                if (audio.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AudioTrack initialization failed")
                player = audio
                audio.play()
                report("Speaker playback started (5s PCM)")
                var offset = 0
                while (offset < clip.size && !stopping.get()) {
                    val count = audio.write(clip, offset, minOf(4096, clip.size - offset))
                    if (count <= 0) throw IllegalStateException("AudioTrack write failed: $count")
                    offset += count
                }
                // Allow hardware output buffer to drain; stopping immediately can clip the tail.
                val targetFrames = clip.size / 2
                val deadline = SystemClock.elapsedRealtime() + 7000L
                while (!stopping.get() && audio.playbackHeadPosition < targetFrames &&
                    SystemClock.elapsedRealtime() < deadline) Thread.sleep(35)
                if (!stopping.get()) report("Speaker playback finished; bytes=$offset")
            } catch (e: Exception) {
                report("Playback error: ${e.message}")
            } finally {
                runCatching { audio?.pause() }
                runCatching { audio?.flush() }
                runCatching { audio?.release() }
                player = null
                onFinished()
            }
        }
    }

    fun stop() {
        stopping.set(true)
        runCatching { recorder?.stop() }
        runCatching { player?.pause() }
        report("Audio stop requested")
    }

    override fun close() {
        stop()
        worker.shutdownNow()
        runCatching { recorder?.release() }
        runCatching { player?.release() }
    }
}
