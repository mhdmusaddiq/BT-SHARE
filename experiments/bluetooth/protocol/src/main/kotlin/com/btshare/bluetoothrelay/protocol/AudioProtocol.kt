package com.btshare.bluetoothrelay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AudioEncoding(val wireValue: Int) {
    PCM_SIGNED_16_BIT_LITTLE_ENDIAN(1),
    ;

    companion object {
        fun fromWireValue(value: Int): AudioEncoding =
            entries.firstOrNull { it.wireValue == value }
                ?: throw ProtocolException("Unknown audio encoding: $value")
    }
}

data class AudioStreamMetadata(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: AudioEncoding,
    val declaredPcmBytes: Long,
    val chunkSizeBytes: Int,
)

data class AudioChunkPayload(
    val streamId: Long,
    val chunkIndex: Int,
    val pcmBytes: ByteArray,
)

enum class AudioEndReason(val wireValue: Int) {
    COMPLETED(1),
    STOPPED(2),
    ;

    companion object {
        fun fromWireValue(value: Int): AudioEndReason =
            entries.firstOrNull { it.wireValue == value }
                ?: throw ProtocolException("Unknown audio end reason: $value")
    }
}

data class AudioEndPayload(
    val streamId: Long,
    val chunkCount: Int,
    val totalPcmBytes: Long,
    val reason: AudioEndReason,
)

enum class AudioStreamResult {
    COMPLETED,
    STOPPED,
    TRUNCATED,
}

data class AudioSendSummary(
    val streamId: Long,
    val chunkCount: Int,
    val pcmBytes: Long,
    val result: AudioStreamResult,
) {
    val durationMillis: Long
        get() = AudioWireFormat.durationMillis(pcmBytes)
}

data class AudioReceiveSummary(
    val streamId: Long,
    val chunkCount: Int,
    val pcmBytes: Long,
    val missingChunks: Int,
    val duplicateChunks: Int,
    val outOfOrderChunks: Int,
    val result: AudioStreamResult,
    val detail: String,
) {
    val durationMillis: Long
        get() = AudioWireFormat.durationMillis(pcmBytes)
}

/**
 * Audio wire metadata for the first proof of concept.
 *
 * AUDIO_START payload (19 bytes, network byte order): metadata version u8,
 * sample rate i32, channels u8, encoding u8, declared PCM byte count i64,
 * and sender chunk size i32. Encoding 1 is signed 16-bit little-endian PCM.
 *
 * AUDIO_CHUNK payload: stream id i64, zero-based chunk index i32, then an
 * even, non-empty PCM byte array. AUDIO_END payload (21 bytes): stream id i64,
 * chunk count i32, total PCM byte count i64, and end reason u8.
 */
object AudioWireFormat {
    const val METADATA_VERSION = 1
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE
    const val TARGET_DURATION_MILLIS = 5_000L
    const val TARGET_PCM_BYTES = BYTES_PER_SECOND * 5
    const val DEFAULT_CHUNK_BYTES = 4 * 1024
    const val MAXIMUM_CHUNK_BYTES = 8 * 1024
    const val MAXIMUM_PCM_BYTES = BYTES_PER_SECOND * 10L
    const val MAXIMUM_CHUNK_COUNT = (MAXIMUM_PCM_BYTES / BYTES_PER_SAMPLE).toInt()

    const val START_PAYLOAD_BYTES = 1 + Int.SIZE_BYTES + 1 + 1 + Long.SIZE_BYTES + Int.SIZE_BYTES
    const val CHUNK_HEADER_BYTES = Long.SIZE_BYTES + Int.SIZE_BYTES
    const val END_PAYLOAD_BYTES = Long.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES + 1

    fun metadataFor(
        pcmBytes: Int,
        chunkSizeBytes: Int = DEFAULT_CHUNK_BYTES,
    ): AudioStreamMetadata = AudioStreamMetadata(
        sampleRateHz = SAMPLE_RATE_HZ,
        channelCount = CHANNEL_COUNT,
        encoding = AudioEncoding.PCM_SIGNED_16_BIT_LITTLE_ENDIAN,
        declaredPcmBytes = pcmBytes.toLong(),
        chunkSizeBytes = chunkSizeBytes,
    ).also(::validateMetadata)

