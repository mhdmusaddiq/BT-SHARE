package com.btshare.bluetoothrelay.meshpoc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/** Five-second mono PCM clip: explicit baseline, NOT live streaming or Opus. */
object PcmClipProtocol {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val DURATION_SECONDS = 5
    const val CLIP_BYTES = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8 * DURATION_SECONDS // 160000
    const val CHUNK_BYTES = 4000
    const val CHUNK_COUNT = CLIP_BYTES / CHUNK_BYTES // 40
    private const val CLIP_MAGIC = 0x50434D31 // PCM1
    data class Start(val totalBytes: Int, val chunkBytes: Int, val chunkCount: Int)

    fun startPayload(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        .putInt(CLIP_MAGIC).putInt(SAMPLE_RATE).putInt(CHANNELS).putInt(BITS_PER_SAMPLE)
        .putInt(CLIP_BYTES).putInt(CHUNK_BYTES).array()

    fun decodeStart(bytes: ByteArray): Start {
        if (bytes.size != 24) throw RelayWireException("Invalid audio start metadata length")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (b.int != CLIP_MAGIC || b.int != SAMPLE_RATE || b.int != CHANNELS || b.int != BITS_PER_SAMPLE) {
            throw RelayWireException("Unsupported audio format")
        }
        val total = b.int
        val chunk = b.int
        if (total != CLIP_BYTES || chunk != CHUNK_BYTES) throw RelayWireException("Unexpected audio extent")
        return Start(total, chunk, CHUNK_COUNT)
    }

    fun checksum(audio: ByteArray): Int = CRC32().apply { update(audio) }.value.toInt()

    fun endPayload(audio: ByteArray): ByteArray = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        .putInt(audio.size).putInt(CHUNK_COUNT).putInt(checksum(audio)).array()

    fun validateEnd(bytes: ByteArray, audio: ByteArray, chunks: Int) {
        if (bytes.size != 12) throw RelayWireException("Invalid audio end metadata")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (b.int != CLIP_BYTES || b.int != CHUNK_COUNT || b.int != checksum(audio) || chunks != CHUNK_COUNT) {
            throw RelayWireException("Audio truncated, duplicated, or checksum mismatch")
        }
    }

    fun frames(audio: ByteArray, session: UUID, origin: UUID, stream: Long): List<RelayFrame> {
        require(audio.size == CLIP_BYTES) { "Expected exactly $CLIP_BYTES PCM bytes" }
        val result = ArrayList<RelayFrame>(CHUNK_COUNT + 2)
        var seq = 1L
        result += RelayFrame(RelayFrameType.AUDIO_START, session, origin, stream, seq++, 4, 0, startPayload())
        for (position in audio.indices step CHUNK_BYTES) {
            result += RelayFrame(RelayFrameType.AUDIO_CHUNK, session, origin, stream, seq++, 4, 0,
                audio.copyOfRange(position, position + CHUNK_BYTES))
        }
        result += RelayFrame(RelayFrameType.AUDIO_END, session, origin, stream, seq, 4, 0, endPayload(audio))
        return result
    }
}

/** One clip per receiver at a time, 160 KB upper bound; rejects gaps, reorder, oversized chunks. */
class PcmClipAssembler {
    private var activeKey: String? = null
    private var nextSequence: Long = 0
    private var chunkCount = 0
    private var sink: ByteArrayOutputStream? = null

    @Synchronized
    fun accept(frame: RelayFrame): ByteArray? {
        val key = "${frame.sessionId}/${frame.originId}/${frame.streamId}"
        when (frame.type) {
            RelayFrameType.AUDIO_START -> {
                PcmClipProtocol.decodeStart(frame.payload)
                if (frame.sequence != 1L) throw RelayWireException("Start sequence must be 1")
                activeKey = key
                nextSequence = 2L
                chunkCount = 0
                sink = ByteArrayOutputStream(PcmClipProtocol.CLIP_BYTES)
            }
            RelayFrameType.AUDIO_CHUNK -> {
                if (activeKey != key || sink == null || frame.sequence != nextSequence) {
                    clear(); throw RelayWireException("Missing or out-of-order audio chunk")
                }
                if (frame.payload.size != PcmClipProtocol.CHUNK_BYTES || chunkCount >= PcmClipProtocol.CHUNK_COUNT) {
                    clear(); throw RelayWireException("Bad chunk size or excess chunks")
                }
                sink!!.write(frame.payload)
                chunkCount++
                nextSequence++
            }
            RelayFrameType.AUDIO_END -> {
                if (activeKey != key || sink == null || frame.sequence != nextSequence) {
                    clear(); throw RelayWireException("Bad audio end sequence")
                }
                val clip = sink!!.toByteArray()
                try { PcmClipProtocol.validateEnd(frame.payload, clip, chunkCount) }
                finally { clear() }
                return clip
            }
            else -> Unit
        }
        return null
    }

    @Synchronized fun clear() {
        activeKey = null
        nextSequence = 0
        chunkCount = 0
        sink = null
    }
}
