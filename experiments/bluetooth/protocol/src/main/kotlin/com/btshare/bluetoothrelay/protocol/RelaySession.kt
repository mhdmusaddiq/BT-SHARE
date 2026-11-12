package com.btshare.bluetoothrelay.protocol

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class RelaySession(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
    private val codec: PacketCodec = PacketCodec(),
    private val rttTracker: RttTracker = RttTracker(),
) : Closeable {
    interface Listener {
        fun onMessageReceived(sequence: Long, text: String)
        fun onAcknowledgementReceived(sequence: Long, roundTripNanos: Long)
        fun onUnexpectedAcknowledgement(sequence: Long)
        fun onAudioStreamStarted(streamId: Long, metadata: AudioStreamMetadata) = Unit
        fun onAudioChunkReceived(streamId: Long, chunkIndex: Int, pcmBytes: ByteArray) = Unit
        fun onAudioStreamEnded(summary: AudioReceiveSummary) = Unit
        fun onAudioProtocolIssue(streamId: Long?, message: String) = Unit
    }

    private val nextSequence = AtomicLong(1L)
    private val writeLock = Any()
    private var incomingAudio: IncomingAudioState? = null

    @Throws(Exception::class)
    fun sendText(text: String): Long {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > codec.maximumPayloadBytes) {
            throw ProtocolException(
                "UTF-8 message is ${payload.size} bytes; maximum is ${codec.maximumPayloadBytes}",
            )
        }

        val sequence = nextSequenceNumber()

        rttTracker.markSent(sequence)
        try {
            write(RelayPacket(PacketType.DATA, sequence, payload))
        } catch (error: Exception) {
            rttTracker.forget(sequence)
            throw error
        }
        return sequence
    }

    @Throws(Exception::class)
    fun sendAudio(
        pcmBytes: ByteArray,
        chunkSizeBytes: Int = AudioWireFormat.DEFAULT_CHUNK_BYTES,
        shouldContinue: () -> Boolean = { true },
    ): AudioSendSummary {
        val metadata = AudioWireFormat.metadataFor(pcmBytes.size, chunkSizeBytes)
        val streamId = nextSequenceNumber()
        write(RelayPacket(PacketType.AUDIO_START, streamId, AudioWireFormat.encodeStart(metadata)))

        var chunkCount = 0
        var bytesSent = 0
        while (bytesSent < pcmBytes.size && shouldContinue()) {
            val endOffset = minOf(bytesSent + chunkSizeBytes, pcmBytes.size)
            val chunkBytes = pcmBytes.copyOfRange(bytesSent, endOffset)
            write(
                RelayPacket(
                    type = PacketType.AUDIO_CHUNK,
                    sequence = nextSequenceNumber(),
                    payload = AudioWireFormat.encodeChunk(streamId, chunkCount, chunkBytes),
                ),
            )
            chunkCount += 1
            bytesSent = endOffset
        }

        val completed = bytesSent == pcmBytes.size
        val result = if (completed) AudioStreamResult.COMPLETED else AudioStreamResult.STOPPED
        write(
            RelayPacket(
                type = PacketType.AUDIO_END,
                sequence = nextSequenceNumber(),
                payload = AudioWireFormat.encodeEnd(
                    AudioEndPayload(
                        streamId = streamId,
                        chunkCount = chunkCount,
                        totalPcmBytes = bytesSent.toLong(),
                        reason = if (completed) AudioEndReason.COMPLETED else AudioEndReason.STOPPED,
                    ),
                ),
            ),
        )
        return AudioSendSummary(streamId, chunkCount, bytesSent.toLong(), result)
    }

    @Throws(Exception::class)
    fun readLoop() {
        try {
            while (true) {
                val packet = codec.read(input) ?: return
                handleIncoming(packet)
            }
        } finally {
            finishIncomingAsTruncated("Connection ended before AUDIO_END")
        }
    }

    internal fun handleIncoming(packet: RelayPacket) {
        when (packet.type) {
            PacketType.DATA -> {
                val text = decodeUtf8(packet.payload)
                listener.onMessageReceived(packet.sequence, text)
                write(RelayPacket(PacketType.ACKNOWLEDGEMENT, packet.sequence))
            }

            PacketType.ACKNOWLEDGEMENT -> {
                val roundTripNanos = rttTracker.acknowledge(packet.sequence)
                if (roundTripNanos == null) {
                    listener.onUnexpectedAcknowledgement(packet.sequence)
                } else {
                    listener.onAcknowledgementReceived(packet.sequence, roundTripNanos)
                }
            }

            PacketType.AUDIO_START -> handleAudioStart(packet)
            PacketType.AUDIO_CHUNK -> handleAudioChunk(packet)
            PacketType.AUDIO_END -> handleAudioEnd(packet)
        }
    }

    private fun handleAudioStart(packet: RelayPacket) {
        finishIncomingAsTruncated("A new AUDIO_START replaced the active stream")
        val metadata = AudioWireFormat.decodeStart(packet.payload)
        incomingAudio = IncomingAudioState(
            streamId = packet.sequence,
            metadata = metadata,
            lastPacketSequence = packet.sequence,
        )
        listener.onAudioStreamStarted(packet.sequence, metadata)
    }

    private fun handleAudioChunk(packet: RelayPacket) {
        val chunk = AudioWireFormat.decodeChunk(packet.payload)
        val state = incomingAudio
        if (state == null) {
            listener.onAudioProtocolIssue(chunk.streamId, "Ignored AUDIO_CHUNK without AUDIO_START")
            return
        }
        if (chunk.streamId != state.streamId) {
            listener.onAudioProtocolIssue(
                chunk.streamId,
                "Ignored AUDIO_CHUNK for stream ${chunk.streamId}; active stream is ${state.streamId}",
            )
            return
        }

        if (packet.sequence <= state.lastPacketSequence) {
            state.outOfOrderChunks += 1
            state.protocolViolation = true
            listener.onAudioProtocolIssue(
                state.streamId,
                "Non-increasing audio packet sequence ${packet.sequence}",
            )
        }
        state.lastPacketSequence = maxOf(state.lastPacketSequence, packet.sequence)

        when {
            chunk.chunkIndex < state.expectedChunkIndex -> {
                state.duplicateChunks += 1
                state.outOfOrderChunks += 1
                listener.onAudioProtocolIssue(
                    state.streamId,
                    "Ignored duplicate/out-of-order audio chunk ${chunk.chunkIndex}; " +
                        "expected ${state.expectedChunkIndex}",
                )
                return
            }
            chunk.chunkIndex > state.expectedChunkIndex -> {
                state.missingChunks += chunk.chunkIndex - state.expectedChunkIndex
                state.outOfOrderChunks += 1
                state.protocolViolation = true
                listener.onAudioProtocolIssue(
                    state.streamId,
                    "Audio chunk discontinuity: received ${chunk.chunkIndex}, " +
                        "expected ${state.expectedChunkIndex}",
                )
            }
        }

        val newTotal = state.receivedBytes + chunk.pcmBytes.size
        if (newTotal > state.metadata.declaredPcmBytes) {
            state.protocolViolation = true
            listener.onAudioProtocolIssue(
                state.streamId,
                "Audio data exceeds declared ${state.metadata.declaredPcmBytes} bytes",
            )
            return
        }
        state.expectedChunkIndex = chunk.chunkIndex + 1
        state.receivedChunks += 1
        state.receivedBytes = newTotal
        listener.onAudioChunkReceived(state.streamId, chunk.chunkIndex, chunk.pcmBytes)
    }

    private fun handleAudioEnd(packet: RelayPacket) {
        val end = AudioWireFormat.decodeEnd(packet.payload)
        val state = incomingAudio
        if (state == null || state.streamId != end.streamId) {
            listener.onAudioProtocolIssue(end.streamId, "Ignored AUDIO_END without matching AUDIO_START")
            return
        }
        incomingAudio = null

        val countsMatch = end.chunkCount == state.receivedChunks
        val bytesMatch = end.totalPcmBytes == state.receivedBytes
        val declarationMatch = end.totalPcmBytes == state.metadata.declaredPcmBytes
        val result = when {
            end.reason == AudioEndReason.STOPPED -> AudioStreamResult.STOPPED
            countsMatch && bytesMatch && declarationMatch && state.missingChunks == 0 &&
                !state.protocolViolation -> AudioStreamResult.COMPLETED
            else -> AudioStreamResult.TRUNCATED
        }
        val detail = when (result) {
            AudioStreamResult.COMPLETED -> "Received complete audio stream"
            AudioStreamResult.STOPPED -> "Sender stopped audio stream"
            AudioStreamResult.TRUNCATED ->
                "AUDIO_END mismatch: sender chunks=${end.chunkCount} bytes=${end.totalPcmBytes}; " +
                    "receiver chunks=${state.receivedChunks} bytes=${state.receivedBytes}"
        }
        listener.onAudioStreamEnded(state.toSummary(result, detail))
    }

    private fun finishIncomingAsTruncated(detail: String) {
        val state = incomingAudio ?: return
        incomingAudio = null
        listener.onAudioStreamEnded(state.toSummary(AudioStreamResult.TRUNCATED, detail))
    }

    private fun write(packet: RelayPacket) {
        synchronized(writeLock) {
            codec.write(output, packet)
        }
    }

    private fun nextSequenceNumber(): Long {
        val sequence = nextSequence.getAndIncrement()
        if (sequence <= 0) throw IllegalStateException("Sequence number space exhausted")
        return sequence
    }

    private fun decodeUtf8(payload: ByteArray): String {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
                .toString()
        } catch (error: Exception) {
            throw ProtocolException("DATA payload is not valid UTF-8", error)
        }
    }

    override fun close() {
        rttTracker.clear()
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

private data class IncomingAudioState(
    val streamId: Long,
    val metadata: AudioStreamMetadata,
    var expectedChunkIndex: Int = 0,
    var receivedChunks: Int = 0,
    var receivedBytes: Long = 0,
    var missingChunks: Int = 0,
    var duplicateChunks: Int = 0,
    var outOfOrderChunks: Int = 0,
    var protocolViolation: Boolean = false,
    var lastPacketSequence: Long,
) {
    fun toSummary(result: AudioStreamResult, detail: String) = AudioReceiveSummary(
        streamId = streamId,
        chunkCount = receivedChunks,
        pcmBytes = receivedBytes,
        missingChunks = missingChunks,
        duplicateChunks = duplicateChunks,
        outOfOrderChunks = outOfOrderChunks,
        result = result,
        detail = detail,
    )
}