    fun encodeStart(metadata: AudioStreamMetadata): ByteArray {
        validateMetadata(metadata)
        return ByteBuffer.allocate(START_PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(METADATA_VERSION.toByte())
            .putInt(metadata.sampleRateHz)
            .put(metadata.channelCount.toByte())
            .put(metadata.encoding.wireValue.toByte())
            .putLong(metadata.declaredPcmBytes)
            .putInt(metadata.chunkSizeBytes)
            .array()
    }

    fun decodeStart(payload: ByteArray): AudioStreamMetadata {
        if (payload.size != START_PAYLOAD_BYTES) {
            throw ProtocolException(
                "AUDIO_START payload is ${payload.size} bytes; expected $START_PAYLOAD_BYTES",
            )
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val metadataVersion = buffer.get().toInt() and 0xFF
        if (metadataVersion != METADATA_VERSION) {
            throw ProtocolException("Unsupported audio metadata version: $metadataVersion")
        }
        return AudioStreamMetadata(
            sampleRateHz = buffer.int,
            channelCount = buffer.get().toInt() and 0xFF,
            encoding = AudioEncoding.fromWireValue(buffer.get().toInt() and 0xFF),
            declaredPcmBytes = buffer.long,
            chunkSizeBytes = buffer.int,
        ).also(::validateMetadata)
    }

    fun encodeChunk(streamId: Long, chunkIndex: Int, pcmBytes: ByteArray): ByteArray {
        validateChunk(streamId, chunkIndex, pcmBytes)
        return ByteBuffer.allocate(CHUNK_HEADER_BYTES + pcmBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(streamId)
            .putInt(chunkIndex)
            .put(pcmBytes)
            .array()
    }

    fun decodeChunk(payload: ByteArray): AudioChunkPayload {
        if (payload.size <= CHUNK_HEADER_BYTES) {
            throw ProtocolException("AUDIO_CHUNK must contain its header and PCM bytes")
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val streamId = buffer.long
        val chunkIndex = buffer.int
        val pcmBytes = ByteArray(buffer.remaining())
        buffer.get(pcmBytes)
        validateChunk(streamId, chunkIndex, pcmBytes)
        return AudioChunkPayload(streamId, chunkIndex, pcmBytes)
    }

    fun encodeEnd(end: AudioEndPayload): ByteArray {
        validateEnd(end)
        return ByteBuffer.allocate(END_PAYLOAD_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(end.streamId)
            .putInt(end.chunkCount)
            .putLong(end.totalPcmBytes)
            .put(end.reason.wireValue.toByte())
            .array()
    }

    fun decodeEnd(payload: ByteArray): AudioEndPayload {
        if (payload.size != END_PAYLOAD_BYTES) {
            throw ProtocolException(
                "AUDIO_END payload is ${payload.size} bytes; expected $END_PAYLOAD_BYTES",
            )
        }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return AudioEndPayload(
            streamId = buffer.long,
            chunkCount = buffer.int,
            totalPcmBytes = buffer.long,
            reason = AudioEndReason.fromWireValue(buffer.get().toInt() and 0xFF),
        ).also(::validateEnd)
    }

    fun validatePacket(packet: RelayPacket) {
        when (packet.type) {
            PacketType.DATA -> Unit
            PacketType.ACKNOWLEDGEMENT -> if (packet.payload.isNotEmpty()) {
                throw ProtocolException("Acknowledgement packets must not contain a payload")
            }
            PacketType.AUDIO_START -> decodeStart(packet.payload)
            PacketType.AUDIO_CHUNK -> decodeChunk(packet.payload)
            PacketType.AUDIO_END -> decodeEnd(packet.payload)
        }
    }

    fun durationMillis(pcmBytes: Long): Long = pcmBytes * 1_000L / BYTES_PER_SECOND

    private fun validateMetadata(metadata: AudioStreamMetadata) {
        if (metadata.sampleRateHz != SAMPLE_RATE_HZ) {
            throw ProtocolException("Unsupported audio sample rate: ${metadata.sampleRateHz}")
        }
        if (metadata.channelCount != CHANNEL_COUNT) {
            throw ProtocolException("Unsupported audio channel count: ${metadata.channelCount}")
        }
        if (metadata.encoding != AudioEncoding.PCM_SIGNED_16_BIT_LITTLE_ENDIAN) {
            throw ProtocolException("Unsupported audio encoding: ${metadata.encoding}")
        }
        if (metadata.declaredPcmBytes !in BYTES_PER_SAMPLE.toLong()..MAXIMUM_PCM_BYTES ||
            metadata.declaredPcmBytes % BYTES_PER_SAMPLE != 0L
        ) {
            throw ProtocolException("Invalid declared PCM byte count: ${metadata.declaredPcmBytes}")
        }
        if (metadata.chunkSizeBytes !in BYTES_PER_SAMPLE..MAXIMUM_CHUNK_BYTES ||
            metadata.chunkSizeBytes % BYTES_PER_SAMPLE != 0
        ) {
            throw ProtocolException("Invalid audio chunk size: ${metadata.chunkSizeBytes}")
        }
    }

    private fun validateChunk(streamId: Long, chunkIndex: Int, pcmBytes: ByteArray) {
        if (streamId <= 0) throw ProtocolException("Invalid audio stream id: $streamId")
        if (chunkIndex !in 0 until MAXIMUM_CHUNK_COUNT) {
            throw ProtocolException("Invalid audio chunk index: $chunkIndex")
        }
        if (pcmBytes.isEmpty() || pcmBytes.size > MAXIMUM_CHUNK_BYTES ||
            pcmBytes.size % BYTES_PER_SAMPLE != 0
        ) {
            throw ProtocolException("Invalid audio chunk PCM length: ${pcmBytes.size}")
        }
    }

    private fun validateEnd(end: AudioEndPayload) {
        if (end.streamId <= 0) throw ProtocolException("Invalid audio stream id: ${end.streamId}")
        if (end.chunkCount !in 0..MAXIMUM_CHUNK_COUNT) {
            throw ProtocolException("Invalid audio chunk count: ${end.chunkCount}")
        }
        if (end.totalPcmBytes < 0 || end.totalPcmBytes > MAXIMUM_PCM_BYTES ||
            end.totalPcmBytes % BYTES_PER_SAMPLE != 0L
        ) {
            throw ProtocolException("Invalid AUDIO_END PCM byte count: ${end.totalPcmBytes}")
        }
    }
}
